-- ====================================================================
-- top_summaries: rework the unused V20 skeleton into a per-language
-- notification payload table with dispatch-once semantics.
-- (notifications_clickbait_tasks.txt §5)
-- ====================================================================
--
-- Migration number: V25 (not V24 as the task file states). V24 is taken
-- by language_support/backend_tasks.txt §5 (articles.language). The
-- next free V-number was confirmed via
-- `ls src/main/resources/db/migration | sort -V | tail -1`.
--
-- Sanity verified pre-migration: SELECT COUNT(*) FROM top_summaries = 0,
-- so dropping notification_message destroys no data.

ALTER TABLE top_summaries DROP COLUMN notification_message;

-- Per-language clickbait. Shape:
--   {"en": "Trump signs tariff bill — 45 new articles", ...}
-- One inner key per language in Languages.codes (de/fr/it/en/es/pl/nl).
ALTER TABLE top_summaries
    ADD COLUMN notification_messages JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Idempotency: NULL = fresh, non-NULL = already sent. Watcher picks the
-- most recent NULL row per fire.
ALTER TABLE top_summaries ADD COLUMN dispatched_at TIMESTAMPTZ;

-- Audit trail for the 3-tier selection rule:
--   1 = tier 1 (semantic clustering via Milvus embeddings)
--   2 = tier 2 (single publisher repeating the same news)
--   3 = tier 3 (fallback to politics/business)
ALTER TABLE top_summaries ADD COLUMN selection_tier SMALLINT NOT NULL DEFAULT 3;

-- Free-form audit blob populated by snelmind:
--   {"publisher_count": 4, "publishers": ["nu.nl",…], "category": "politics", ...}
ALTER TABLE top_summaries ADD COLUMN selection_metadata JSONB;

-- The watcher's hot path: "give me the most recent undispatched row."
-- Partial index keeps it tiny — only indexes NULL dispatched_at rows.
CREATE INDEX idx_top_summaries_undispatched
    ON top_summaries (created_at DESC)
    WHERE dispatched_at IS NULL;

-- ====================================================================
-- subscription language preference (iOS + Android). Default 'en' so
-- existing rows + older clients that haven't shipped the new field
-- keep working. CHECK constraint hard-codes the supported set; if the
-- product adds another language later, follow up with another
-- migration AND keep Languages.codes / CategoryNames synced.
-- ====================================================================

ALTER TABLE notification_subscriptions
    ADD COLUMN notification_language TEXT NOT NULL DEFAULT 'en';

ALTER TABLE notification_subscriptions
    ADD CONSTRAINT notification_subscriptions_notification_language_check
        CHECK (notification_language IN ('de','fr','it','en','es','pl','nl'));

ALTER TABLE android_notification_subscriptions
    ADD COLUMN notification_language TEXT NOT NULL DEFAULT 'en';

ALTER TABLE android_notification_subscriptions
    ADD CONSTRAINT android_notification_subscriptions_notification_language_check
        CHECK (notification_language IN ('de','fr','it','en','es','pl','nl'));

-- Dispatch fan-out keys on language. Index for the per-language token
-- query in NotificationSubscriptionRepository.findTokensByLanguageGrouped.
CREATE INDEX idx_notif_sub_lang_env
    ON notification_subscriptions (notification_language, apns_environment);

CREATE INDEX idx_android_notif_sub_lang
    ON android_notification_subscriptions (notification_language);

-- ====================================================================
-- Dispatch audit trail: which top_summary produced this dispatch?
-- (notifications_clickbait_tasks.txt §11)
-- Nullable because legacy (pre-clickbait) rows have no top story; with
-- the no-fallback policy in §10, every NEW row carries one.
-- ====================================================================

ALTER TABLE notification_dispatches
    ADD COLUMN top_summary_id BIGINT REFERENCES top_summaries(id);

ALTER TABLE android_notification_dispatches
    ADD COLUMN top_summary_id BIGINT REFERENCES top_summaries(id);
