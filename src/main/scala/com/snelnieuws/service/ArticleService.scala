package com.snelnieuws.service

import com.snelnieuws.repository.{AppClientRepository, ArticleRepository, FeatureFlagRepository}
import com.snelnieuws.model.{Article, ArticleCreate, ArticleRow, ArticleV3Row}
import org.slf4j.LoggerFactory

import java.security.MessageDigest
import java.util.UUID
import scala.collection.mutable
import scala.util.Random

/** Article CRUD + read-time URL rewriting. The constructor takes the image
  * cache wiring so two things happen consistently across both read and
  * write paths:
  *
  *   - On READ (toArticle): any url_to_image starting with `/` gets the
  *     configured public-base-url prepended so iOS receives an absolute
  *     https URL — matching the existing `AsyncImage(url: URL(string:))`
  *     call site. Legacy absolute URLs (the 459 pre-cache rows) are left
  *     as-is.
  *
  *   - On WRITE (create): a non-empty source URL is replaced with its
  *     content-addressed local path, the original is enqueued for the
  *     async download, and the row stores the local path forever. Empty
  *     URLs go straight to the fallback path so iOS always has a working
  *     image to render.
  *
  * The optional `appClientRepository` and `featureFlagRepository` power
  * the personalised-feed filter. When the `personalised_feed_enabled`
  * flag is on and the caller supplies a `clientId`, read paths pull a
  * wider pool, filter by the client's served-id history, and append the
  * newly-served ids. See docs/personalised-feed-plan.md.
  */
