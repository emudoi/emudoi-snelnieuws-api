-- Consumer-side store for the eulang (native Dutch-language) track — the
-- mirror of `articles`, fed by the eulang summarized Kafka topic
-- (eulang.articles.summarized). Same cumulative shape as `articles`
-- (V1 + V3 + V17 + V21 + V22 + V24) collapsed into one migration:
-- UNIQUE(title) dedup, language column, per-language index.
CREATE TABLE IF NOT EXISTS eulang_articles (
    id                BIGSERIAL PRIMARY KEY,
    author            VARCHAR(255),
    title             VARCHAR(500) NOT NULL,
    description       TEXT,
    url               VARCHAR(1000) NOT NULL,
    url_to_image      VARCHAR(1000),
    published_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    content           TEXT,
    category          VARCHAR(100),
    shared_categories TEXT[],
    country           TEXT,
    shared_countries  TEXT[],
    language          TEXT NOT NULL DEFAULT 'en',
    CONSTRAINT eulang_articles_title_unique UNIQUE (title)
);

CREATE INDEX IF NOT EXISTS idx_eulang_articles_category
    ON eulang_articles(category);
CREATE INDEX IF NOT EXISTS idx_eulang_articles_published_at
    ON eulang_articles(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_eulang_articles_language_published_at
    ON eulang_articles(language, published_at DESC);
