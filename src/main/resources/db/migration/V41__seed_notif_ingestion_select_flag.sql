-- Switch for unifying push-notification top-story selection with marketing.
-- When OFF (default), notifications use the local TopStorySelector (heuristic
-- tier ladder, no embedding clustering) exactly as before. When ON, the
-- per-language dispatch calls ingestion-api's POST /api/internal/top-stories/
-- select — the SAME selector marketing-api uses (server-side window + Milvus
-- embedding-cluster Tier 1a + heuristic tiers) — then maps each selected URL
-- back to the snelnieuws article for the deep-link. Any failure (flag off,
-- ingestion down, no URL match) falls back to the local selector, so
-- notifications never break. Seeded OFF — ships dark; flip in psql to toggle.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('notif_ingestion_select_enabled', false)
ON CONFLICT (feature) DO NOTHING;
