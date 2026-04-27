package com.snelnieuws.api

import org.scalatra._
import org.scalatra.json._
import org.json4s.{DefaultFormats, Formats}
import com.snelnieuws.service.ArticleService
import com.snelnieuws.model.{ArticleCreate, NewsFetchResponse}

class NewsServlet extends ScalatraServlet with JacksonJsonSupport {
  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  // GET /everything
  // Query params: q (search query), sortBy (publishedAt)
  get("/everything") {
    val query = params.getOrElse("q", "")
    val limit = params.getOrElse("pageSize", "100").toInt

    val articles = if (query.isEmpty || query == "news") {
      ArticleService.findAll(limit)
    } else {
      // First try category, then search
      val byCategory = ArticleService.findByCategory(query, limit)
      if (byCategory.nonEmpty) byCategory
      else ArticleService.search(query, limit)
    }

    NewsFetchResponse(
      status = "ok",
      totalResults = articles.length,
      articles = articles
    )
  }

  // GET /top-headlines
  // Query params: category, country (ignored)
  get("/top-headlines") {
    val category = params.getOrElse("category", "")
    val limit = params.getOrElse("pageSize", "100").toInt

    val articles = if (category.isEmpty) {
      ArticleService.findAll(limit)
    } else {
      ArticleService.findByCategory(category, limit)
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
    val created = ArticleService.create(article)
    Created(created)
  }

  // GET /articles/:id - Get single article
  get("/articles/:id") {
    val id = params("id").toLong
    ArticleService.findById(id) match {
      case Some(article) => article
      case None => NotFound(Map("error" -> "Article not found"))
    }
  }

  // DELETE /articles/:id - Delete article
  delete("/articles/:id") {
    val id = params("id").toLong
    val deleted = ArticleService.delete(id)
    if (deleted > 0) NoContent()
    else NotFound(Map("error" -> "Article not found"))
  }

  // GET /categories - List categories that currently have at least one article
  get("/categories") {
    val categories = ArticleService.findCategories()
    Map("categories" -> categories)
  }

  // App config
  get("/app/config") {
    Map("minVersion" -> "1.2.0")
  }

  // Health check
  get("/health") {
    Map("status" -> "ok", "service" -> "SnelNieuws API")
  }

  // Privacy policy — referenced from App Store listing and in-app menu
  get("/privacy") {
    serveStatic("static/privacy.html")
  }

  // Support page — referenced from App Store listing
  get("/support") {
    serveStatic("static/support.html")
  }

  private def serveStatic(resourcePath: String): Any = {
    Option(getClass.getClassLoader.getResourceAsStream(resourcePath)) match {
      case Some(stream) =>
        try {
          val bytes = stream.readAllBytes()
          contentType = "text/html; charset=utf-8"
          response.setHeader("Cache-Control", "public, max-age=3600")
          new String(bytes, "UTF-8")
        } finally {
          stream.close()
        }
      case None =>
        NotFound(Map("error" -> s"Resource $resourcePath not found"))
    }
  }
}
