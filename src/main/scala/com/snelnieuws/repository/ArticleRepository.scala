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

  /** Delete articles whose `published_at` is older than `cutoff`. Returns row count. */
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

  /** Count articles whose id is greater than `sinceId`. When `sinceId` is None
   *  (no prior dispatch), counts all articles in the table.
   */
  def countSinceId(sinceId: Option[Long]): Either[Throwable, Int] =
    try {
      val q = sinceId match {
        case Some(id) => sql"SELECT COUNT(*) FROM articles WHERE id > $id"
        case None     => sql"SELECT COUNT(*) FROM articles"
      }
      Right(q.query[Int].unique.transact(transactor).unsafeRunSync())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to count articles since=$sinceId: ${e.getMessage}", e)
        Left(e)
    }

  /** Largest article id currently in the table, or None if empty. */
  def latestId(): Either[Throwable, Option[Long]] =
    try
      Right(
        sql"SELECT MAX(id) FROM articles"
          .query[Option[Long]]
          .unique
          .transact(transactor)
          .unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to fetch latest article id: ${e.getMessage}", e)
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
  // Country-scoped feed with cursor pagination. Caller passes the request
  // country (already validated to be 2 lowercase letters). Rows match if
  // `articles.country` equals it OR the request country appears in
  // `articles.shared_countries`. Category filter is optional — when present,
  // an article matches if its `category` is in the list OR its
  // `shared_categories` array overlaps the list. Returns (page, hasMore)
  // where `hasMore` is computed by fetching one extra row and checking.

  def findV3(
    country: String,
    categories: List[String],
    cursor: Option[(Instant, Long)],
    limit: Int
  ): Either[Throwable, (List[ArticleV3Row], Boolean)] =
    try {
      val lowerCats = categories.map(_.toLowerCase)
      val baseSelect = fr"""
        SELECT id, author, title, description, url, url_to_image,
               published_at, content, category, country,
               (country = $country OR $country = ANY(shared_countries)) AS is_local
        FROM articles
        WHERE (country = $country OR $country = ANY(shared_countries))
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
          s"Failed to load v3 feed country=$country categories=${categories.mkString(",")} cursor=$cursor: ${e.getMessage}",
          e
        )
        Left(e)
    }

  /** Single-article fetch with the same v3 projection (including `is_local`). */
  def findV3ById(country: String, id: Long): Either[Throwable, Option[ArticleV3Row]] =
    try
      Right(
        sql"""
          SELECT id, author, title, description, url, url_to_image,
                 published_at, content, category, country,
                 (country = $country OR $country = ANY(shared_countries)) AS is_local
          FROM articles
          WHERE id = $id
        """.query[ArticleV3Row].option.transact(transactor).unsafeRunSync()
      )
    catch {
      case e: Exception =>
        logger.error(s"Failed to load v3 article id=$id country=$country: ${e.getMessage}", e)
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
      Right(
        sql"""
          INSERT INTO articles (author, title, description, url, url_to_image, published_at, content,
                                category, shared_categories, country, shared_countries)
          VALUES (${article.author}, ${article.title}, ${article.description},
                  ${article.url}, ${article.urlToImage}, $publishedAt, NULL,
                  ${article.category}, ${article.sharedCategories},
                  ${article.country}, ${article.sharedCountries})
          ON CONFLICT (title) DO UPDATE SET
            author            = EXCLUDED.author,
            description       = EXCLUDED.description,
            url               = EXCLUDED.url,
            url_to_image      = EXCLUDED.url_to_image,
            published_at      = EXCLUDED.published_at,
            category          = EXCLUDED.category,
            shared_categories = EXCLUDED.shared_categories,
            country           = EXCLUDED.country,
            shared_countries  = EXCLUDED.shared_countries
        """.update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to upsert summarized article title='${article.title}': ${e.getMessage}", e)
        Left(e)
    }
}
