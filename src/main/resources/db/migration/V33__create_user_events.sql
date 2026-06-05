-- First-party engagement events from the v3 apps: which articles a user sees
-- (impression), opens (open), how long they stay (close + dwell_ms), and the
-- 30s "real read" (read_engaged), plus swipe/share. Written in real time by
-- POST /v3/events; client_id comes from the X-Client-Key gate (not the body).
-- Owned in-house (complements GA4) and intended to power personalization
-- (Phase 2). Direct-to-Postgres for now; can move behind Kafka if impression
-- volume demands.
CREATE TABLE IF NOT EXISTS user_events (
    id           BIGSERIAL PRIMARY KEY,
    client_id    UUID NOT NULL,
    event_type   TEXT NOT NULL,
    -- Public article id as served to the client: numeric ("123") for the
    -- articles table, "e<id>" for eulang_articles. NULL for non-article events.
    article_id   TEXT,
    dwell_ms     BIGINT,
    position     INT,
    list_name    TEXT,
    country      TEXT,
    language     TEXT,
    -- Client-reported event time; created_at is the server receive time.
    event_ts     TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_events_client_created
    ON user_events(client_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_events_article_created
    ON user_events(article_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_events_type_created
    ON user_events(event_type, created_at DESC);
