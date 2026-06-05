-- Widen user_events to capture everything the apps already compute (so we
-- never need an app release to start using a field). category + source are
-- promoted to indexed columns (the main affinity dimensions); everything else
-- the app sends (open_source, close_reason, direction, share_surface,
-- is_local, age_hours, from_article_id, search_query, category_slug, …) lands
-- in the flexible `props` JSONB bag, which the backend can mine/promote later
-- with no app change.
ALTER TABLE user_events
  ADD COLUMN IF NOT EXISTS category TEXT,
  ADD COLUMN IF NOT EXISTS source   TEXT,
  ADD COLUMN IF NOT EXISTS props    JSONB;

CREATE INDEX IF NOT EXISTS idx_user_events_category ON user_events(category);
CREATE INDEX IF NOT EXISTS idx_user_events_source   ON user_events(source);
