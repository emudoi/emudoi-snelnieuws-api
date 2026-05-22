-- Server-side storage for one custom semantic-search query per
-- user/device. Enables cross-device sync after login and a future
-- personalized-notifications hook.
--
-- One row per client_id (the per-install identifier). Optional
-- user_id binds the row to an authenticated user so logging in on a
-- second device can sync the query via GET /v3/embeddings/query.
--
-- The embedding is stored as REAL[] (1024 floats per row, ~4 KB)
-- because Milvus owns search; postgres only needs to STORE +
-- RETRIEVE. No pgvector extension required.
--
-- query_text is potentially sensitive — operators considering
-- encryption at rest should grep for this comment.
--
-- Migration number: V26 (not V25 as the task file states). V25 is
-- taken by language_support/notifications_clickbait_tasks.txt §5
-- (top_summaries rework + subscription language column). The next
-- free V-number was confirmed via
-- `ls src/main/resources/db/migration | sort -V | tail -1`.
CREATE TABLE user_semantic_queries (
    client_id    UUID         PRIMARY KEY
                              REFERENCES app_clients(client_id) ON DELETE CASCADE,
    user_id      VARCHAR(128) REFERENCES users(id) ON DELETE CASCADE,
    query_text   TEXT         NOT NULL,
    embedding    REAL[]       NOT NULL,
    language     TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Cross-device sync: find by user_id, return the row (if any).
CREATE INDEX idx_user_semantic_queries_user_id
    ON user_semantic_queries (user_id)
    WHERE user_id IS NOT NULL;

-- For the future notifications hook: enumerate all stored queries in
-- time order. Cheap given the table is bounded by active client count.
CREATE INDEX idx_user_semantic_queries_updated_at
    ON user_semantic_queries (updated_at DESC);
