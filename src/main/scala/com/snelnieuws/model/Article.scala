package com.snelnieuws.model

import java.time.OffsetDateTime

case class ArticleRow(
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

case class Article(
  id: String,
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

/** Repo projection for v3 reads. `publishedAt` is raw (Instant) so the
  * servlet can both format it for the JSON payload and use it directly for
  * the next-page cursor without re-parsing.
  */
case class ArticleV3Row(
  id: Long,
  author: Option[String],
  title: String,
  description: Option[String],
  url: String,
  urlToImage: Option[String],
  publishedAt: OffsetDateTime,
  content: Option[String],
  category: Option[String],
  country: Option[String],
  isLocal: Boolean,
  language: String
)
