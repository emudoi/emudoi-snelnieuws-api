# Personalised Feed Rotation — Execution Plan

> **For the autonomous session executing this:** this plan is designed to be
> picked up by a Claude Code session running with `--dangerously-skip-permissions`.
> Every task has exact commands, exact files, exact verification gates, and an
> explicit stop-condition. Work through the phases in order. After each phase
> commits cleanly with all gates green, move to the next phase. Do not skip
> phases. Do not invent design changes — every decision is already made below.

---

## ABSOLUTE RULES (even with --dangerously-skip-permissions)

1. **Prod DB and flag access are confined to the exact statements in Phase 8
   and Phase 9 of this document.** The user has explicitly authorised the
   autonomous rollout for this feature only. Outside the personalised-feed
   flag and the read-only verification queries listed in Phase 8, no other
   prod DB writes, no other `kubectl exec` into prod pods.
2. **NEVER flip any other feature flag.** Only
   `personalised_feed_enabled`. If you find yourself about to touch
   another flag, you've gone off-script — stop and report.
3. **NEVER modify these files:** anything under
   `src/main/scala/com/snelnieuws/repository/NotificationDispatchRepository.scala`,
   `src/main/scala/com/snelnieuws/repository/AndroidNotificationDispatchRepository.scala`,
   `src/main/scala/com/snelnieuws/service/FcmMessagingService.scala`,
   `src/main/scala/com/snelnieuws/service/ApnsMessagingService.scala`,
   `src/main/scala/com/snelnieuws/api/NewsServlet.scala` (the v1 legacy
   servlet — must stay byte-identical so old App Store builds keep working),
   anything under `dags/`, `k8s/`. The personalised-feed feature only touches
   v2 and shared service code.
4. **NEVER bypass git hooks** (`--no-verify`). If a hook fails, fix the
   underlying issue.
5. **NEVER `git push --force`** on any branch.
6. **NEVER edit mobile-app code.** This entire feature ships server-side only.
   If a task says "verify against mobile shape," do that by reading the mobile
   files, not editing them. The iOS repo is at
   `/Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-ios/` and Android at
   `/Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-android/`.
7. **STOP and ask the user** if any of these happen:
   - Existing test suite is red before you start work.
   - A Flyway migration fails.
   - A test you wrote can't pass without changing untouched files.
   - You'd need to break the response JSON shape contract documented in
     "Mobile contract" below.

---

## Context and goal

The iOS and Android apps fetch up to 100 articles per call from
`/v2/everything`, `/v2/feed`, `/v2/top-headlines`. Both apps fetch only one
page (no pagination). The database currently retains ~800–1000 articles (400–
500/day ingestion × 48h retention). So every user sees roughly the same top
100 newest articles, even though the DB has many more.

We cannot ship a client update quickly enough to fix this. The goal is to
**rotate the 100 articles returned per call on a per-install basis**, so each
refetch gives the user a fresh batch they have not been served before. The
mechanism is: persist a per-`X-Client-Key` set of "already-served article ids"
server-side, and filter the response by it.

Background reading (the existing conversation that produced this plan is the
source of truth; this section is the executive summary):
- Both apps send `X-Client: <platform>/<version>` and `X-Client-Key: <UUID>`
  on every v2 request. The UUID is install-level, stored in iOS Keychain /
  Android encrypted prefs, survives cold starts.
- `NewsServletV2.before()` already validates these on every request and calls
  `appClientRepository.markSeen(uuid)`.
- The clients' local `SeenStore` is bounded by `prune(keep:)` to the current
  backend response — it cannot remember more than the last 100 ids, so the
  server is the only place where long-term per-user history can live.

---

## Mobile contract (DO NOT VIOLATE)

The response JSON shape MUST stay identical to today. Verified against:
- iOS `Article` Codable at
  `/Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-ios/SnelNieuws/data/model/NewsFetch.swift`
- Android `ArticleDto` + `ArticleListResponse` at
  `/Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-android/app/src/main/java/com/emudoi/snelnieuws/data/api/dto/ArticleDto.kt`

Top level: `{ status, totalResults, articles[] }`. Each article object must
contain the fields: `id` (String), `author` (String?), `title` (String),
`description` (String?), `url` (String), `urlToImage` (String?),
`publishedAt` (String), `content` (String?). Extra fields are tolerated by
both decoders (Codable + kotlinx.serialization with `@Serializable`) — so a
`category` field on the article object is fine (it already exists today),
and adding new optional top-level fields would also be fine. But removing or
renaming an existing field would crash the apps.

`articles[]` may be of length 0 to 100. Both apps render an empty array
gracefully via their existing empty-state UI (`HomeView.swift` empty branch
and `HomeViewModel.emptyMessageFor`), but our design avoids ever returning 0
unless the DB itself is essentially empty (see the reset-on-exhaust rule in
Phase 3).

---

## Design (already decided — do not deviate)

- **Storage**: a `JSONB` column `app_clients.last_served_ids` holding an
  ordered array of article ids the client has been served. Capped at 1000 on
  write (oldest trimmed). Default `'[]'::jsonb`. Stale ids referencing
  already-deleted articles are harmless and not actively pruned (cap-on-write
  bounds the cost at ~16KB/client).
- **Pool size on read**: 300 articles. Same SQL as today's `findAll` /
  `findByCategory` / `findByCategories`, just a wider LIMIT.
- **Filter algorithm**: read 300, exclude any id present in
  `last_served_ids`, return the first 100 of the remainder. Append those 100
  ids to `last_served_ids`.
- **Reset-on-exhaust**: if the filter yields 0 articles, clear
  `last_served_ids` and return the unfiltered top 100, populating
  `last_served_ids` with those 100. The client's local `SeenStore.prune` will
  re-align naturally on the next fetch.
- **Concurrency**: read-filter-update happens in a single transaction with
  `SELECT ... FOR UPDATE` on the `app_clients` row, so two parallel requests
  from the same client return disjoint fresh sets.
- **Bypass for free-text search**: `/everything?q=<term>` where `<term>` is
  neither empty/`"news"` nor a known category bypasses the filter entirely.
  Search is intent-driven; hiding "seen" matches confuses users.
- **Feature-flag gated**: a row in `feature_flags` named
  `personalised_feed_enabled`. Default `false`. When `false`, every read
  path is byte-identical to today (verified by test 5.4).
- **DB retention floor**: bump `ArticleCleanupScheduler.MinArticleCount`
  20 → 400 and `articles.cleanup.retention-hours` 48 → 72. Always at least
  400 articles available so the filter has runway.

---

## Pre-flight checklist

Run these before starting any phase. Fail-fast: if any check fails, STOP and
tell the user what's wrong before proceeding.

```bash
cd /Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-api

# 1. Clean tree, on main
git status --porcelain                 # must be empty (or only the local .claude/settings.local.json)
git rev-parse --abbrev-ref HEAD        # must be main, or a feature branch already created for this work

# 2. Docker available (Testcontainers needs it)
docker info >/dev/null 2>&1 && echo "docker ok" || echo "DOCKER MISSING - STOP"

# 3. Baseline tests green (sanity that nothing is already broken)
sbt -batch test
# Expected: all suites pass. If anything is red, STOP and report.

# 4. Create the feature branch
git checkout -b feat/personalised-feed
```

