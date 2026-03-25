package com.snelnieuws.db

import cats.effect.IO
import com.snelnieuws.model.{Article, ArticleCreate}
import doobie._
import doobie.implicits._
import cats.effect.unsafe.implicits.global

object ArticleRepository {
  private val xa = Database.transactor

  def findAll(limit: Int = 100): List[Article] = {
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[Article].to[List].transact(xa).unsafeRunSync()
  }

  def findByCategory(category: String, limit: Int = 100): List[Article] = {
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      WHERE LOWER(category) = LOWER($category)
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[Article].to[List].transact(xa).unsafeRunSync()
  }

  def search(query: String, limit: Int = 100): List[Article] = {
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
    """.query[Article].to[List].transact(xa).unsafeRunSync()
  }

  def create(article: ArticleCreate): Article = {
    sql"""
      INSERT INTO articles (author, title, description, url, url_to_image, content, category)
      VALUES (${article.author}, ${article.title}, ${article.description},
              ${article.url}, ${article.urlToImage}, ${article.content}, ${article.category})
      RETURNING id, author, title, description, url, url_to_image,
                to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
    """.query[Article].unique.transact(xa).unsafeRunSync()
  }

  def findById(id: Long): Option[Article] = {
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      WHERE id = $id
    """.query[Article].option.transact(xa).unsafeRunSync()
  }

  def delete(id: Long): Int = {
    sql"DELETE FROM articles WHERE id = $id".update.run.transact(xa).unsafeRunSync()
  }
}
