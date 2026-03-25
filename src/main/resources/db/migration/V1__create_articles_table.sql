CREATE TABLE articles (
    id BIGSERIAL PRIMARY KEY,
    author VARCHAR(255),
    title VARCHAR(500) NOT NULL,
    description TEXT,
    url VARCHAR(1000) NOT NULL,
    url_to_image VARCHAR(1000),
    published_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    content TEXT,
    category VARCHAR(100)
);

CREATE INDEX idx_articles_category ON articles(category);