If you need to resume after a session break: `git status` should show your
work so far; the next phase to do is the first one whose acceptance gates
have not been met (re-run the verify commands in each phase to check).

---

## Phase 1 — Schema migrations and retention floor

Goal: schema in place, retention bumped. No behavior change yet (feature flag
defaults to false). One commit per task; one PR for the phase.

### Task 1.1 — Article published_at index

Create file `src/main/resources/db/migration/V17__add_articles_published_at_index.sql`:

```sql
-- Personalised feed pulls a wider pool (LIMIT 300) instead of LIMIT 100. At
-- current table sizes (~1000 rows) the sort is still cheap but the index
-- makes it bounded as the table grows.
CREATE INDEX IF NOT EXISTS idx_articles_published_at
  ON articles (published_at DESC);
```

Verification: `sbt -batch test` — all suites still pass (the migration runs
on every test container start). No code references the index name; just
asserting it doesn't break Flyway.

### Task 1.2 — last_served_ids column on app_clients

Create file `src/main/resources/db/migration/V18__add_app_clients_last_served_ids.sql`:

```sql
-- Per-install rotation history for the personalised feed feature. Capped at
-- 1000 ids on write inside AppClientRepository.appendServedIds. Stale ids
-- referencing deleted articles are harmless (cap-on-write bounds cost) and
-- are not actively pruned.
ALTER TABLE app_clients
  ADD COLUMN IF NOT EXISTS last_served_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
```

Verification: `sbt -batch test` — still green. Existing
`NewsServletV2Spec.gateClientId` registration should continue to work because
the column defaults to `[]`.

### Task 1.3 — Seed the feature flag

Create file `src/main/resources/db/migration/V19__seed_personalised_feed_flag.sql`:

```sql
-- Default off. Phase 8 (human-operated) flips it on in production via a
-- direct SQL UPDATE on the feature_flags table.
INSERT INTO feature_flags (feature, is_enabled)
VALUES ('personalised_feed_enabled', false)
ON CONFLICT (feature) DO NOTHING;
```

Verification: `sbt -batch test` — still green.

### Task 1.4 — Bump article retention floor and window

Edit `src/main/scala/com/snelnieuws/service/ArticleCleanupScheduler.scala`:

```scala
object ArticleCleanupScheduler {
  /** Floor on the table size — cleanup is skipped while the count is below
    * this. Sized to keep the personalised-feed filter (Phase 1–4 of
    * docs/personalised-feed-plan.md) with at least 400 candidates available
    * even if ingestion stalls. The earlier 20-article floor was set when the
    * feed was unfiltered and 20 was enough to render *something*; with
    * per-client filtering we need a wider pool so heavy users do not exhaust
    * their fresh set immediately. */
  val MinArticleCount: Int = 400
}
```

Edit `src/main/resources/application.conf` line 27:

```hocon
articles {
  cleanup {
    enabled          = true
    enabled          = ${?ARTICLE_CLEANUP_ENABLED}
    retention-hours  = 72
    retention-hours  = ${?ARTICLE_CLEANUP_RETENTION_HOURS}
    interval-minutes = 60
    interval-minutes = ${?ARTICLE_CLEANUP_INTERVAL_MINUTES}
  }
}
```

Verification: `sbt -batch test`. Any existing `ArticleCleanupSchedulerSpec`
must be updated to match the new floor — if a test fails because it asserts
the old 20, update its assertion to 400. If no such spec exists, that's fine;
Phase 5 adds coverage.

### Phase 1 commit

```
chore(db): add personalised-feed schema + bump article retention

- V17: index on articles(published_at DESC) for wider-pool reads
- V18: app_clients.last_served_ids JSONB column
- V19: seed personalised_feed_enabled flag (off)
- ArticleCleanupScheduler: floor 20 -> 400, retention 48h -> 72h

No behaviour change. Flag is off; new column unused; floor only kicks in if
ingestion stalls. Full personalised-feed wiring lands in subsequent phases.
```

---

## Phase 2 — Repository layer

Goal: new repo methods, fully unit-tested against the Testcontainer DB. No
service-level wiring yet — these methods are dead code until Phase 3 calls
them.

### Task 2.1 — AppClientRepository.readServedIds

Edit `src/main/scala/com/snelnieuws/repository/AppClientRepository.scala`.
Add imports if needed:

```scala
import io.circe.parser.parse
import io.circe.Json
import doobie.postgres.circe.jsonb.implicits._  // requires doobie-postgres-circe; if not on classpath, see fallback below
```

**Note on doobie-postgres-circe**: check `build.sbt` for the `doobie-postgres-circe`
dependency. If it is NOT present, do NOT add it. Instead read the JSONB column as a
`String` and parse with `io.circe.parser.parse` (already on the classpath via
`circe-parser`). Keeps the build small.

Add methods:

```scala
/** Read the JSONB id list. Returns an empty set if the client is missing
  * or the column is null/empty. */
def readServedIds(clientId: UUID): Either[Throwable, Set[Long]] =
  try
    Right(
      sql"SELECT last_served_ids::text FROM app_clients WHERE client_id = $clientId"
        .query[String]
        .option
        .transact(transactor)
        .unsafeRunSync()
        .flatMap(parse(_).toOption)
        .flatMap(_.asArray)
        .map(_.flatMap(_.asNumber).flatMap(_.toLong).toSet)
        .getOrElse(Set.empty[Long])
    )
  catch {
    case e: Exception =>
      logger.error(s"Failed to read last_served_ids for $clientId: ${e.getMessage}", e)
      Left(e)
  }

/** Append `newIds` to last_served_ids (deduped, ordered newest-last), trim
  * to the most recent `capAt` entries. Single UPDATE statement. The trim
  * keeps the *tail* (most recently appended) — older ids fall off first. */
def appendServedIds(
  clientId: UUID,
  newIds: List[Long],
  capAt: Int = 1000
): Either[Throwable, Int] =
  if (newIds.isEmpty) Right(0)
  else
    try {
      // Pre-serialise to a JSONB array literal. doobie's standard Put[String]
      // + ::jsonb cast in SQL avoids needing doobie-postgres-circe on the
      // classpath.
      val newIdsJson = "[" + newIds.mkString(",") + "]"
      Right(
        sql"""
          UPDATE app_clients
          SET last_served_ids = (
            SELECT COALESCE(
              jsonb_agg(elem ORDER BY ord),
              '[]'::jsonb
            )
            FROM (
              SELECT elem, ord
              FROM (
                SELECT elem,
                       row_number() OVER () AS ord
                FROM (
                  SELECT DISTINCT ON (value) value AS elem
                  FROM (
                    SELECT jsonb_array_elements(last_served_ids) AS value
                    UNION ALL
                    SELECT jsonb_array_elements($newIdsJson::jsonb) AS value
                  ) all_vals
                ) deduped,
                jsonb_array_elements(last_served_ids || $newIdsJson::jsonb) WITH ORDINALITY AS positions(val, position)
                WHERE positions.val = deduped.elem
              ) ranked
              ORDER BY ord DESC
              LIMIT $capAt
            ) trimmed
          )
          WHERE client_id = $clientId
        """.update.run.transact(transactor).unsafeRunSync()
      )
    } catch {
      case e: Exception =>
        logger.error(s"Failed to append served ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }
```

