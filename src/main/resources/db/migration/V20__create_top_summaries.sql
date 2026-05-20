CREATE TABLE IF NOT EXISTS top_summaries (
    id                   BIGSERIAL   PRIMARY KEY,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    top_news             JSONB,
    notification_message TEXT        NOT NULL,
    category_top_news    JSONB
);
