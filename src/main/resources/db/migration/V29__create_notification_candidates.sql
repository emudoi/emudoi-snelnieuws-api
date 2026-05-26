-- ====================================================================
-- notification_candidates: per-language ranked candidate pool used by
-- the dispatch fallback flow when the 3-tier top-story selector finds
-- no viable story in the current window.
--
-- Lifecycle:
--   1. Each successful dispatch tick (per language) inserts up to N
--      candidates (typically 4) ranked 1..N.
--   2. Rank-1 is dispatched immediately and its row is marked
--      consumed_at = NOW().
--   3. On a later tick where fresh selection yields nothing for a
--      language, the dispatch picks the highest-ranked unconsumed,
--      non-expired row for that language, sends it, marks consumed.
--   4. Lazy cleanup on insert deletes rows past expires_at (Option C —
--      no scheduled job). TTL is 12 h (set by the inserter).
--
-- Read by: NotificationCandidateRepository.findPickable / markConsumed
-- Written by: NotificationCandidateRepository.insertBatch
--
-- Tied to feature flag `notifications_fallback_pool_enabled` — when the
-- flag is off, this table is never touched and the dispatch path runs
-- exactly the pre-V29 logic.
-- ====================================================================

CREATE TABLE IF NOT EXISTS notification_candidates (
    id                          BIGSERIAL   PRIMARY KEY,
    run_id                      UUID        NOT NULL,
    language                    TEXT        NOT NULL,
    rank                        SMALLINT    NOT NULL,
    representative_article_id   BIGINT      NOT NULL,
    representative_url          TEXT        NOT NULL,
    selection_tier              SMALLINT    NOT NULL,
    score                       INTEGER     NOT NULL DEFAULT 0,
    selection_metadata          JSONB,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at                  TIMESTAMPTZ NOT NULL,
    consumed_at                 TIMESTAMPTZ,
    CONSTRAINT notification_candidates_rank_positive
        CHECK (rank > 0),
    CONSTRAINT notification_candidates_language_check
        CHECK (language IN ('de','fr','it','en','es','pl','nl')),
    -- One (run, language, rank) triple per fire — guards against an
    -- accidental double-insert from a retried request.
    CONSTRAINT notification_candidates_run_lang_rank_uniq
        UNIQUE (run_id, language, rank)
);

-- Hot path: "give me the highest-ranked unconsumed, non-expired
-- candidate for this language." Partial index keeps it small — only
-- pickable rows are indexed. Order is rank ASC (lower rank = better),
-- then created_at DESC (prefer fresher batches over older leftovers).
CREATE INDEX idx_notif_candidates_pickable
    ON notification_candidates (language, rank ASC, created_at DESC)
    WHERE consumed_at IS NULL;

-- Lazy cleanup: DELETE WHERE expires_at < NOW() runs on every insert.
CREATE INDEX idx_notif_candidates_expires
    ON notification_candidates (expires_at);

-- Dedup: "was this representative_article_id already sent in the last
-- 24 h?" — used at insert-time to skip candidates we just sent for
-- another language (or earlier today for the same language).
CREATE INDEX idx_notif_candidates_consumed_article
    ON notification_candidates (representative_article_id, consumed_at)
    WHERE consumed_at IS NOT NULL;

-- Feature flag (default off — fallback pool stays dormant until
-- explicitly enabled). The repo treats unknown names as false, so this
-- INSERT is also defence-in-depth.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('notifications_fallback_pool_enabled', FALSE)
ON CONFLICT (feature) DO NOTHING;
