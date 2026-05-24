package com.snelnieuws.model

/** Row inserted into `top_news_videos` by VideoDispatchService. Only
  * the input columns the dispatch path controls — `id`, `status`,
  * `created_at` etc. are filled in by the table defaults.
  */
case class TopNewsVideoRow(
  text:        String,
  anchor:      String,
  variant:     String,
  description: Option[String]
)
