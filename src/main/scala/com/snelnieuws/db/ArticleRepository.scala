package com.snelnieuws.db

import cats.effect.IO
import com.snelnieuws.model.{ArticleRow, ArticleCreate, SummarizedArticleExport}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import cats.effect.unsafe.implicits.global

import java.time.OffsetDateTime

object ArticleRepository {
  private val xa = Database.transactor

  def findAll(limit: Int = 100): List[ArticleRow] = {
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[ArticleRow].to[List].transact(xa).unsafeRunSync()
  }

  def findByCategory(category: String, limit: Int = 100): List[ArticleRow] = {
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      WHERE LOWER(category) = LOWER($category)
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[ArticleRow].to[List].transact(xa).unsafeRunSync()
  }

  def search(query: String, limit: Int = 100): List[ArticleRow] = {
    val searchPattern = s"%${query.toLowerCase}%"
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      WHERE LOWER(title) LIKE $searchPattern
         OR LOWER(description) LIKE $searchPattern
         OR LOWER(content) LIKE $searchPattern
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[ArticleRow].to[List].transact(xa).unsafeRunSync()
  }

  def create(article: ArticleCreate): ArticleRow = {
    sql"""
      INSERT INTO articles (author, title, description, url, url_to_image, content, category)
      VALUES (${article.author}, ${article.title}, ${article.description},
              ${article.url}, ${article.urlToImage}, ${article.content}, ${article.category})
      RETURNING id, author, title, description, url, url_to_image,
                to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
    """.query[ArticleRow].unique.transact(xa).unsafeRunSync()
  }

  def findById(id: Long): Option[ArticleRow] = {
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      WHERE id = $id
    """.query[ArticleRow].option.transact(xa).unsafeRunSync()
  }

  def delete(id: Long): Int = {
    sql"DELETE FROM articles WHERE id = $id".update.run.transact(xa).unsafeRunSync()
  }

  /** Delete articles whose `published_at` is older than `cutoff`. Returns row count. */
  def deletePublishedBefore(cutoff: OffsetDateTime): Int = {
    sql"DELETE FROM articles WHERE published_at < $cutoff".update.run.transact(xa).unsafeRunSync()
  }

  /** Distinct non-null, non-empty categories that currently have at least one article. */
  def findDistinctCategories(): List[String] = {
    sql"""
      SELECT DISTINCT category
      FROM articles
      WHERE category IS NOT NULL AND category <> ''
      ORDER BY category
    """.query[String].to[List].transact(xa).unsafeRunSync()
  }

  /**
   * Insert-or-update an article keyed on `title`, using a summarized-article event
   * from the ingestion-api. Title is the dedup key — matches the upstream
   * `ingestion_articles` UNIQUE(title) constraint. `content` stays null (the
   * export only carries summary fields).
   */
  def upsertByTitle(article: SummarizedArticleExport): Int = {
    val publishedAt = OffsetDateTime.parse(article.publishedAt)
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
    """.update.run.transact(xa).unsafeRunSync()
  }
}