class ArticleService(
  repository: ArticleRepository,
  appClientRepository: AppClientRepository,
  featureFlagRepository: FeatureFlagRepository,
  imageCacheService: ImageCacheService,
  imageDownloadWorker: ImageDownloadWorker,
  publicBaseUrl: String
) {

  import ArticleService._

  private val logger = LoggerFactory.getLogger(classOf[ArticleService])

  def findAll(limit: Int = 100): Either[Throwable, List[Article]] =
    repository.findAll(limit).map(_.map(toArticle)).map(interleaveBySource)

  def findByCategory(category: String, limit: Int = 100): Either[Throwable, List[Article]] =
    repository.findByCategory(category, limit).map(_.map(toArticle)).map(interleaveBySource)

  def findByCategories(categories: List[String], limit: Int = 100): Either[Throwable, List[Article]] =
    repository.findByCategories(categories, limit).map(_.map(toArticle)).map(interleaveBySource)

  def search(query: String, limit: Int = 100): Either[Throwable, List[Article]] =
    repository.search(query, limit).map(_.map(toArticle)).map(interleaveBySource)

  def create(article: ArticleCreate): Either[Throwable, Article] = {
    val resolution = resolveUrlToImageOnWrite(article.urlToImage)
    val toStore    = article.copy(urlToImage = resolution.storedRelative)
    val result     = repository.create(toStore).map(toArticle)
    // Fire-and-forget; failures are recorded inside the worker / service
    // and never block the create response.
    resolution.enqueue.foreach(imageDownloadWorker.enqueue)
    result
  }

  def findById(id: Long): Either[Throwable, Option[Article]] =
    repository.findById(id).map(_.map(toArticle))

  def delete(id: Long): Either[Throwable, Int] =
    repository.delete(id)

  def findCategories(): Either[Throwable, List[String]] =
    repository.findDistinctCategories()

  // /everything route logic — empty / "news" → all; otherwise try category, fall back to search.
  def findEverything(query: String, limit: Int = 100): Either[Throwable, List[Article]] =
    if (query.isEmpty || query == "news") {
      findAll(limit)
    } else {
      findByCategory(query, limit).flatMap { byCategory =>
        if (byCategory.nonEmpty) Right(byCategory)
        else search(query, limit)
      }
    }

  // /top-headlines route logic — empty category → all; otherwise filter by category.
  def findTopHeadlines(category: String, limit: Int = 100): Either[Throwable, List[Article]] =
    if (category.isEmpty) findAll(limit)
    else findByCategory(category, limit)

  // ─────────────────────── Personalised-feed overloads ──────────────────────
  //
  // Behaviour matrix:
  //   flag off OR clientId.isEmpty → identical to the legacy overload.
  //   flag on AND clientId.isDefined → wider-pool read, filter by served-id
  //     history, append the freshly-served ids. On exhaustion (filter yields
  //     0 articles), clear the history and serve the top `limit` unfiltered.

  def findAll(limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
    personalisedOrLegacy(
      clientId,
      limit,
      legacy = () => findAll(limit),
      pool   = () => repository.findAllPool()
    )

  def findByCategory(category: String, limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
    personalisedOrLegacy(
      clientId,
      limit,
      legacy = () => findByCategory(category, limit),
      pool   = () => repository.findByCategoryPool(category)
    )

  def findByCategories(categories: List[String], limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
    personalisedOrLegacy(
      clientId,
      limit,
      legacy = () => findByCategories(categories, limit),
      pool   = () => repository.findByCategoriesPool(categories)
    )

  def findEverything(query: String, limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
    if (query.isEmpty || query == "news") {
      findAll(limit, clientId)
    } else {
      // Try the category overload first (personalised when applicable);
      // fall back to the legacy free-text search if no category match.
      // Search bypasses personalisation entirely — callers (NewsServletV2)
      // are expected to short-circuit non-category q values to None before
      // calling this, but the inner search() call here keeps the same
      // bypass semantics regardless.
      findByCategory(query, limit, clientId).flatMap { byCategory =>
        if (byCategory.nonEmpty) Right(byCategory)
        else search(query, limit)
      }
    }

  def findTopHeadlines(category: String, limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
    if (category.isEmpty) findAll(limit, clientId)
    else findByCategory(category, limit, clientId)

  // ─────────────────────── v3 personalised feed ───────────────────────
  //
  // NewsServletV3 was written to call `articleRepository.findV3`
  // directly, which bypassed the personalised-feed filter entirely.
  // Restoring it here so v3 endpoints honour `personalised_feed_enabled`
  // the same way v2 always has. Same rotation contract:
  //
  //   1. flag off OR no clientId → return findV3 (cursor-paginated,
  //      no served-id filter).
  //   2. flag on + clientId → fetch a wider pool (findV3Pool),
  //      filter out the client's `last_served_ids`, take `limit` from
  //      the remainder, append to served_ids.
  //   3. Exhausted (no unseen rows in the pool) → reset served_ids to
  //      the freshly-served top `limit` (rotation cycle restarts).
  //
  // The cursor input from the client is IGNORED on the personalised
  // path because the served_ids set already IS the pagination state.
  // Returning `(rows, hasMore)` keeps the servlet's existing shape;
  // hasMore is true iff the pool had more unseen rows than `limit`.

  /** True when the personalised-feed flag is enabled AND a clientId
    * is available — used by NewsServletV3 to decide between the
    * personalised path and the legacy cursor-only path WITHOUT
    * having to read the flag twice. */
  def personalisedV3Available(clientId: Option[UUID]): Boolean =
    clientId.isDefined &&
      featureFlagRepository.isEnabled(PersonalisedFeedFlag).getOrElse(false)

  /** Personalised v3 read. Returns the next `limit` articles the
    * client has not yet been served, marks them as served, and
    * signals `hasMore` based on the wider pool size. Caller (the
    * servlet) is expected to have already checked
    * `personalisedV3Available(clientId)`; if the check fails this
    * method returns the underlying findV3 result unchanged so
    * mis-ordered callers still get correct data. */
  def personalisedV3Fetch(
    clientId: Option[UUID],
    country: String,
    language: String,
    categories: List[String],
    limit: Int
  ): Either[Throwable, (List[ArticleV3Row], Boolean)] = {
    val flagOn = featureFlagRepository.isEnabled(PersonalisedFeedFlag).getOrElse(false)
    (flagOn, clientId) match {
      case (true, Some(cid)) =>
        for {
          pool   <- repository.findV3Pool(country, language, categories)
          served <- appClientRepository.readServedIds(cid)
          result <- {
            val servedSet = served.toSet
            val fresh = pool.filterNot(r => servedSet.contains(r.id)).take(limit)
            if (fresh.nonEmpty) {
              logger.info(
                s"personalised_v3 client=${hashClient(cid)} pool=${pool.length} " +
                  s"served=${served.size} fresh=${fresh.size} reset=false " +
                  s"language=$language country=$country cats=${categories.mkString(",")}"
              )
              val hasMore = pool.count(r => !servedSet.contains(r.id)) > limit
              appClientRepository.appendServedIds(cid, fresh.map(_.id))
                .map(_ => (fresh, hasMore))
            } else {
              // Exhaust path: reset and serve top `limit` from the
              // pool. Rotation cycle restarts — the user sees the
              // most-recent articles again, but only because they've
              // genuinely caught up with the entire pool.
              val reset = pool.take(limit)
              logger.info(
                s"personalised_v3 client=${hashClient(cid)} pool=${pool.length} " +
                  s"served=${served.size} fresh=0 reset=true " +
                  s"language=$language country=$country cats=${categories.mkString(",")}"
              )
              appClientRepository.setServedIds(cid, reset.map(_.id))
                .map(_ => (reset, pool.length > limit))
            }
          }
        } yield result
      case _ =>
        // Defensive: caller should have routed elsewhere. Honour
        // cursor=None first-page behaviour by delegating to findV3.
        repository.findV3(country, language, categories, cursor = None, limit = limit)
    }
  }

  private def personalisedOrLegacy(
    clientId: Option[UUID],
    limit: Int,
    legacy: () => Either[Throwable, List[Article]],
    pool: () => Either[Throwable, List[ArticleRow]]
  ): Either[Throwable, List[Article]] = {
    val flagOn = featureFlagRepository.isEnabled(PersonalisedFeedFlag).getOrElse(false)
    (flagOn, clientId) match {
      case (true, Some(cid)) => personalisedFetch(cid, limit, pool)
      case _                 => legacy()
    }
  }

  private def personalisedFetch(
    clientId: UUID,
    limit: Int,
    pool: () => Either[Throwable, List[ArticleRow]]
  ): Either[Throwable, List[Article]] =
    for {
      rows   <- pool()
      served <- appClientRepository.readServedIds(clientId)
      result <- {
        val fresh = rows.filterNot(r => served.contains(r.id)).take(limit)
        if (fresh.nonEmpty) {
          logger.info(
            s"personalised_fetch client=${hashClient(clientId)} pool=${rows.length} " +
              s"served=${served.size} fresh=${fresh.size} reset=false"
          )
          appClientRepository.appendServedIds(clientId, fresh.map(_.id))
            .map(_ => fresh)
        } else {
          // Exhaust path: reset history and serve the top `limit` unfiltered.
          // setServedIds replaces wholesale so the next call starts a fresh
          // rotation cycle.
          val reset = rows.take(limit)
          logger.info(
            s"personalised_fetch client=${hashClient(clientId)} pool=${rows.length} " +
              s"served=${served.size} fresh=0 reset=true"
          )
          appClientRepository.setServedIds(clientId, reset.map(_.id))
            .map(_ => reset)
        }
      }
    } yield interleaveBySource(result.map(toArticle))

  private def toArticle(row: ArticleRow): Article = Article(
    id          = row.id.toString,
    author      = row.author,
    title       = row.title,
    description = row.description,
    url         = row.url,
    urlToImage  = absolutiseStoredUrl(row.urlToImage),
    publishedAt = row.publishedAt,
    content     = row.content,
    category    = row.category
  )

  /** Stored values starting with `/` are server-relative paths (the new
    * caching scheme); prepend the configured base URL so the response
    * carries an absolute URL. Legacy absolute URLs flow through unchanged.
    * Empty configured base URL is allowed (used in tests) — relative paths
    * stay relative, which integration tests can still match exactly. */
  private def absolutiseStoredUrl(stored: Option[String]): Option[String] = stored.map { v =>
    if (v.startsWith("/")) publicBaseUrl + v else v
  }

  /** Decide what to write into articles.url_to_image and what (if anything)
    * to enqueue for download. */
  private def resolveUrlToImageOnWrite(raw: Option[String]): UrlToImageWriteResolution = {
    val cleaned = raw.map(_.trim).filter(_.nonEmpty)
    cleaned match {
      case None =>
        // No source URL — point at the bundled fallback so iOS always has
        // something to render. Nothing to enqueue.
        UrlToImageWriteResolution(Some(imageCacheService.fallbackRelativeUrl), None)
      case Some(url) if url.startsWith("/v2/images/") =>
        // Idempotent path — caller already passed us a local URL (e.g. a
        // retry of POST /articles or the consumer reusing a value). Don't
        // re-enqueue; the worker has already handled (or is handling) it.
        UrlToImageWriteResolution(Some(url), None)
      case Some(url) =>
        UrlToImageWriteResolution(Some(imageCacheService.relativeUrlFor(url)), Some(url))
    }
  }
}

object ArticleService {

  private val PersonalisedFeedFlag = "personalised_feed_enabled"

  /** SHA-256 prefix of the install UUID, used only in log lines so production
    * logs don't contain raw install IDs. 4 bytes (8 hex chars) is enough to
    * disambiguate clients while keeping the log compact. */
  private def hashClient(clientId: UUID): String = {
    val bytes = MessageDigest.getInstance("SHA-256").digest(clientId.toString.getBytes("UTF-8"))
    bytes.take(4).map("%02x".format(_)).mkString
  }

  /** Internal write-path decision: what to persist on the row, and whether
    * to enqueue the original source URL for the async download. */
  private case class UrlToImageWriteResolution(
    storedRelative: Option[String],
    enqueue: Option[String]
  )

  // Shuffle articles and reorder so that two articles from the same source
  // (author) are never adjacent when avoidable. Articles with no author each
  // act as their own unique source so they may sit next to each other freely.
  private def interleaveBySource(articles: List[Article]): List[Article] = {
    if (articles.length <= 1) return articles

    val buffers = mutable.Map[String, mutable.Queue[Article]]()
    var anonCounter = 0
    articles.foreach { a =>
      val key = a.author match {
        case Some(s) if s.nonEmpty => s
        case _ =>
          anonCounter += 1
          s"__anon__$anonCounter"
      }
      buffers.getOrElseUpdate(key, mutable.Queue.empty).enqueue(a)
    }
    buffers.keys.foreach { k =>
      val shuffled = Random.shuffle(buffers(k).toList)
      buffers(k) = mutable.Queue(shuffled: _*)
    }

    val result = mutable.ListBuffer[Article]()
    var lastSource: Option[String] = None
    while (buffers.nonEmpty) {
      val ranked = Random.shuffle(buffers.toList).sortBy(-_._2.size)
      val pick = ranked.find { case (k, _) => !lastSource.contains(k) }
        .orElse(ranked.headOption)
      pick.foreach { case (k, q) =>
        result += q.dequeue()
        lastSource = Some(k)
        if (q.isEmpty) buffers.remove(k)
      }
    }
    result.toList
  }
}
