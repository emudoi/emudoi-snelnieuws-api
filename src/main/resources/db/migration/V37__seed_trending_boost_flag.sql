-- Kill switch for the trending-search ranking boost. When false, the v3
-- personalised feed passes Map.empty into blendedV3Pool, so byScore reduces
-- to pure recency — byte-for-byte the pre-boost feed. When true (and the
-- request country has live article_trending_scores rows), recent articles
-- matching currently-trending search terms get a bounded recency-multiplied
-- lift. Flip in psql to toggle instantly with no redeploy. Seeded OFF so the
-- feature ships dark; flip on for NL after verifying the table populates.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('trending_boost_enabled', false)
ON CONFLICT (feature) DO NOTHING;
