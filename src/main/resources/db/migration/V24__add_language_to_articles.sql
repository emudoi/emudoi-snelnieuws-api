-- Carry the per-article language end-to-end from the summarize pipeline
-- (`summarized_articles.language` on the ingestion side) through the
-- Kafka payload (V_a in language_support/backend_tasks.txt §4) onto
-- the consumer-side `articles` table for v3 filtering.
--
-- TEXT (not VARCHAR(8)): identical storage on Postgres, but lets BCP-47
-- region tags (e.g. nl-NL) fit later without another migration. NOT NULL
-- with DEFAULT 'en' so the existing INSERT statement in
-- ArticleRepository.upsertByTitle keeps working unchanged until that
-- code is updated in this same PR.
ALTER TABLE articles
    ADD COLUMN language TEXT NOT NULL DEFAULT 'en';

-- Index for v3 reads which always filter by language + sort by
-- published_at DESC. Without this, the planner falls back to the
-- (published_at, id) index and post-filters by language, which works
-- but doesn't scale once the table holds rows for multiple languages.
CREATE INDEX IF NOT EXISTS idx_articles_language_published_at
    ON articles(language, published_at DESC);

-- Belt-and-suspenders. ADD COLUMN ... NOT NULL DEFAULT 'en' on
-- Postgres 11+ already populates every existing row in O(1)
-- (metadata-only, no heap rewrite), but the explicit UPDATE makes the
-- intent obvious to a future migration reviewer and is a no-op when
-- the DEFAULT did its job.
UPDATE articles SET language = 'en'
WHERE language IS NULL OR language = '';
