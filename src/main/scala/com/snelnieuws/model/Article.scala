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

/** Minimal catalog projection used to stamp article-bearing user_events at
  * write time (recommender Phase-0). `source` is the article's author/publisher.
  * Fetched by raw per-table id; the eulang/articles distinction is carried by
  * the lookup, not this row. */
case class ArticleStamp(
  id: Long,
  title: String,
  url: String,
  category: Option[String],
  source: Option[String],
  country: Option[String],
  language: String
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
