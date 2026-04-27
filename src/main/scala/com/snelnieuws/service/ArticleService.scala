package com.snelnieuws.service

import com.snelnieuws.db.ArticleRepository
import com.snelnieuws.model.{Article, ArticleCreate, ArticleRow}

object ArticleService {

  private def toArticle(row: ArticleRow): Article = Article(
    id = row.id.toString,
    author = row.author,
    title = row.title,
    description = row.description,
    url = row.url,
    urlToImage = row.urlToImage,
    publishedAt = row.publishedAt,
    content = row.content,
    category = row.category
  )

  def findAll(limit: Int = 100): List[Article] =
    ArticleRepository.findAll(limit).map(toArticle)

  def findByCategory(category: String, limit: Int = 100): List[Article] =
    ArticleRepository.findByCategory(category, limit).map(toArticle)

  def search(query: String, limit: Int = 100): List[Article] =
    ArticleRepository.search(query, limit).map(toArticle)

  def create(article: ArticleCreate): Article =
    toArticle(ArticleRepository.create(article))

  def findById(id: Long): Option[Article] =
    ArticleRepository.findById(id).map(toArticle)

  def delete(id: Long): Int =
    ArticleRepository.delete(id)

  def findCategories(): List[String] =
    ArticleRepository.findDistinctCategories()
}
