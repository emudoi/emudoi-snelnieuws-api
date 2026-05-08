package com.snelnieuws.service

import com.snelnieuws.repository.ArticleRepository
import com.snelnieuws.model.{Article, ArticleCreate, ArticleRow}

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
  */
class ArticleService(
  repository: ArticleRepository,
  imageCacheService: ImageCacheService,
  imageDownloadWorker: ImageDownloadWorker,
  publicBaseUrl: String
) {

  import ArticleService._

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
