-- Recommender Phase-0: slate / propensity logging.
--
-- The feed is what the *policy actually served* — the ordered, paginated slate
-- the client received. Client-reported `impression` events are lossy (deep-link
-- and search opens never emit one; 10/34 historical opens had no impression),
-- so they can't be the ground truth of "what was shown". We log the served
-- slate here, server-side, at the point the feed response is built.
--
-- One row per (serve, article): the public id exactly as served (matches the
-- article_id the apps later report in user_events, so reward joins are direct),
-- its position in the slate, and the policy `propensity` of showing it.
--
-- propensity is 1.0 today because the serving policy (recency · trending) is
-- deterministic. Honest *unbiased* off-policy evaluation (IPS/DR) additionally
-- requires controlled randomisation in the serving policy so propensity < 1 can
-- be logged — that is a separate product decision, deliberately NOT made here.
-- Even deterministic, this log enables replay/warm-start and direct-method /
-- doubly-robust evaluation, which the per-article client impressions cannot.
CREATE TABLE IF NOT EXISTS feed_serves (
    id          BIGSERIAL PRIMARY KEY,
    client_id   UUID NOT NULL,
    article_id  TEXT NOT NULL,                 -- public id as served ("123" / "e123")
    position    INT  NOT NULL,                 -- 0-based slot in the served slate
    list_name   TEXT,                          -- feed context (categories), nullable
    country     TEXT,
    language    TEXT,
    propensity  REAL NOT NULL DEFAULT 1.0,     -- P(shown) under the serving policy
    served_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feed_serves_client_served  ON feed_serves(client_id, served_at DESC);
CREATE INDEX IF NOT EXISTS idx_feed_serves_article_served ON feed_serves(article_id, served_at DESC);
