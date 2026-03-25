package com.snelnieuws.model

import java.time.Instant

case class Article(
  id: Long,
  author: Option[String],
  title: String,
  description: Option[String],
  url: String,
  urlToImage: Option[String],
  publishedAt: String,
  content: Option[String],
  category: Option[String]
)

case class NewsFetchResponse(
  status: String,
  totalResults: Int,
  articles: List[Article]
)

case class ArticleCreate(
  author: Option[String],
  title: String,
  description: Option[String],
  url: String,
  urlToImage: Option[String],
  content: Option[String],
  category: Option[String]
)
