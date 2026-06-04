-- Kill switch for the eulang feed blender. When false, the v3 personalised
-- feed behaves exactly as before (articles table only, published_at order).
-- When true, the personalised pool is blended with eulang_articles and
-- ordered local:other (default 3:1). Flip in psql to disable instantly with
-- no redeploy. Seeded ON so the feature is live on deploy.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('eulang_blend_enabled', true)
ON CONFLICT (feature) DO NOTHING;
