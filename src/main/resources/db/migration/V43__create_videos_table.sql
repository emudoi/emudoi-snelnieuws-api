-- videos: local mirror of ingestion-api's video_renders, fed by Kafka
-- (topic snelnieuws.videos.rendered) — the video analogue of the articles
-- table, so the reel can be served from here without calling ingestion /
-- marketing-api. `id` is the ingestion video_renders.id (stable across the
-- render lifecycle), used as the upsert key and the shareable id.
--
-- `stream_url` is the public CDN (Bunny) URL — played directly. `url` /
-- `url_to_image` are the SOURCE article's original publisher link + image
-- (from ingestion_articles via real_article_id), so tapping a video opens the
-- same publisher page its source article would, and we get a poster image.
CREATE TABLE IF NOT EXISTS videos (
    id              BIGINT PRIMARY KEY,        -- = ingestion video_renders.id
    title           VARCHAR(500) NOT NULL,
    description     TEXT,
    stream_url      VARCHAR(1000) NOT NULL,    -- public CDN mp4 (played directly)
    url             VARCHAR(1000),             -- source article publisher link (tap-to-article)
    url_to_image    VARCHAR(1000),             -- source article image (poster)
    article_id      BIGINT,                    -- source article id (relating)
    category        VARCHAR(100),
    country         VARCHAR(8),
    language        VARCHAR(8) NOT NULL DEFAULT 'en',
    duration_sec    DOUBLE PRECISION,
    variant         VARCHAR(64),
    published_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Reel ordering / freshness.
CREATE INDEX IF NOT EXISTS idx_videos_published_at ON videos (published_at DESC);
CREATE INDEX IF NOT EXISTS idx_videos_language ON videos (language);
