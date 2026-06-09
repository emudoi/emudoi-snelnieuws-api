-- Kill switch for the LinUCB recommender re-ranking of the v3 feed
-- (recommender Phase-5). When false, the personalised v3 feed serves the
-- recency/trending-ordered pool exactly as before. When true (and the
-- RecommenderClient is wired), the candidate pool is reordered by the
-- emudoi-snelnieuws-recommender service's /rank scores per device, with a hard
-- fallback to the existing order on any error/timeout. Seeded OFF so the
-- feature ships dark; flip on after the recommender has learned from live
-- traffic. Flip in psql to toggle instantly with no redeploy.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('recommender_enabled', false)
ON CONFLICT (feature) DO NOTHING;
