# emudoi-snelnieuws-recommender — Build Hand-off

**Status:** plan / hand-off. Nothing here is implemented yet.
**Author of plan:** investigation session, 2026-06-09.
**Goal:** A standalone **Python** service, `emudoi-snelnieuws-recommender`, that ranks candidate articles per user with a **LinUCB contextual bandit**, learning online from `user_events` delivered over Kafka.

This doc is meant to be executable by another engineer/session without re-doing the investigation. File:line references are point-in-time (2026-06-09) — verify before editing.

---

## 0. Decisions already made (don't re-litigate)

- **Language:** Python service (separate from the Scala API). LinUCB itself doesn't require Python, but a separate service was the explicit choice (clean separation, room to grow into neural bandits).
- **Transport:** Kafka. A new `user.events` topic, produced by the Scala API, consumed by the recommender.
- **Article-reference strategy:** **Option B** — stamp a *feature snapshot* (plus `title`+`url`) onto every article-bearing event at write time. See §2.
- **Bandit granularity:** a **single shared linear model** over user×article *features* (not per-article arms). Articles are ephemeral; their identity is fully carried by their feature vector. See §6.
- **Roll-out:** dark-ship behind a feature flag, same pattern as `trending_boost_enabled` (V37). The recommender augments, then optionally replaces, the existing recency+trending blend.

---

## 1. Current data reality (as of 2026-06-09)

Measured directly against the live `snelnieuws` Postgres (`user_events`):

- **2,018 events / 14 devices / 848 distinct articles touched**, spanning 4 days (Jun 5–9). One device (1,662 events) is almost certainly a QA/dev handset. ~6 devices have non-trivial sessions; **4** ever produced a `read_engaged`.
- **Event-type counts:** `impression` 965, `swipe` 926, `open` 37, `close` 35, `search_submit` 23, `share` 12, `category_select` 11, `read_engaged` 8, `feed_caught_up` 1.
- **Replayable tuples:** 898 distinct `(device, article)` impression pairs; of those **24 → open, 4 → read_engaged, 10 → share**. Replay is *structurally* valid but volume is near-zero — it seeds, it does not train.
- **Catalog linkage:** of 848 touched articles, 350 still in `articles`, 363 in `eulang_articles`, **~135 already purged** by the 72h cleanup.

**Implication:** the blocker is *traffic*, not engineering. Build the pipeline now; meaningful evaluation waits on DAU. Every step below can be built and validated for *shape* against today's rows.

---

## 2. The critical fix — event→article reference (Option B)

**Problem.** An event's `article_id` (e.g. `"57898"`, or `"e9716"` for eulang) is a **snelnieuws-api `BIGSERIAL` assigned fresh on insert**. The Kafka export `SummarizedArticleExport` carries **no id** (`common/.../model/SummarizedArticleExport.scala:10-26`); the consumer dedups by `title` (`ArticleRepository.scala:563-581`, `ON CONFLICT (title)`); ingestion ids are an independent sequence. So once the 72h cleanup deletes the `articles` row, the event's id maps to **nothing** on either side. `props` carries no `title`/`url` either (verified: keys are `article_age_hours`, `article_is_local`, `direction`, `from_article_id`, `open_source`, `close_reason`, …).

**Fix (Option B).** When writing an article-bearing event, stamp onto it both a **durable join key** and a **feature snapshot**, so the row is self-describing and survives the serving-table cleanup:

| Stamped field | Purpose |
|---|---|
| `title` | durable join key into ingestion (`ingestion_articles.title` UNIQUE) |
| `url` | secondary confirmation / content link |
| `category` | feature (already partly logged) |
| `source` | feature (already partly logged) |
| `language` | feature — **currently logged on 1/2018 events; fix this** |
| `country` / `is_local` | feature (`is_local` already in props) |
| `published_at` or `age_hours` | recency feature (`article_age_hours` already in props) |

