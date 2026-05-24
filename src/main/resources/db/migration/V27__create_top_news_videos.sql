-- top_news_videos: work queue for the litikai-video-generator pipeline.
-- Schema mirrored verbatim from litikai-video-generator's
-- migrations/001_top_news_videos.sql so both repos can boot from a clean
-- DB. Both sides use CREATE TABLE IF NOT EXISTS so whichever Flyway /
-- bootstrap runs first wins, and the other becomes a no-op.

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
