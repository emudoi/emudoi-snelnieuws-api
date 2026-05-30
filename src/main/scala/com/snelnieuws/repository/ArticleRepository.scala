package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{ArticleCreate, ArticleRow, ArticleV3Row, SummarizedArticleExport}
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.time.{Instant, OffsetDateTime, ZoneOffset}

class ArticleRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[ArticleRepository])

  // Resolved lazily so tests can defer DB connection until the container is up.
  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  def findAll(limit: Int = 100): Either[Throwable, List[ArticleRow]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load articles: ${e.getMessage}", e)
        Left(e)
    }

  def findByCategory(category: String, limit: Int = 100): Either[Throwable, List[ArticleRow]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          WHERE LOWER(category) = LOWER($category)
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load articles by category=$category: ${e.getMessage}", e)
        Left(e)
    }

  /** Articles whose category matches any of the supplied list. Comparison
    * is case-insensitive; callers should pre-lowercase their list to match
    * the binding type cleanly. Empty list yields no rows (caller's
    * responsibility to short-circuit before calling).
    */
  def findByCategories(categories: List[String], limit: Int = 100): Either[Throwable, List[ArticleRow]] =
    try {
      val lowercased = categories.map(_.toLowerCase)
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          WHERE LOWER(category) = ANY($lowercased)
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load articles by categories=${categories.mkString(",")}: ${e.getMessage}", e)
        Left(e)
    }

  // ────────────────────── Personalised-feed pool reads ──────────────────────
  //
  // Same projection and ordering as findAll / findByCategory /
  // findByCategories, just a wider default LIMIT so the post-filter result
  // still has ~100 articles available after excluding the client's seen
  // history. See ArticleService.personalisedFetch and
  // docs/personalised-feed-plan.md.

  def findAllPool(limit: Int = 300): Either[Throwable, List[ArticleRow]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load article pool: ${e.getMessage}", e)
        Left(e)
    }

  def findByCategoryPool(category: String, limit: Int = 300): Either[Throwable, List[ArticleRow]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          WHERE LOWER(category) = LOWER($category)
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load article pool by category=$category: ${e.getMessage}", e)
        Left(e)
    }

  def findByCategoriesPool(categories: List[String], limit: Int = 300): Either[Throwable, List[ArticleRow]] =
    try {
      val lowercased = categories.map(_.toLowerCase)
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          WHERE LOWER(category) = ANY($lowercased)
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load article pool by categories=${categories.mkString(",")}: ${e.getMessage}", e)
        Left(e)
    }

  def search(query: String, limit: Int = 100): Either[Throwable, List[ArticleRow]] =
    try {
      val searchPattern = s"%${query.toLowerCase}%"
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          WHERE LOWER(title) LIKE $searchPattern
             OR LOWER(description) LIKE $searchPattern
             OR LOWER(content) LIKE $searchPattern
          ORDER BY published_at DESC
          LIMIT $limit
        """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to search articles q=$query: ${e.getMessage}", e)
        Left(e)
    }

  def create(article: ArticleCreate): Either[Throwable, ArticleRow] =
    try
      Right(
        sql"""
          INSERT INTO articles (author, title, description, url, url_to_image, content, category)
          VALUES (${article.author}, ${article.title}, ${article.description},
                  ${article.url}, ${article.urlToImage}, ${article.content}, ${article.category})
          RETURNING id, author, title, description, url, url_to_image,
                    to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
        """.query[ArticleRow].unique.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to create article title='${article.title}': ${e.getMessage}", e)
        Left(e)
    }

  def findById(id: Long): Either[Throwable, Option[ArticleRow]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
          FROM articles
          WHERE id = $id
        """.query[ArticleRow].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load article id=$id: ${e.getMessage}", e)
        Left(e)
    }

  def delete(id: Long): Either[Throwable, Int] =
    try
      Right(sql"DELETE FROM articles WHERE id = $id".update.run.transact(transactor).unsafeRunSync())
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete article id=$id: ${e.getMessage}", e)
        Left(e)
    }

  /** Delete articles whose `published_at` is older than `cutoff`. Returns row count.
    *
    * Kept for backwards compatibility (older call sites may exist). The
    * cleanup scheduler now drives per-language deletion via
    * [[deletePublishedBeforeForLanguage]]; prefer that for new work. */
  def deletePublishedBefore(cutoff: OffsetDateTime): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM articles WHERE published_at < $cutoff".update.run
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to delete articles before $cutoff: ${e.getMessage}", e)
        Left(e)
    }

  /** Languages currently present in the table. Order undefined. Empty
    * list when the table is empty. Used by ArticleCleanupScheduler to
    * fan out the per-language cleanup pass. */
  def distinctLanguages(): Either[Throwable, List[String]] =
    try
      Right(
        sql"SELECT DISTINCT language FROM articles".query[String].to[List]
          .transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to list distinct languages: ${e.getMessage}", e)
        Left(e)
    }

  /** Count rows for a single language. Driving the MinArticleCount
    * floor per-language is the whole point of the cleanup rewrite — see
    * language_support/backend_tasks.txt §13. */
  def countByLanguage(language: String): Either[Throwable, Int] =
    try
      Right(
        sql"SELECT COUNT(*) FROM articles WHERE language = $language"
          .query[Int].unique.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to count articles for language=$language: ${e.getMessage}", e)
        Left(e)
    }

  /** Delete rows where language = $language AND published_at < $cutoff.
    * Returns deleted row count. */
  def deletePublishedBeforeForLanguage(
    language: String,
    cutoff: OffsetDateTime
  ): Either[Throwable, Int] =
    try
      Right(
        sql"DELETE FROM articles WHERE language = $language AND published_at < $cutoff"
          .update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(
          s"Failed to delete articles for language=$language before $cutoff: ${e.getMessage}", e
        )
        Left(e)
    }

  def count(): Either[Throwable, Int] =
    try
      Right(
        sql"SELECT COUNT(*) FROM articles".query[Int].unique
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to count articles: ${e.getMessage}", e)
        Left(e)
    }

  /** Articles for one language published on or after `since`, for
    * top-story selection. Replaces the old global `(lastAsOf,
    * currentMax]` id-window: each language now selects from its OWN
    * recent articles, so a high-volume language can't push a shared
    * watermark past a slower language's fresh articles (per-language
    * pool fairness).
    *
    * Returns the full ArticleV3Row shape so the selector can use
    * `author` (= publisher domain), `category`, `country`,
    * `publishedAt`, `url` without a second query. is_local is forced
    * false (empty country placeholder) — the selector ignores it. */
  def findRecentForTopStory(
    language: String,
    since: OffsetDateTime
  ): Either[Throwable, List[ArticleV3Row]] =
    try {
      val country = "" // placeholder — selector ignores is_local
      val q = fr"""
        SELECT id, author, title, description, url, url_to_image,
               published_at, content, category, country,
               COALESCE(country = $country OR $country = ANY(shared_countries), FALSE) AS is_local,
               language
        FROM articles
        WHERE language = $language
          AND published_at >= $since
        ORDER BY id ASC
      """
      Right(q.query[ArticleV3Row].to[List].transact(transactor).unsafeRunSync())
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to load recent window for top-story lang=$language since=$since: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Distinct non-null, non-empty categories that currently have at least one article. */
  def findDistinctCategories(): Either[Throwable, List[String]] =
    try
      Right(
        sql"""
          SELECT DISTINCT category
          FROM articles
          WHERE category IS NOT NULL AND category <> ''
          ORDER BY category
        """.query[String].to[List].transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load categories: ${e.getMessage}", e)
        Left(e)
    }

  // ─────────────────────────────── v3 reads ───────────────────────────────
  //
  // Cursor-paginated feed. The request country is a **labeling** input,
  // not a filter — it produces the per-article `is_local` boolean (matches
  // article.country OR appears in shared_countries) but never excludes
  // rows. This keeps the apps from going blank while existing articles
  // in the DB still carry NULL country / shared_countries.
  //
  // Category filter IS a filter — when present, an article matches if its
  // `category` is in the list OR its `shared_categories` array overlaps
  // the list. is_local uses COALESCE(..., FALSE) so NULL country resolves
  // to false rather than NULL.

  def findV3(
    country: String,
    language: String,
    categories: List[String],
    cursor: Option[(Instant, Long)],
    limit: Int
  ): Either[Throwable, (List[ArticleV3Row], Boolean)] =
    try {
      val lowerCats = categories.map(_.toLowerCase)
      val baseSelect = fr"""
        SELECT id, author, title, description, url, url_to_image,
               published_at, content, category, country,
               COALESCE(country = $country OR $country = ANY(shared_countries), FALSE) AS is_local,
               language
        FROM articles
        WHERE language = $language
      """

      val categoryFilter: Fragment =
        if (lowerCats.isEmpty) Fragment.empty
        // `shared_categories` is text[]; the JDBC driver binds Scala
        // List[String] as varchar[], so an explicit ::text[] cast is needed
        // for the && (array overlap) operator to type-check.
        else fr"AND (LOWER(category) = ANY($lowerCats) OR shared_categories && $lowerCats::text[])"

      val cursorFilter: Fragment = cursor match {
        case Some((pubAt, id)) =>
          val pubOdt = OffsetDateTime.ofInstant(pubAt, ZoneOffset.UTC)
          fr"AND (published_at, id) < ($pubOdt, $id)"
        case None => Fragment.empty
      }

      val orderLimit = fr"ORDER BY published_at DESC, id DESC LIMIT ${limit + 1}"

      val q = baseSelect ++ categoryFilter ++ cursorFilter ++ orderLimit
      val rows = q.query[ArticleV3Row].to[List].transact(transactor).unsafeRunSync()
      val hasMore = rows.size > limit
      Right((rows.take(limit), hasMore))
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to load v3 feed country=$country language=$language categories=${categories.mkString(",")} cursor=$cursor: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Wider-pool variant of `findV3` used by the personalised-feed
    * read path (ArticleService.personalisedV3Fetch). Same shape as
    * findV3 (returns ArticleV3Row, applies language + optional
    * categories filter, computes is_local against country) but
    *
    *   - NO cursor — the personalised filter (`served_ids`) IS the
    *     pagination state; an SQL cursor on top of it would double-
    *     count exclusions.
    *   - WIDER limit (default 300) so the service-layer post-filter
    *     against served_ids still has ~100 articles available even
    *     when the client has been served 200 already.
    *
    * Without this method NewsServletV3 would have to use findV3 and
    * post-filter — but findV3 only returns `limit` rows, so a client
    * with many served_ids would get fewer-than-requested results
    * page after page. The wider pool gives the service room to drop
    * served rows and still hit `limit`.
    */
  def findV3Pool(
    country: String,
    language: String,
    categories: List[String],
    limit: Int = 300
  ): Either[Throwable, List[ArticleV3Row]] =
    try {
      val lowerCats = categories.map(_.toLowerCase)
      val baseSelect = fr"""
        SELECT id, author, title, description, url, url_to_image,
               published_at, content, category, country,
               COALESCE(country = $country OR $country = ANY(shared_countries), FALSE) AS is_local,
               language
        FROM articles
        WHERE language = $language
      """
      val categoryFilter: Fragment =
        if (lowerCats.isEmpty) Fragment.empty
        else fr"AND (LOWER(category) = ANY($lowerCats) OR shared_categories && $lowerCats::text[])"
      val orderLimit = fr"ORDER BY published_at DESC, id DESC LIMIT $limit"
      val q = baseSelect ++ categoryFilter ++ orderLimit
      Right(q.query[ArticleV3Row].to[List].transact(transactor).unsafeRunSync())
    } catch {
      case e: Exception =>
        logger.error(
          s"Failed to load v3 pool country=$country language=$language categories=${categories.mkString(",")}: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** v3 read keyed on a set of URLs — used by /v3/feed/semantic to
    * fetch local article rows for the URLs Milvus returned as kNN
    * matches (semantic_search/backend_tasks.txt §9). No cursor + no
    * limit; the caller merges with the categorical results in memory
    * and paginates the union there.
    *
    * Language + country filters still apply: the ingestion-api bridge
    * already filters by language at the JOIN level, but applying it
    * locally too is defence-in-depth + matches the user's picker
    * choice when the bridge's language filter was None.
    */
  def findV3ByUrls(
    urls: List[String],
    country: String,
    language: String
  ): Either[Throwable, List[ArticleV3Row]] =
    if (urls.isEmpty) Right(Nil)
    else
      try
        Right(
          sql"""
            SELECT id, author, title, description, url, url_to_image,
                   published_at, content, category, country,
                   COALESCE(country = $country OR $country = ANY(shared_countries), FALSE) AS is_local,
                   language
            FROM articles
            WHERE url = ANY($urls)
              AND language = $language
          """.query[ArticleV3Row].to[List].transact(transactor).unsafeRunSync()
        )
      catch {
        case e: Exception =>
          logger.error(
            s"Failed to load v3 articles by urls count=${urls.size} country=$country language=$language: ${e.getMessage}",
            e
          )
          Left(e)
      }

  /** Single-article fetch with the same v3 projection (including `is_local`). */
  def findV3ById(country: String, language: String, id: Long): Either[Throwable, Option[ArticleV3Row]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 published_at, content, category, country,
                 COALESCE(country = $country OR $country = ANY(shared_countries), FALSE) AS is_local,
                 language
          FROM articles
          WHERE id = $id AND language = $language
        """.query[ArticleV3Row].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load v3 article id=$id country=$country language=$language: ${e.getMessage}", e)
        Left(e)
    }

  /**
   * Insert-or-update an article keyed on `title`, using a summarized-article event
   * from the ingestion-api. Title is the dedup key — matches the upstream
   * `ingestion_articles` UNIQUE(title) constraint. `content` stays null (the
   * export only carries summary fields).
   */
  def upsertByTitle(article: SummarizedArticleExport): Either[Throwable, Int] =
    try {
      val publishedAt = OffsetDateTime.parse(article.publishedAt)
      // None on the payload defaults to 'en' at the DB layer — older
      // producer deployments that haven't yet shipped the language emit
      // (language_support/backend_tasks.txt §4) decode to None here.
      val language = article.language.getOrElse("en")
      Right(
        sql"""
          INSERT INTO articles (author, title, description, url, url_to_image, published_at, content,
                                category, shared_categories, country, shared_countries, language)
          VALUES (${article.author}, ${article.title}, ${article.description},
                  ${article.url}, ${article.urlToImage}, $publishedAt, NULL,
                  ${article.category}, ${article.sharedCategories},
                  ${article.country}, ${article.sharedCountries}, $language)
          ON CONFLICT (title) DO UPDATE SET
            author            = EXCLUDED.author,
            description       = EXCLUDED.description,
            url               = EXCLUDED.url,
            url_to_image      = EXCLUDED.url_to_image,
            published_at      = EXCLUDED.published_at,
            category          = EXCLUDED.category,
            shared_categories = EXCLUDED.shared_categories,
            country           = EXCLUDED.country,
            shared_countries  = EXCLUDED.shared_countries,
            language          = EXCLUDED.language
        """.update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to upsert summarized article title='${article.title}': ${e.getMessage}", e)
        Left(e)
    }

  /** Rewrite an already-upserted article's url_to_image to the fallback
    * route. Used by SummarizedArticleConsumer when the source image URL
    * uses an unfetchable scheme (data:/blob:/ftp:) — the row was first
    * upserted with a content-addressed URL on the assumption that the
    * worker would fetch the bytes, but the worker can't, so the client
    * should see the bundled fallback directly instead of round-tripping
    * through the servlet. */
  def setUrlToImageFallback(title: String): Either[Throwable, Int] =
    try
      Right(
        sql"""
          UPDATE articles
          SET url_to_image = '/v2/images/_fallback'
          WHERE title = $title
        """.update.run.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to set url_to_image fallback title='$title': ${e.getMessage}", e)
        Left(e)
    }
}
