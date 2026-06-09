-- Recommender Phase-0 (Option B): make article-bearing events self-describing.
--
-- An event's article_id is a snelnieuws-api BIGSERIAL that is reassigned on
-- ingest (dedup by title) and purged by the 72h articles cleanup. Once the
-- row is gone, the id maps to nothing — neither here nor in the ingestion
-- store (whose ids are an independent sequence). The only stable cross-side
-- key is title/url, which events did not carry.
--
-- We stamp title + url onto each article-bearing event at write time (server
-- side, joined from the live catalog). Together with the already-present
-- category/source/language/country/age snapshot this lets the recommender
-- reconstruct article features long after the serving row is cleaned up, and
-- recover full content/embeddings from the ingestion store by joining on title.
ALTER TABLE user_events
  ADD COLUMN IF NOT EXISTS title TEXT,
  ADD COLUMN IF NOT EXISTS url   TEXT;
