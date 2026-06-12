-- Per-client served-video-id history for the video reel feed. Mirrors
-- last_served_ids (articles) but kept in its OWN column so video ids and
-- article ids never collide or cross-contaminate each other's rotation.
-- The video-feed read path (VideoFeedService) loads it, filters the
-- marketing catalogue, appends freshly-served ids, and resets the set on
-- exhaustion so the reel loops — identical semantics to the article feed.
ALTER TABLE app_clients
  ADD COLUMN IF NOT EXISTS last_served_video_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
