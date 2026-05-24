-- top_news_videos was created here briefly (PR #18, 2026-05-24) and
-- dropped same-day in PR #19 — table ownership moved to ingestion-api.
-- File is kept verbatim so Flyway can match the applied history row in
-- prod (deleting it triggers "Detected applied migration not resolved
-- locally" on pod boot). V28 drops the table; on a fresh DB the
-- create→drop pair is a no-op.
CREATE TABLE IF NOT EXISTS top_news_videos (
    id              BIGSERIAL PRIMARY KEY,

    text            TEXT NOT NULL,
    anchor          TEXT NOT NULL DEFAULT 'erica',
    variant         TEXT NOT NULL,
    description     TEXT,

    status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','processing','completed','failed')),
    error_message   TEXT,

    video_path      TEXT,
    duration_sec    NUMERIC(6,2),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    saved_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_top_news_videos_pending
    ON top_news_videos(created_at)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_top_news_videos_anchor_variant
    ON top_news_videos(anchor, variant, created_at DESC);