**Where to change (Scala API — gated repo, get explicit OK before editing):**
- Event input shape: `src/main/scala/com/snelnieuws/model/UserEvent.scala` (`UserEventInput`, ~8-26). Either add typed fields or push into `props` (props is the lower-friction path; it's already a `Map[String,String]`).
- Write path: `EventRepository.insertBatch` (`src/main/scala/com/snelnieuws/repository/EventRepository.scala:45-78`), reached via `POST /v3/events` (`NewsServletV3.scala:356-375`).
- The serving side already has the article in hand when it builds the feed (it's the source of the impression), so the title/url/features are available at log time without an extra lookup.

**Decision — where snapshot is produced:** prefer the **client** already knows category/age/is_local (it renders them) → cheapest is to have the app include `title`+`url` in the event payload it already sends. If you'd rather not trust the client, stamp server-side at the point the feed is assembled. Pick one; document it.

**Historical 135 purged articles:** unrecoverable (no title in old events). Accept the loss.

---

## 3. Kafka topic — `user.events`

Follow the existing `seo.trends.collected` convention (`SeoTrendsConsumer.scala`, `application.conf:206-219`).

- **Topic:** `user.events` (name TBD-confirm), JSON/Circe payload to match existing topics.
- **Producer:** the Scala API event-write path (`EventRepository.insertBatch`). Dual-write PG + Kafka, or a transactional outbox if you want exactly-once. At current volume dual-write is fine.
- **Payload:** the full stamped event from §2 (type, client_id, article_id, title, url, category, source, language, country, position, list_name, dwell_ms, age_hours, props, event_ts).
- **Consumer:** the recommender (`confluent-kafka` or `aiokafka`), group `snelnieuws-recommender`. Updates the model and persists reward/feature rows.
- **Note:** Postgres still persists every event (replay/backfill source). Kafka is the live feed; PG is the system of record.

---

## 4. Data sources & access (for the Python service)

| Source | What | Access |
|---|---|---|
| `snelnieuws` PG — `user_events` | rewards / interactions | read-only role; `psycopg2`/SQLAlchemy. Backfill + system-of-record. |
| `snelnieuws` PG — `articles` / `eulang_articles` | live serving catalog + features | read-only. Recent (<72h) articles. |
| `snelnieuws` PG — `article_trending_scores` | popularity feature | read-only. (V36) |
| `snelnieuws_ingestion` PG — `ingestion_articles`, `eulang_ingestion_articles`, `summarized_articles`, `eulang_summarized_articles` | **full article history** (no cleanup) | read-only role on the ingestion DB. Join by `title`. |
| Milvus `10.0.1.30:19530` | 1024-d `multilingual-e5-large` article vectors, collections `snelmind_articles` + `eulang_articles`, HNSW/cosine | `pymilvus`. Optional dense feature (see §6 — start without it). |

Config all via env (`DB_HOST`/`DB_*`, `INGESTION_DB_*`, `MILVUS_HOST`/`MILVUS_PORT`, `KAFKA_*`). DBs live in k8s ns `postgres` (pod `postgresql-0`); ingestion DB name is `snelnieuws_ingestion`.

**Ingestion join (recover features for a purged article):**
```sql
SELECT ia.id, ia.title, ia.content_link AS url, ia.date,
       sa.category, sa.shared_categories, sa.country, sa.shared_countries, sa.language
FROM ingestion_articles ia
JOIN summarized_articles sa ON sa.source_article_id = ia.id
WHERE ia.title = :title;          -- :title comes from the stamped event (§2)
-- eulang variant: eulang_ingestion_articles + eulang_summarized_articles
```

---

## 5. Data preparation

### 5.1 Reward labeling

Turn raw events into `(client_id, article_id, context, reward)`. Reward shaping:

| Signal (within window after impression) | reward |
|---|---|
| `read_engaged` or `share` | 1.0 |
| `open` only | 0.3 (tune) |
| nothing | 0.0 |

**Labeling SQL (validated against current data — produces 898 rows today):**
```sql
WITH imp AS (
  SELECT client_id, article_id, MIN(created_at) AS imp_ts,
         MAX(category)  AS category,   -- stamped fields (§2) once available
         MAX(source)    AS source,
         MAX(language)  AS language,
         MAX(position)  AS position
  FROM user_events
  WHERE event_type = 'impression' AND article_id IS NOT NULL
  GROUP BY client_id, article_id
),
rwd AS (
  SELECT client_id, article_id,
         MAX(CASE WHEN event_type IN ('read_engaged','share') THEN 1 ELSE 0 END) AS strong,
         MAX(CASE WHEN event_type = 'open'                    THEN 1 ELSE 0 END) AS opened
  FROM user_events
  WHERE article_id IS NOT NULL
    AND event_type IN ('open','read_engaged','share')
  GROUP BY client_id, article_id
)
SELECT imp.client_id, imp.article_id, imp.category, imp.source, imp.language, imp.position,
       imp.imp_ts,
       CASE WHEN COALESCE(rwd.strong,0) = 1 THEN 1.0
            WHEN COALESCE(rwd.opened,0) = 1 THEN 0.3
            ELSE 0.0 END AS reward
FROM imp
LEFT JOIN rwd USING (client_id, article_id);
```
Add a real time-window guard (e.g. reward event within 30 min of `imp_ts`) once volume warrants; at current density the join is effectively per-session already.

### 5.2 Article feature extractor

From `articles`/`eulang_articles` (live) or ingestion (historical), build a per-article vector:
- `category` → one-hot (~10–20 dims)
- `source` → hashed one-hot (feature-hash to a fixed width, e.g. 32)
- `language` → one-hot (~5)
- `country` / `is_local` → one-hot + boolean
- `age_hours` → bucketed or `exp(-k·age)` recency scalar (mirror the feed's `k = ln2/18h`)
- `trending_score` → scalar from `article_trending_scores` (0..1)
- *(optional, later)* reduced embedding — see §6.

### 5.3 User feature extractor

Per `client_id`, aggregate history from `user_events`:
- engagement rate (opens/impressions, reads/impressions)
- category affinity vector (normalized engaged-count per category)
- source affinity vector (hashed)
- recency/activity level (events in last N days)
- preferred language(s)

Persist as a `user_profiles` table refreshed on a schedule (or incrementally from the Kafka stream).

### 5.4 Context vector

`x_{t,a}` = combination of user features and article features — concatenation to start (simple, debuggable); add a small set of user×article interaction terms (e.g. user-category-affinity × article-category indicator) once the base model works. Keep total dimensionality `d` modest (target **d ≈ 50–100**) so the online matrix update stays cheap.

---

## 6. The model — LinUCB (shared linear)

**Formulation (single shared θ, not per-arm):**
- For each candidate article `a` with context `x_a ∈ R^d`:
  - `p_a = θᵀ x_a + α · sqrt(x_aᵀ A⁻¹ x_a)`  (mean + exploration bonus)
  - `θ = A⁻¹ b`
- Rank candidates by `p_a`; serve the top-k.
- **Update** on observed reward `r` for the chosen `x`:
  - `A ← A + x xᵀ`
  - `b ← b + r x`
- Maintain `A⁻¹` incrementally with **Sherman–Morrison** (rank-1 update) to avoid a full inversion per request.

**Why shared, not disjoint/per-article:** articles live <72h and there are thousands of them — per-arm θ never accumulates data. A single θ over *features* generalizes across all articles sharing a category/source/recency profile. This is the only granularity that learns at your volume.

**Hyperparameters:** `α` (exploration) start ~1.0; ridge `λ` → `A₀ = λI`, λ=1. Tune later.

**Embeddings (defer):** the 1024-d Milvus vector would blow up `A` (1024×1024 inverse per request). If you want dense semantic signal, PCA/random-project it to ~16–32 dims first, or skip for v1. **v1 = categorical + affinity features only.**

**State persistence:** store `A`, `b` (and `A⁻¹`) in Postgres or Redis. Tiny (`d×d` floats). Reload on boot; checkpoint periodically.

---

## 7. Serving integration

- **Recommender endpoint:** `POST /rank` → input `{client_id, candidate_article_ids[]}`, output `[{article_id, score}]` ranked. Must fit the feed latency budget (single-digit ms target; it's one matrix-vector per candidate).
- **Scala wiring:** `ArticleService.blendedV3Pool` (`src/main/scala/com/snelnieuws/service/ArticleService.scala:201-266`) currently scores `score = exp(-k·ageH)·(1 + α·trend)`. Add a branch that, when the recommender flag is on, calls `/rank` over the candidate pool and uses its ordering (or blends bandit score with recency).
- **Feature flag:** new `feature_flags` row, e.g. `recommender_enabled`, seeded FALSE — mirror `trending_boost_enabled` (V37, `feature_flags` table V15). Dark-ship.
- **Fallback:** if `/rank` errors/times out or the model is cold, degrade to the existing recency+trending blend. The bandit must never be a hard dependency of the feed.

---

## 8. Evaluation

- **Offline replay / off-policy eval** needs **propensity + slate logging** (the current feed is deterministic and logs neither). Add to the impression path: the policy's score per shown item and the full slate. Without it you can only evaluate via live A/B. *(This is the one logging item beyond §2 worth doing early.)*
- **Online A/B:** flag-gated cohort; measure CTR, engaged-read rate, dwell — all already in `user_events`.
- **Model health metrics:** staleness (time since last update), `α`-bonus magnitude (exploration vs exploitation), per-feature θ sanity.

---

## 9. Task checklist (ordered; ★ = critical path)

**Phase 0 — Scala-side logging/data (gated repo — get OK before editing)**
- [ ] ★ Stamp `title`+`url`+feature snapshot on article-bearing events (§2).
- [ ] ★ Fix `language` logging (currently 1/2018).
- [ ] ★ Ensure every served article logs an `impression` (10/34 opens currently lack one).
- [ ] Add propensity + slate logging to impressions (§8).

**Phase 1 — Kafka**
- [ ] Create `user.events` topic (convention per `seo.trends.collected`).
- [ ] Produce from `EventRepository.insertBatch` (dual-write or outbox).

**Phase 2 — Service scaffold (`emudoi-snelnieuws-recommender`)**
- [ ] ★ Repo skeleton: pyproject/poetry, Dockerfile, `/health`.
- [ ] Python build pipeline → ghcr image; k8s deployment + namespace (Scala `scala-build` won't apply).
- [ ] Config via env (Kafka, both PG DBs, Milvus).
- [ ] ★ Kafka consumer on `user.events`.
- [ ] Read-only PG clients (`snelnieuws` + `snelnieuws_ingestion`); `pymilvus` client.

**Phase 3 — Data prep**
- [ ] ★ Reward labeling job (§5.1 SQL).
- [ ] ★ Article feature extractor (§5.2), with ingestion fallback join.
- [ ] ★ User feature extractor + `user_profiles` table (§5.3).
- [ ] Context-vector assembly (§5.4).
- [ ] Replay/backfill job to warm-start from historical `user_events`.

**Phase 4 — Model**
- [ ] ★ LinUCB shared-linear (§6) with Sherman–Morrison updates.
- [ ] Model-state persistence + checkpoint/reload.
- [ ] Online update from the Kafka stream.

**Phase 5 — Serving**
- [ ] ★ `/rank` endpoint.
- [ ] Wire `blendedV3Pool` to call it behind `recommender_enabled` flag.
- [ ] Fallback to recency+trending on error/cold/timeout.

**Phase 6 — Eval & ops**
- [ ] Offline replay harness (needs Phase-0 propensity logging).
- [ ] Online A/B via flag.
- [ ] Metrics + alerting.

**Do-first cut:** Phase 0 → topic+producer → scaffold+consumer → labeling + feature extractors. Everything else builds on those.

---

## 10. Reference: key code locations (2026-06-09)

- Event model / allowed types: `src/main/scala/com/snelnieuws/model/UserEvent.scala:8-26,34-38`
- Event write: `repository/EventRepository.scala:45-78`; `api/NewsServletV3.scala:356-375`
- `user_events` schema: `db/migration/V33__create_user_events.sql`, `V34__user_events_props.sql`
- Article consumer / insert: `service/SummarizedArticleConsumer.scala:116`; `repository/ArticleRepository.scala:563-581`
- Export payload (no id): ingestion `common/.../model/SummarizedArticleExport.scala:10-26`
- Ingestion tables: ingestion `db/migration/V1__create_tables.sql`, `V20__…`, `V7__…`, `V24__…`; DB `snelnieuws_ingestion`
- Feed blend: `service/ArticleService.scala:201-266`
- Trending: `service/TrendingScoreService.scala`, `service/SeoTrendsConsumer.scala`; `db/migration/V35`, `V36`; topic `seo.trends.collected`
- Feature-flag pattern: `db/migration/V15__create_feature_flags.sql`, `V37` (`trending_boost_enabled`)
- Milvus: ingestion `vectordb/…` — host `10.0.1.30:19530`, collections `snelmind_articles`/`eulang_articles`, dim 1024 (`multilingual-e5-large`)

---

## 11. Open decisions to confirm before coding

1. **Snapshot producer** — client-sent `title`/`url` vs server-side stamp at feed assembly (§2).
2. **Topic name** + serialization (assume `user.events`, JSON/Circe).
3. **Reward shaping** — exact open weight (0.3 placeholder) and window length.
4. **`d`** — final feature set / dimensionality (target 50–100; embeddings deferred).
5. **Model store** — Postgres vs Redis.
6. **Serving semantics** — bandit *replaces* the blend ordering, or *blends* with recency.