If the inline SQL above proves brittle in code review (the nested CTE is
clever-but-fragile), the equivalent in Scala is fine:

```scala
def appendServedIds(clientId: UUID, newIds: List[Long], capAt: Int = 1000): Either[Throwable, Int] =
  readServedIds(clientId).flatMap { existing =>
    // Order: keep existing (oldest first), then append newIds (deduped).
    val merged = (existing.toList ++ newIds).distinct
    val trimmed = merged.takeRight(capAt)
    val json = "[" + trimmed.mkString(",") + "]"
    try Right(
      sql"UPDATE app_clients SET last_served_ids = $json::jsonb WHERE client_id = $clientId"
        .update.run.transact(transactor).unsafeRunSync()
    ) catch {
      case e: Exception =>
        logger.error(s"Failed to append served ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }
  }
```

The Scala variant is two round-trips instead of one, but is dramatically
simpler. **Use the Scala variant unless you can write the SQL one with
confidence AND it passes all concurrency tests in Phase 5.** Correctness
beats cleverness here.

Add a companion method:

```scala
/** Replace last_served_ids with the given list. Used by the reset-on-exhaust
  * path. */
def setServedIds(clientId: UUID, ids: List[Long]): Either[Throwable, Int] =
  try {
    val json = "[" + ids.mkString(",") + "]"
    Right(
      sql"UPDATE app_clients SET last_served_ids = $json::jsonb WHERE client_id = $clientId"
        .update.run.transact(transactor).unsafeRunSync()
    )
  } catch {
    case e: Exception =>
      logger.error(s"Failed to set served ids for $clientId: ${e.getMessage}", e)
      Left(e)
  }
```

### Task 2.2 — ArticleRepository pool reads

Edit `src/main/scala/com/snelnieuws/repository/ArticleRepository.scala`.
Add **new** methods alongside the existing ones — do NOT modify the existing
`findAll` / `findByCategory` / `findByCategories`:

```scala
/** Wide-pool variant used by the personalised-feed filter. Same query as
  * findAll but with a larger default LIMIT so the post-filter result still
  * has 100 articles available. */
def findAllPool(limit: Int = 300): Either[Throwable, List[ArticleRow]] =
  try Right(
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
  ) catch {
    case e: Exception =>
      logger.error(s"Failed to load article pool: ${e.getMessage}", e)
      Left(e)
  }

def findByCategoryPool(category: String, limit: Int = 300): Either[Throwable, List[ArticleRow]] =
  try Right(
    sql"""
      SELECT id, author, title, description, url, url_to_image,
             to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
      FROM articles
      WHERE LOWER(category) = LOWER($category)
      ORDER BY published_at DESC
      LIMIT $limit
    """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
  ) catch {
    case e: Exception =>
      logger.error(s"Failed to load article pool by category=$category: ${e.getMessage}", e)
      Left(e)
  }

def findByCategoriesPool(categories: List[String], limit: Int = 300): Either[Throwable, List[ArticleRow]] =
  try {
    val lowercased = categories.map(_.toLowerCase)
    Right(
      sql"""
        SELECT id, author, title, description, url, url_to_image,
               to_char(published_at, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'), content, category
        FROM articles
        WHERE LOWER(category) = ANY($lowercased)
        ORDER BY published_at DESC
        LIMIT $limit
      """.query[ArticleRow].to[List].transact(transactor).unsafeRunSync()
    )
  } catch {
    case e: Exception =>
      logger.error(s"Failed to load article pool by categories=${categories.mkString(",")}: ${e.getMessage}", e)
      Left(e)
  }
```

### Phase 2 verification

`sbt -batch test` — still green. Phase 5 will add coverage for these new
methods; for now they exist but are unreferenced.

### Phase 2 commit

```
feat(repo): add personalised-feed repository methods

- AppClientRepository: readServedIds, appendServedIds, setServedIds
- ArticleRepository: findAllPool / findByCategoryPool / findByCategoriesPool

Methods exist but are unreferenced. Existing methods are untouched, so the
legacy read paths stay byte-identical. Service-level wiring lands next.
```

---

## Phase 3 — Service layer

Goal: `ArticleService` orchestrates the filter behind the feature flag.
Servlet wiring still uses the old service methods — that wires up in Phase 4.

### Task 3.1 — Inject FeatureFlagRepository into ArticleService

Edit `src/main/scala/com/snelnieuws/service/ArticleService.scala` constructor:

```scala
class ArticleService(
  repository: ArticleRepository,
  appClientRepository: AppClientRepository,
  featureFlagRepository: FeatureFlagRepository,
  imageCacheService: ImageCacheService,
  imageDownloadWorker: ImageDownloadWorker,
  publicBaseUrl: String
) {
  // ... existing body
}
```

Update `Components.scala` where `ArticleService` is constructed to pass the
two new dependencies. `AppClientRepository` and `FeatureFlagRepository`
already exist as lazy vals in `Components.scala`; just wire them through.

### Task 3.2 — New personalised method variants

Add to `ArticleService`:

```scala
private val FlagName = "personalised_feed_enabled"

/** When the feature flag is off OR clientId is None, returns identical
  * results to the non-clientId overload. Otherwise applies the per-client
  * served-id filter. */
def findAll(limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
  personalisedOrLegacy(clientId, limit,
    legacy = () => findAll(limit),
    pool = () => repository.findAllPool(),
  )

def findByCategory(category: String, limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
  personalisedOrLegacy(clientId, limit,
    legacy = () => findByCategory(category, limit),
    pool = () => repository.findByCategoryPool(category),
  )

def findByCategories(categories: List[String], limit: Int, clientId: Option[UUID]): Either[Throwable, List[Article]] =
  personalisedOrLegacy(clientId, limit,
    legacy = () => findByCategories(categories, limit),
    pool = () => repository.findByCategoriesPool(categories),
  )

private def personalisedOrLegacy(
  clientId: Option[UUID],
  limit: Int,
  legacy: () => Either[Throwable, List[Article]],
  pool: () => Either[Throwable, List[ArticleRow]]
): Either[Throwable, List[Article]] = {
  val flagOn = featureFlagRepository.isEnabled(FlagName).getOrElse(false)
  (flagOn, clientId) match {
    case (true, Some(cid)) => personalisedFetch(cid, limit, pool)
    case _                 => legacy()
  }
}

private def personalisedFetch(
  clientId: UUID,
  limit: Int,
  pool: () => Either[Throwable, List[ArticleRow]]
): Either[Throwable, List[Article]] =
  for {
    rows    <- pool()
    served  <- appClientRepository.readServedIds(clientId)
    result  <- {
      val fresh = rows.filterNot(r => served.contains(r.id)).take(limit)
      if (fresh.nonEmpty) {
        appClientRepository.appendServedIds(clientId, fresh.map(_.id))
          .map(_ => fresh)
      } else {
        // Exhaust path: reset and serve top-`limit` unfiltered. The newly
        // served ids replace the entire history so the next call starts a
        // fresh rotation cycle.
        val reset = rows.take(limit)
        appClientRepository.setServedIds(clientId, reset.map(_.id))
          .map(_ => reset)
      }
    }
  } yield interleaveBySource(result.map(toArticle))
```

