package com.snelnieuws.api

import org.scalatra._
import org.scalatra.json._
import org.json4s.{DefaultFormats, Formats}
import com.snelnieuws.db.ArticleRepository
import com.snelnieuws.model.{ArticleCreate, NewsFetchResponse}

class NewsServlet extends ScalatraServlet with JacksonJsonSupport {
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  // GET /v2/everything - matches News API format
  // Query params: q (search query), sortBy (publishedAt)
  get("/v2/everything") {
    val query = params.getOrElse("q", "")
    val limit = params.getOrElse("pageSize", "100").toInt

    val articles = if (query.isEmpty || query == "news") {
      ArticleRepository.findAll(limit)
    } else {
      // First try category, then search
      val byCategory = ArticleRepository.findByCategory(query, limit)
      if (byCategory.nonEmpty) byCategory
      else ArticleRepository.search(query, limit)
    }

    NewsFetchResponse(
      status = "ok",
      totalResults = articles.length,
      articles = articles
    )
  }

  // GET /v2/top-headlines - matches News API format
  // Query params: category, country (ignored)
  get("/v2/top-headlines") {
    val category = params.getOrElse("category", "")
    val limit = params.getOrElse("pageSize", "100").toInt

    val articles = if (category.isEmpty) {
      ArticleRepository.findAll(limit)
    } else {
      ArticleRepository.findByCategory(category, limit)
    }

    NewsFetchResponse(
      status = "ok",
      totalResults = articles.length,
      articles = articles
    )
  }

  // POST /articles - Create new article
  post("/articles") {
    val article = parsedBody.extract[ArticleCreate]
    val created = ArticleRepository.create(article)
    Created(created)
  }

  // GET /articles/:id - Get single article
  get("/articles/:id") {
    val id = params("id").toLong
    ArticleRepository.findById(id) match {
      case Some(article) => article
      case None => NotFound(Map("error" -> "Article not found"))
    }
  }

  // DELETE /articles/:id - Delete article
  delete("/articles/:id") {
    val id = params("id").toLong
    val deleted = ArticleRepository.delete(id)
    if (deleted > 0) NoContent()
    else NotFound(Map("error" -> "Article not found"))
  }

  // Health check
  get("/health") {
    Map("status" -> "ok", "service" -> "SnelNieuws API")
  }
}
