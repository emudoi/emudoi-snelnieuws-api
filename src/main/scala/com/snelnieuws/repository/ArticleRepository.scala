package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{ArticleCreate, ArticleRow, SummarizedArticleExport}
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime

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
          INSERT INTO articles (author, title, description, url, url_to_image, published_at, content, category)
          VALUES (${article.author}, ${article.title}, ${article.description},
                  ${article.url}, ${article.urlToImage}, $publishedAt, NULL, ${article.category})
          ON CONFLICT (title) DO UPDATE SET
            author       = EXCLUDED.author,
            description  = EXCLUDED.description,
            url          = EXCLUDED.url,
            url_to_image = EXCLUDED.url_to_image,
            published_at = EXCLUDED.published_at,
            category     = EXCLUDED.category
        """.update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to upsert summarized article title='${article.title}': ${e.getMessage}", e)
        Left(e)
    }
}
