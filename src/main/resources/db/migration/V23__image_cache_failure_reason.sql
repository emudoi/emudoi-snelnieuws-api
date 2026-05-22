-- Capture WHY a download failed, so:
--   (a) the in-process classifier can decide whether to enqueue the URL
--       onto the slow-retry topic (image-retry-slow).
--   (b) operators can debug 'failed' rows without spelunking pod logs.
--
-- reason: a short token from a closed set, picked by the
-- ImageCacheService classifier. Allowed values:
--   'timeout'              - HTTP request timed out
--   'connection_error'     - TCP-level failure (reset, refused, DNS)
--   'http_5xx'             - upstream 5xx
--   'http_4xx'             - upstream 4xx (non-retryable)
--   'oversize'             - exceeded images.max-bytes
--   'unsupported_scheme'   - data:, blob:, ftp:, etc.
--   'signed_token_expired' - looks like a signed URL that expired
--                            (set after 4xx if the URL contains
--                            auth=/token=/signature=)
--   'other'                - anything else
--
-- last_failure_status_code: HTTP status when applicable; NULL for
-- transport-layer failures.
ALTER TABLE image_cache
    ADD COLUMN last_failure_reason TEXT,
    ADD COLUMN last_failure_status_code INT;

-- For the slow consumer's idempotency check (skip if downloaded)
-- and for ops queries grouping failures by reason.
CREATE INDEX IF NOT EXISTS idx_image_cache_status_reason
    ON image_cache (status, last_failure_reason)
    WHERE status = 'failed';
