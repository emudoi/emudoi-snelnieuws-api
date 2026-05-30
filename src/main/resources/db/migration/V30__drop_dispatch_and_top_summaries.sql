-- Notification dispatch is now driven entirely by the per-language
-- notification_candidates pool. The two *_notification_dispatches tables
-- only ever held a global per-frequency `as_of_article_id` watermark
-- (which starved low-volume languages) plus a send audit; both are gone
-- from the code. top_summaries was a write-only audit sink in the
-- dispatch path (the title sent always came from `articles`), read by
-- nothing. Drop all three.
DROP TABLE IF EXISTS notification_dispatches;
DROP TABLE IF EXISTS android_notification_dispatches;
DROP TABLE IF EXISTS top_summaries;