**Important**: `toArticle` and `interleaveBySource` are already defined
(they're called from the existing `findAll(limit)` etc.). The personalised
path must use them too so the output passes through the same source-shuffle
and url-rewriting logic. The wrapper above does this correctly.

### Task 3.3 — Keep findEverything and findTopHeadlines bypass for search

The existing `findEverything` decides between firehose, category, and
search. Keep it as-is (it dispatches to `findAll` / `findByCategory` /
`search`). The personalisation lives at the level below — the servlet must
pass `clientId` only when the query is empty/"news"/a known category, and
pass `None` when it's a free-text search. That logic lives in the servlet
(Phase 4), not here.

### Phase 3 verification

`sbt -batch test` — still green. Existing tests use the old method
signatures, which still exist and behave identically.

### Phase 3 commit

```
feat(service): add personalised ArticleService overloads behind feature flag

- New overloads take clientId: Option[UUID]
- Flag off OR clientId empty -> delegates to existing methods (byte-identical)
- Flag on + clientId -> pool fetch, filter by served ids, append fresh ids
- Exhaust path: clear served history and serve top-N unfiltered

Servlet still calls the legacy overloads; no behaviour change in production.
Wiring lands next.
```

---

## Phase 4 — Servlet wiring

Goal: `NewsServletV2` passes the X-Client-Key UUID into the new service
overloads on the three read routes. With the flag still off, behavior is
unchanged in production.

### Task 4.1 — Update read routes

Edit `src/main/scala/com/snelnieuws/api/NewsServletV2.scala`. Three routes:

```scala
get("/everything") {
  val query = params.getOrElse("q", "")
  val limit = params.getOrElse("pageSize", "100").toInt
  // Pass clientId only when the route is firehose or a known category.
  // Free-text search intentionally bypasses personalisation.
  val isCategoryLookup =
    query.isEmpty ||
    query == "news" ||
    Categories.all.contains(query.toLowerCase)
  val cidForCall = if (isCategoryLookup) clientIdFromHeader else None
  articleService.findEverything(query, limit, cidForCall) match {
    case Right(articles) =>
      NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
    case Left(e) =>
      InternalServerError(Map("error" -> s"Failed to load articles: ${e.getMessage}"))
  }
}

get("/feed") {
  val raw = params.getOrElse("categories", "")
  val canonical = Categories.all.toSet
  val parsed = raw.split(',').toList
    .map(_.trim.toLowerCase).filter(_.nonEmpty)
    .filter(canonical.contains).distinct
  if (parsed.isEmpty) {
    BadRequest(Map("error" -> "categories required and must contain at least one canonical category"))
  } else {
    val limit = params.getOrElse("pageSize", "100").toInt
    articleService.findByCategories(parsed, limit, clientIdFromHeader) match {
      case Right(articles) =>
        NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
      case Left(e) =>
        InternalServerError(Map("error" -> s"Failed to load articles: ${e.getMessage}"))
    }
  }
}

get("/top-headlines") {
  val category = params.getOrElse("category", "")
  val limit    = params.getOrElse("pageSize", "100").toInt
  articleService.findTopHeadlines(category, limit, clientIdFromHeader) match {
    case Right(articles) =>
      NewsFetchResponse(status = "ok", totalResults = articles.length, articles = articles)
    case Left(e) =>
      InternalServerError(Map("error" -> s"Failed to load headlines: ${e.getMessage}"))
  }
}
```

The `findEverything` and `findTopHeadlines` methods on `ArticleService`
also need new overloads accepting `clientId: Option[UUID]` that simply
dispatch to `findAll(limit, clientId)` / `findByCategory(category, limit,
clientId)`. Add those alongside the existing overloads — keep the old
no-clientId variants in place.

### Task 4.2 — Structured log line

In `personalisedFetch` (Phase 3 code), emit a single info-level log per
call. Hash the UUID so production logs aren't full of raw install IDs:

```scala
import java.security.MessageDigest

private def hashClient(clientId: UUID): String = {
  val bytes = MessageDigest.getInstance("SHA-256").digest(clientId.toString.getBytes("UTF-8"))
  bytes.take(4).map("%02x".format(_)).mkString
}

// inside personalisedFetch, after computing fresh:
logger.info(
  s"personalised_fetch client=${hashClient(clientId)} pool=${rows.length} " +
  s"served=${served.size} fresh=${fresh.size} reset=${fresh.isEmpty}"
)
```

### Phase 4 verification

`sbt -batch test` — all suites still green. The existing
`NewsServletV2Spec` exercises the routes with the flag off (Phase 1.3
seeded it false), so behavior is unchanged.

### Phase 4 commit

```
feat(api): wire X-Client-Key into v2 read routes for personalised feed

- /everything: pass clientId unless q is a free-text search term
- /feed and /top-headlines: always pass clientId
- New service overloads accept Option[UUID]
- Structured log per personalised fetch (hashed clientId)

Flag remains off; behaviour identical in prod. Tests verify the bypass
path matches today's responses byte-for-byte.
```

---

## Phase 5 — Tests

Goal: comprehensive coverage. Every scenario below must have a passing
test. Use the existing `DatabaseTestSupport` pattern (Testcontainers, real
Postgres). Keep `Test / parallelExecution := false` in `build.sbt` —
don't change it.

### Task 5.1 — AppClientRepositorySpec (new file)

Create `src/test/scala/com/snelnieuws/repository/AppClientRepositorySpec.scala`.

Scenarios:

1. `readServedIds` on a newly registered client returns `Set.empty`.
2. `readServedIds` on an unknown client_id returns `Set.empty` (the column
   defaults to `[]`; an unknown id has no row, which the method must
   tolerate).
3. `appendServedIds` with `Nil` is a no-op and does not error.
4. `appendServedIds` adds the ids, preserves insertion order in the JSONB,
   and dedupes when called twice with the same ids.
5. `appendServedIds` trims to `capAt` (test with capAt=5, append 7 fresh
   ids, assert final array length == 5 and contains the 5 most recent).
6. `setServedIds` replaces the column wholesale (use it to set 3 ids, then
   call again with 2 different ids, assert only the latter 2 remain).
7. **Concurrency**: spawn two `Future`s that each call
   `appendServedIds(cid, List(i))` for i in two disjoint ranges (e.g. 1..50
   and 51..100). Use `Await.result(Future.sequence(...))`. After both
   complete, `readServedIds` must contain all 100 ids — no lost updates.
   This validates that the two-round-trip Scala impl is safe enough OR
   forces you to wrap it in a serializable transaction.

If the concurrency test fails with the simple two-round-trip
implementation, wrap the read+update in a single doobie `ConnectionIO` with
`FOR UPDATE`:

```scala
def appendServedIds(clientId: UUID, newIds: List[Long], capAt: Int = 1000): Either[Throwable, Int] =
  if (newIds.isEmpty) Right(0)
  else
    try {
      val program = for {
        existingJsonOpt <- sql"SELECT last_served_ids::text FROM app_clients WHERE client_id = $clientId FOR UPDATE"
          .query[String].option
        existing = existingJsonOpt
          .flatMap(parse(_).toOption)
          .flatMap(_.asArray)
          .map(_.flatMap(_.asNumber).flatMap(_.toLong).toList)
          .getOrElse(List.empty[Long])
        merged = (existing ++ newIds).distinct
        trimmed = merged.takeRight(capAt)
        json = "[" + trimmed.mkString(",") + "]"
        rows <- sql"UPDATE app_clients SET last_served_ids = $json::jsonb WHERE client_id = $clientId"
          .update.run
      } yield rows
      Right(program.transact(transactor).unsafeRunSync())
    } catch {
      case e: Exception =>
        logger.error(s"Failed to append served ids for $clientId: ${e.getMessage}", e)
        Left(e)
    }
```

### Task 5.2 — ArticleRepositorySpec additions

Add to existing `ArticleRepositorySpec` (or create one if it doesn't exist
yet — check before adding):

1. `findAllPool(300)` returns up to 300 rows ordered by published_at DESC.
2. `findByCategoryPool` filters and orders correctly.
3. `findByCategoriesPool` accepts a list and unions the matches.
4. `findAllPool(limit=10)` honors the explicit limit override.

### Task 5.3 — ArticleServiceSpec (new file)

Create `src/test/scala/com/snelnieuws/service/ArticleServiceSpec.scala`.

Bootstrap: register a real client via `appClientRepository.upsertOnRegister`,
seed N articles via `articleRepository.create` or direct SQL inserts. Then
test:

1. **Flag off, no clientId** → calls `findAll(100, None)`, returns top
   100 by date, served column on a separately-checked client remains `[]`.
2. **Flag off, with clientId** → same as above (clientId is ignored when
   flag is off). Served column unchanged.
3. **Flag on, no clientId** → falls through to legacy path. Served column
   unchanged.
4. **Flag on, with clientId, served empty** → returns 100 fresh, served
   column now contains those 100 ids in order.
5. **Flag on, with clientId, served covers half the pool** →
   pre-populate served with ids 1..150 of the 300-article pool, call
   `findAll(100, Some(cid))`, assert all returned ids are > 150 (disjoint
   from served) and the served column now contains 100 new ids appended.
6. **Flag on, with clientId, served covers entire pool (reset cycle)** →
   pre-populate served with the ids of ALL articles in the pool, call,
   assert the response is the top 100 (NOT empty), and the served column
   has been replaced with exactly those 100 ids (the reset replaces, does
   not append).
7. **Flag on, two concurrent calls from the same client** → use two
   `Future`s, assert the union of their returned ids has the expected
   cardinality (200 if pool > 200, otherwise pool size; verify no overlap
   between the two response sets means they raced cleanly).
8. **`findByCategory` personalised path** — analogous to 4–6 but for one
   category. Ensures the per-category pool reads work end-to-end.
9. **`findByCategories` personalised path** — analogous to 4 with a
   multi-category list.

### Task 5.4 — NewsServletV2Spec end-to-end additions

Append to `src/test/scala/com/snelnieuws/api/NewsServletV2Spec.scala`. Use
the existing `gateClientId` and `gatedHeaders` helpers. Test scenarios
listed below MUST be added; do not remove any existing scenario.

To toggle the flag in a test, use the `FeatureFlagRepository` accessible
via `components.featureFlagRepository`. Flip it before the test body and
reset it in an `afterEach` or at the end of each test.

1. **Flag-off baseline (regression guard)**: with flag off, two
   back-to-back `GET /v2/everything` calls from the same client return
   identical article id sets in identical order. This is the "byte-for-byte
   unchanged" guarantee.
2. **Flag-on firehose rotation**: seed 250 articles. Flip flag on. Two
   back-to-back `GET /v2/everything` calls return disjoint id sets of 100
   each.
3. **Flag-on /feed rotation**: seed 250 articles spread across 3
   categories. `GET /v2/feed?categories=politics,economy` twice returns
   disjoint id sets, both within those categories.
4. **Flag-on /top-headlines rotation**: same pattern as 3, single category.
5. **Search bypass**: `GET /v2/everything?q=trump` (a term NOT in
   `Categories.all`) called twice returns identical id sets, even with flag
   on. Filter does not apply to search.
6. **Category query (not search) is personalised**: `GET
   /v2/everything?q=politics` (a term IN `Categories.all`) called twice
   returns disjoint id sets.
7. **Empty category list rejected with 400**: `GET /v2/feed?categories=` →
   400. (Pre-existing behavior; assert it didn't regress.)
8. **Cross-client isolation**: register two distinct clients. Both call
   `GET /v2/everything` once. Both get the same top 100 (no leakage of one
   client's served-state into the other). Then both call again — both get
   their own disjoint next-100.
9. **Reset cycle**: seed only 50 articles (less than the pool limit of
   300). Flip flag on. Call `GET /v2/everything` 3 times in a row from the
   same client. Each call returns 50 articles (the entire pool), and the
   `last_served_ids` column for the client toggles between empty and full
   on each cycle — no empty responses.
10. **Gate still works with flag on**: a request missing `X-Client-Key`
    still returns 401 even with the flag on (the gate is unrelated).
11. **markSeen still fires**: after a flag-on `GET /v2/everything`,
    `app_clients.last_seen_at` for the client has been bumped (regression
    guard — the personalised path must not skip the existing markSeen).

### Task 5.5 — Mobile contract shape test (new file)

Create `src/test/scala/com/snelnieuws/api/NewsResponseShapeSpec.scala`. This
asserts the JSON shape against the fields the mobile apps decode. It does
NOT compile any mobile code; it inlines the field names below as the
source of truth (copy-paste from the mobile files listed in "Mobile
contract" above).

Required top-level fields: `status`, `totalResults`, `articles`.
Required per-article fields: `id`, `author`, `title`, `description`,
`url`, `urlToImage`, `publishedAt`, `content`.

The test bootstraps a client, seeds 1 article with all fields populated,
calls `GET /v2/everything`, parses the JSON, and asserts every required
field key is present in the response (use `org.json4s` which is already on
classpath; `(json \ "articles")(0) \ "id"` etc.). Run this with flag both
off and on, since the personalised path must preserve the shape.

If you find any required field missing in the response, do NOT change the
field set to "fix" the test — STOP and ask the user, because that's a
genuine regression.

### Task 5.6 — ArticleCleanupSchedulerSpec updates

If a spec file exists for `ArticleCleanupScheduler`, update its assertions
to the new floor of 400 (was 20). If no spec exists, create one:

`src/test/scala/com/snelnieuws/service/ArticleCleanupSchedulerSpec.scala`.

Scenarios:

1. **Floor protection**: seed 300 articles (all with `published_at`
   72h+ago). Run the cleanup tick. Assert nothing is deleted (300 < 400
   floor). Re-count: still 300.
2. **Normal cleanup**: seed 600 articles, 200 of them older than 72h. Run
   the tick. Assert 200 deleted (the old ones), 400 remain.
3. **Window respected**: seed 500 articles all younger than 72h. Run the
   tick. Nothing deleted (none past cutoff).

### Phase 5 verification

`sbt -batch test` — every test in scope passes. Total runtime should be
under ~3 minutes (Testcontainers startup dominates). If a test is flaky,
fix it before moving on; do not mark a phase done with a flaky test.

### Phase 5 commit

```
test: personalised-feed coverage — unit + servlet end-to-end

- AppClientRepositorySpec: CRUD on last_served_ids, concurrency
- ArticleRepositorySpec: pool reads
- ArticleServiceSpec: filter, exhaust/reset, search bypass
- NewsServletV2Spec: rotation, search bypass, cross-client isolation,
  reset cycle, gate compatibility, markSeen regression
- NewsResponseShapeSpec: mobile contract (Codable + kotlinx.serialization
  field shape) preserved with flag on and off
- ArticleCleanupSchedulerSpec: floor and window updated for 400/72h

All tests use Testcontainers (postgres:15-alpine), serial execution.
```

---

## Phase 6 — Local end-to-end smoke

Goal: prove the whole stack works against a real running app, not just
unit tests. Performed by the autonomous session before opening the PR.

### Task 6.1 — Start the app locally against a fresh Postgres

```bash
# In one terminal: start Postgres
docker run --rm -d --name snelnieuws-pf-pg \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=snelnieuws \
  -p 5433:5432 \
  postgres:15-alpine

# Wait for it to be ready
until docker exec snelnieuws-pf-pg pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
echo "postgres ready"

# Start the app pointed at it (port 5433 to avoid conflict with any system PG)
DB_HOST=localhost DB_PORT=5433 DB_NAME=snelnieuws \
DB_USER=postgres DB_PASSWORD=postgres \
ARTICLE_CLEANUP_ENABLED=false \
KAFKA_SUMMARIZED_IMPORT_ENABLED=false \
SERVER_PORT=8080 \
sbt -batch "run" &
APP_PID=$!

# Wait for the app
until curl -fsS http://localhost:8080/health >/dev/null 2>&1; do sleep 2; done
echo "app ready"
```

If the app's `run` command doesn't honor those env overrides or the health
route is different, stop and use `sbt assembly && java -jar
target/scala-2.13/emudoi-snelnieuws-api.jar` instead.

### Task 6.2 — Seed and exercise

Use the test API key path the existing servlet spec uses, or whatever
local-auth bypass exists. If the gate blocks all POSTs, register a real
client first:

```bash
CLIENT_ID=$(uuidgen | tr 'A-Z' 'a-z')

# Register
curl -fsS -X POST http://localhost:8080/v2/clients/register \
  -H 'Content-Type: application/json' \
  -H 'X-Client: ios/1.4.0' \
  -d "{\"clientId\":\"$CLIENT_ID\",\"bundleId\":\"com.emudoi.snelnieuws\",\"osVersion\":\"iOS 18.0\"}"

# Seed 350 articles via direct SQL (POST /v2/articles is gated and slow for 350 rows)
docker exec -i snelnieuws-pf-pg psql -U postgres snelnieuws <<'SQL'
INSERT INTO articles (author, title, description, url, url_to_image, content, category, published_at)
SELECT
  'Author ' || i,
  'Title ' || i,
  'Desc ' || i,
  'https://example.test/' || i,
  '/v2/images/fallback.png',
  'Content ' || i,
  (ARRAY['politics','economy','sport','technology'])[(i % 4) + 1],
  NOW() - (i || ' minutes')::interval
FROM generate_series(1, 350) AS i;
SQL

# Helper
fetch() {
  curl -fsS http://localhost:8080/v2/everything \
    -H 'X-Client: ios/1.4.0' \
    -H "X-Client-Key: $CLIENT_ID" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(len(d["articles"]), [a["id"] for a in d["articles"][:5]])'
}

# 1. Flag off: two calls return identical first-5 ids
echo "=== flag OFF ==="
docker exec snelnieuws-pf-pg psql -U postgres snelnieuws -c "UPDATE feature_flags SET is_enabled = false WHERE feature = 'personalised_feed_enabled';"
fetch
fetch

# 2. Flag on: two calls return different first-5 ids
echo "=== flag ON ==="
docker exec snelnieuws-pf-pg psql -U postgres snelnieuws -c "UPDATE feature_flags SET is_enabled = true WHERE feature = 'personalised_feed_enabled';"
fetch
fetch
fetch

# 3. Inspect served column growth
docker exec snelnieuws-pf-pg psql -U postgres snelnieuws -c \
  "SELECT client_id, jsonb_array_length(last_served_ids) AS served FROM app_clients WHERE client_id = '$CLIENT_ID';"
```

Expected: in step 1, the two `fetch` calls show identical id lists. In
step 2, the three `fetch` calls show disjoint id lists. In step 3, the
`served` column should be ~300 (100 from each of three fetches).

### Task 6.3 — Tear down

```bash
kill $APP_PID 2>/dev/null
docker stop snelnieuws-pf-pg
```

### Phase 6 verification

If smoke passes, you're cleared to open the PR. If it fails, the failure
mode tells you exactly which phase to revisit:
- Same ids twice with flag on → service or repo bug (Phase 2/3).
- 401/403 errors → gate broken (Phase 4 over-aggressive).
- 500s → check logs; likely SQL syntax in `appendServedIds`.

No commit for this phase (it's verification only).

---

## Phase 7 — Open PR and auto-merge

```bash
git push -u origin feat/personalised-feed

gh pr create --title "Personalised feed rotation (server-side, flagged off until rollout)" --body "$(cat <<'BODY'
## Summary
- Per-\`X-Client-Key\` article rotation: each install gets a fresh batch of up to 100 on each call.
- Mechanism: \`app_clients.last_served_ids\` JSONB tracks served-article history; filter excludes them from a wider 300-row pool; reset cycle when exhausted.
- DB retention floor raised to 400 articles and window to 72h so the pool always has runway.
- Feature-flagged via \`personalised_feed_enabled\` in \`feature_flags\`. Default OFF — zero behaviour change at deploy time.
- Mobile apps unchanged. Response JSON shape preserved (regression-tested in \`NewsResponseShapeSpec\`).

## Test plan
- [x] \`sbt test\` — all suites green, including new specs for repository, service, servlet, and shape contract
- [x] Local end-to-end smoke per \`docs/personalised-feed-plan.md\` Phase 6: same client gets disjoint id sets across calls with flag on, identical id sets with flag off
- [x] Concurrency test in \`AppClientRepositorySpec\` shows no lost updates when two requests race on the same client_id
- [x] Reset cycle test in \`NewsServletV2Spec\` proves no empty response when DB pool is small

## Rollout
Automated by the same session that opened this PR, per Phase 8 of \`docs/personalised-feed-plan.md\`:
1. Wait for required CI checks, auto-merge.
2. Wait for ArgoCD to roll the new image.
3. Smoke live with flag OFF (regression guard).
4. Flip flag via \`kubectl exec\` + psql.
5. Smoke live with flag ON (rotation confirmed).
6. Monitor logs for 5 min. Auto-rollback (flip flag back) on error spike.
7. Report final status to user.

## Rollback
\`UPDATE feature_flags SET is_enabled = false WHERE feature = 'personalised_feed_enabled';\` — instant. No code revert needed.

## What is NOT in this PR
- Per-category served-id pools. Today one shared pool per client across all routes — sufficient for v1.
- Active pruning of stale ids referring to deleted articles. Cap-on-write at 1000 bounds the cost.
- Metrics counter for reset cycles. Log line gives us the signal for now.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
BODY
)"

# Capture the PR number for later use
PR_NUMBER=$(gh pr view --json number -q .number)
echo "PR_NUMBER=$PR_NUMBER"

# Enable auto-merge with squash. This waits for required status checks and
# then merges. If --auto isn't configured for the repo (no branch protection),
# poll the checks manually and call `gh pr merge --squash --delete-branch`
# yourself.
gh pr merge --squash --auto --delete-branch || {
  echo "auto-merge unavailable — polling checks manually"
  # Poll up to 60 minutes for all checks to succeed
  for i in $(seq 1 60); do
    state=$(gh pr view "$PR_NUMBER" --json statusCheckRollup -q '[.statusCheckRollup[].conclusion] | unique | join(",")')
    echo "checks: $state"
    case "$state" in
      "SUCCESS"|"") gh pr merge "$PR_NUMBER" --squash --delete-branch && break ;;
      *FAILURE*|*CANCELLED*|*TIMED_OUT*)
        echo "CHECK FAILED — stopping rollout. Investigate the failing check, fix, push again."
        exit 1 ;;
      *) sleep 60 ;;
    esac
  done
}
```

After merge, proceed to Phase 8 immediately. Do not stop here.

---

## Phase 8 — Production rollout (AUTOMATED)

The user has authorised the autonomous session to drive this phase end-to-end
for **this feature only** (per ABSOLUTE RULE 1). Stay strictly within the
statements below — no other prod queries, no other flag flips.

**Prerequisites the agent must verify before starting Phase 8:**

```bash
# 1. kubectl context points at the prod cluster
kubectl config current-context
# Expected: a context name matching the prod cluster the user runs ArgoCD
# against. If it returns something that looks like a dev/staging context,
# STOP — ask the user to switch kubectl context, then resume.

# 2. The merged commit is on main
git fetch origin main
git log origin/main --oneline -5
# Expected: top commit is the squash of feat/personalised-feed

# 3. Identify the API namespace and deployment name from the k8s manifests
NAMESPACE=$(grep -m1 'namespace:' /Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-api/k8s/deployment.yaml | awk '{print $2}')
DEPLOY=$(grep -m1 -A1 '^kind: Deployment' /Users/pranjutgogoi/workspace/emudoi/emudoi-snelnieuws-api/k8s/deployment.yaml | grep 'name:' | awk '{print $2}')
echo "NAMESPACE=$NAMESPACE DEPLOY=$DEPLOY"
# If either is empty, fall back: `kubectl get deploy -A | grep snelnieuws-api`
# and pick the obvious one.
```

### Task 8.1 — Wait for ArgoCD to roll the new image

The merged commit triggers a CI image build → argo image-updater bumps the
SHA in `k8s/.argocd-source-emudoi-snelnieuws-api.yaml` → ArgoCD syncs to
the cluster. Typical: 3–10 minutes.

```bash
MERGE_SHA=$(git rev-parse origin/main)
echo "watching for deploy of commit $MERGE_SHA"

# Wait for the rollout to settle on a new ReplicaSet, with a generous
# 30-minute ceiling.
timeout 1800 kubectl rollout status -n "$NAMESPACE" deploy/"$DEPLOY" --watch
# Then assert the running pods are healthy:
kubectl get pods -n "$NAMESPACE" -l app="$DEPLOY" -o wide
# All pods Status=Running, Ready=1/1.
```

If the rollout times out, STOP. Tell the user the image-updater is slow or
broken — don't try to force a sync.

### Task 8.2 — Smoke the live API with the flag OFF (regression guard)

```bash
API="https://api.snel.emudoi.com/v2"

# Register a dedicated rollout-smoke client. UUID is deterministic so this
# is idempotent across re-runs.
CLIENT_ID="00000000-7e57-4001-8001-000000000001"  # version 4 UUID, fixed for this rollout test

curl -fsS -X POST "$API/clients/register" \
  -H 'Content-Type: application/json' \
  -H 'X-Client: ios/1.4.0' \
  -d "{\"clientId\":\"$CLIENT_ID\",\"bundleId\":\"com.emudoi.snelnieuws\",\"osVersion\":\"rollout-smoke\"}"

fetch_ids() {
  curl -fsS "$API/everything" \
    -H 'X-Client: ios/1.4.0' \
    -H "X-Client-Key: $CLIENT_ID" \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(",".join(a["id"] for a in d["articles"]))'
}

A=$(fetch_ids); sleep 2; B=$(fetch_ids)
if [ "$A" = "$B" ]; then
  echo "OK — flag-off regression check passed (identical responses)"
else
  echo "FAIL — flag-off responses differ; deploy may be wrong. STOPPING ROLLOUT."
  exit 1
fi
```

If this fails, STOP. Do NOT proceed to flip the flag. Report the diff to
the user and stop.

### Task 8.3 — Flip the feature flag

The agent uses `kubectl exec` into a running API pod (which already has the
DB connection string mounted via env) and runs psql there. No prod
credentials need to live on the agent's machine.

```bash
POD=$(kubectl get pod -n "$NAMESPACE" -l app="$DEPLOY" -o jsonpath='{.items[0].metadata.name}')
echo "flipping flag via pod $POD"

# The image is JVM-only; it does NOT ship psql. So we run psql from a
# transient pod that has it, using the same DB env from the deployment's
# secretRef. Find the secret name from the deployment manifest:
SECRET=$(kubectl get deploy -n "$NAMESPACE" "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].envFrom[0].secretRef.name}')
# (If envFrom is not the pattern used, fall back: `kubectl describe deploy
# -n $NAMESPACE $DEPLOY | grep -A2 envFrom` and adjust.)

kubectl run psql-flag-flip --rm -i --restart=Never \
  -n "$NAMESPACE" \
  --image=postgres:15-alpine \
  --env-from=secret/"$SECRET" \
  -- psql "$DB_URL" -c \
  "UPDATE feature_flags SET is_enabled = true WHERE feature = 'personalised_feed_enabled' RETURNING feature, is_enabled;"

# Expected output line: `personalised_feed_enabled | t`
```

If the transient-pod approach fails (image pull policy, network policy, RBAC
on `pods/exec`), fall back to:

```bash
# Some clusters block ephemeral pods but allow exec. In that case, install
# psql client into a long-lived debug pod, or invoke through one of the
# existing app pods if it has psql baked in (it does NOT by default — verify
# `kubectl exec <pod> -- which psql` first).
```

If neither works, STOP. Do not invent a fourth path. Report to the user
and let them flip manually.

### Task 8.4 — Smoke the live API with the flag ON (rotation confirmed)

```bash
# Reset this client's served history so the smoke starts from a clean slate.
kubectl run psql-flag-flip --rm -i --restart=Never \
  -n "$NAMESPACE" \
  --image=postgres:15-alpine \
  --env-from=secret/"$SECRET" \
  -- psql "$DB_URL" -c \
  "UPDATE app_clients SET last_served_ids = '[]'::jsonb WHERE client_id = '$CLIENT_ID';"

A=$(fetch_ids); sleep 2; B=$(fetch_ids)
if [ "$A" != "$B" ]; then
  # Also assert disjoint (no overlap)
  overlap=$(python3 -c "a=set('$A'.split(',')); b=set('$B'.split(',')); print(len(a&b))")
  if [ "$overlap" = "0" ]; then
    echo "OK — flag-on rotation confirmed (disjoint id sets)"
  else
    echo "WARN — responses differ but with $overlap overlapping ids. Filter is working partially. Continuing to monitor."
  fi
else
  echo "FAIL — flag-on responses identical. Filter not active. ROLLING BACK."
  ROLLBACK_NEEDED=1
fi
```

### Task 8.5 — Monitor for 5 minutes, auto-rollback on error

```bash
if [ "${ROLLBACK_NEEDED:-0}" = "0" ]; then
  echo "monitoring API logs for 5 minutes..."
  # Stream logs from all pods of the deployment; abort on too many errors.
  ERR_THRESHOLD=20
  ERR_COUNT=0
  kubectl logs -n "$NAMESPACE" -l app="$DEPLOY" --since=10s -f --tail=0 --max-log-requests=10 \
    | timeout 300 awk -v t=$ERR_THRESHOLD '
        /ERROR/ { err++; print }
        /personalised_fetch/ { good++ }
        END {
          printf "errors=%d personalised_fetches=%d\n", err, good
          if (err > t) exit 2
        }
      '
  RC=$?
  if [ "$RC" = "2" ]; then
    echo "ERROR THRESHOLD EXCEEDED — auto-rolling back"
    ROLLBACK_NEEDED=1
  fi
fi
```

### Task 8.6 — Final report or auto-rollback

```bash
if [ "${ROLLBACK_NEEDED:-0}" = "1" ]; then
  echo "executing auto-rollback"
  kubectl run psql-rollback --rm -i --restart=Never \
    -n "$NAMESPACE" \
    --image=postgres:15-alpine \
    --env-from=secret/"$SECRET" \
    -- psql "$DB_URL" -c \
    "UPDATE feature_flags SET is_enabled = false WHERE feature = 'personalised_feed_enabled' RETURNING feature, is_enabled;"
  echo "FAIL: flag flipped back to OFF. See logs above. PR is merged but feature is dormant."
  exit 1
else
  echo "SUCCESS: personalised feed is live."
  echo "Verification:"
  echo "  - Flag flipped ON in prod"
  echo "  - Smoke test confirmed disjoint id sets across calls from the same X-Client-Key"
  echo "  - Error rate stayed below threshold during 5-min monitor window"
  echo ""
  echo "Recommend the user spot-check 10 random app_clients rows over 24h:"
  echo "  SELECT client_id, jsonb_array_length(last_served_ids) FROM app_clients ORDER BY last_seen_at DESC LIMIT 10;"
  echo ""
  echo "If reset=true log lines exceed ~5% of fetches over 24h, bump retention-hours from 72 → 96."
fi
```

After Phase 8 completes (either success or auto-rolled-back), the agent's
job is done. Report the outcome with: PR URL, merge SHA, final flag state,
and a one-line verdict.

---

## Phase 9 — Manual rollback (if user requests post-success)

If the user later asks to roll back (e.g. an app review complaint comes in
hours after deploy):

```bash
NAMESPACE=...  # from Phase 8 prereqs
SECRET=...     # from Phase 8 prereqs
kubectl run psql-rollback --rm -i --restart=Never \
  -n "$NAMESPACE" \
  --image=postgres:15-alpine \
  --env-from=secret/"$SECRET" \
  -- psql "$DB_URL" -c \
  "UPDATE feature_flags SET is_enabled = false WHERE feature = 'personalised_feed_enabled' RETURNING feature, is_enabled;"
```

The `last_served_ids` column stays populated and is ignored on the read
path. No code rollback needed.

If the issue is the migration itself (Phase 1), a code revert is required
since Flyway will not auto-downgrade. To roll back the column:

```sql
ALTER TABLE app_clients DROP COLUMN IF EXISTS last_served_ids;
DELETE FROM feature_flags WHERE feature = 'personalised_feed_enabled';
DROP INDEX IF EXISTS idx_articles_published_at;
-- and then a new V20 migration would be required in the codebase to
-- prevent Flyway from re-running V17–V19 (which would fail-noop or
-- complain about checksum mismatch). Don't do this lightly.
```

---

## Success criteria (one week post-flip)

- p99 latency on `/v2/everything`, `/v2/feed`, `/v2/top-headlines`
  unchanged (±10ms vs pre-rollout baseline).
- `reset=true` log lines on < 1% of requests over 24h.
- No spike in 5xx on `NewsServletV2`.
- App-store reviews / support reports about "same news" diminish.

---

## Glossary of files this plan touches

NEW:
- `src/main/resources/db/migration/V17__add_articles_published_at_index.sql`
- `src/main/resources/db/migration/V18__add_app_clients_last_served_ids.sql`
- `src/main/resources/db/migration/V19__seed_personalised_feed_flag.sql`
- `src/test/scala/com/snelnieuws/repository/AppClientRepositorySpec.scala`
- `src/test/scala/com/snelnieuws/service/ArticleServiceSpec.scala`
- `src/test/scala/com/snelnieuws/service/ArticleCleanupSchedulerSpec.scala` (if not already present)
- `src/test/scala/com/snelnieuws/api/NewsResponseShapeSpec.scala`

EDITED:
- `src/main/scala/com/snelnieuws/service/ArticleCleanupScheduler.scala` (floor 20 → 400)
- `src/main/resources/application.conf` (retention 48 → 72)
- `src/main/scala/com/snelnieuws/repository/AppClientRepository.scala` (new methods)
- `src/main/scala/com/snelnieuws/repository/ArticleRepository.scala` (new pool methods)
- `src/main/scala/com/snelnieuws/service/ArticleService.scala` (new overloads, flag-gated filter)
- `src/main/scala/com/snelnieuws/Components.scala` (wire deps into ArticleService)
- `src/main/scala/com/snelnieuws/api/NewsServletV2.scala` (pass clientId on read routes)
- `src/test/scala/com/snelnieuws/api/NewsServletV2Spec.scala` (new scenarios)
- `src/test/scala/com/snelnieuws/repository/ArticleRepositorySpec.scala` (new pool tests, if file exists)

NEVER TOUCH (see Absolute Rules):
- `src/main/scala/com/snelnieuws/api/NewsServlet.scala` (v1 legacy)
- Anything mobile (iOS / Android repos)
- Anything under `dags/` or `k8s/`
- `NotificationDispatchRepository.scala`, `FcmMessagingService.scala`,
  `ApnsMessagingService.scala`, and their Android variants
