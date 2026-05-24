-- top_news_videos is moving to ingestion-api (it owns Milvus + the
-- summarized_articles source the selector reads). Drop the local copy
-- here so it can't be written to from this side anymore. The two
-- existing `pending` rows are orphans of a deploy that's being rolled
-- back the same day they were inserted — never claimed by the renderer.
DROP TABLE IF EXISTS top_news_videos;
