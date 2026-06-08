-- Per-article trending boost, derived by matching the latest seo_trends terms
-- (V35) against the last 48h of articles + eulang_articles. Written by
-- TrendingScoreService after each batch (one geo fully replaced per write),
-- loaded on the read path by ArticleService.blendedV3Pool filtered on
-- expires_at > now(). Rows expire ~8h after a batch and are swept by
-- SeoTrendsCleanupScheduler. source = 'articles' | 'eulang'; the (source,
-- article_id) pair maps to the served_ids key scheme (eulang ids offset by
-- EulangIdOffset) when loaded.
CREATE TABLE article_trending_scores (
  source      VARCHAR(16) NOT NULL,        -- 'articles' | 'eulang'
  article_id  BIGINT      NOT NULL,
  geo         VARCHAR(8)  NOT NULL,
  score       REAL        NOT NULL,        -- [0,1]
  expires_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (source, article_id, geo)
);
CREATE INDEX idx_ats_geo_exp ON article_trending_scores (geo, expires_at);
