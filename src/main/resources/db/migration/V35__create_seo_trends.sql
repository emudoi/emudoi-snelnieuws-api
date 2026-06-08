-- Raw Google-Trends batches consumed off Kafka topic seo.trends.collected.
-- One geo's batch fully replaces the geo's prior rows (see
-- SeoTrendsRepository.replaceForGeo). Kept ~48h by SeoTrendsCleanupScheduler;
-- rank = index+1 (1 = hottest). Source/term matching against recent articles
-- writes into article_trending_scores (V36).
CREATE TABLE seo_trends (
  id          BIGSERIAL PRIMARY KEY,
  term        TEXT        NOT NULL,
  geo         VARCHAR(8)  NOT NULL,
  rank        INTEGER     NOT NULL,        -- 1 = hottest
  source      VARCHAR(32) NOT NULL DEFAULT 'google_trends',
  collected_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_seo_trends_geo_collected ON seo_trends (geo, collected_at DESC);
