package com.snelnieuws.service

import com.snelnieuws.model.ArticleV3Row
import io.circe.Json
import io.circe.syntax._

import java.time.OffsetDateTime

/** 3-tier top-story selector. Port of the Python pipeline's
  * `top_story.py` algorithm minus the embedding-clustering Tier 1
  * (which required a Milvus round-trip + 1024-dim cosine math). Pure
  * functions over the article list — no IO, no LLM, no GPU.
  *
  * The full Python flow had:
  *   Tier 1a: embedding clusters (semantic similarity)
  *   Tier 1b: heuristic fallback (category + country + 4h-window bucketing)
  *   Tier 2:  single-publisher pick
  *   Tier 3:  latest politics/business
  *
  * Scala port drops Tier 1a — the heuristic Tier 1 catches the same
  * "Trump-Xi summit covered by 4 publishers in the same afternoon"
  * pattern. Embedding tier could be added later by exposing a fetch
  * endpoint on ingestion-api; the structure is intentionally left
  * extensible (the `selectFromWindow` entry point can grow a
  * preceding embedding tier without changing the rest of the
  * pipeline).
  *
  * Caller responsibility: pass the articles AFTER applying the
  * "since last dispatch" watermark filter. The selector trusts the
  * window — it'll happily pick a yesterday article if you pass one.
  */
object TopStorySelector {

  /** Four categories of selection outcome. Embedded into the audit
    * `selection_tier` column so we can later analyze which path was
    * fired most often.
    *
    * Tier4LastResort is emitted ONLY by `selectTopN` when the strict
    * tiers (1+2+3) produce fewer than the requested N candidates and
    * the caller has opted in to the fallback pool. `selectFromWindow`
    * (the legacy single-pick entry point) never emits Tier 4 — it
    * preserves the pre-V29 "skip dispatch when no viable top story"
    * semantics.
    */
  sealed trait Tier { def code: Short }
  case object Tier1Heuristic extends Tier { val code: Short = 1 }
  case object Tier2SinglePublisher extends Tier { val code: Short = 2 }
  case object Tier3PoliticsOrBusiness extends Tier { val code: Short = 3 }
  case object Tier4LastResort extends Tier { val code: Short = 4 }

  /** Output of a successful selection. The caller uses
    *   - `representativeArticleId` to look up the article's title in
    *     every language (multi-language lookup happens at the DB
    *     layer, not here, so the selector stays pure).
    *   - `selectionMetadata` JSON is stored verbatim in
    *     top_summaries.selection_metadata for audit.
    */
  case class Selection(
    representativeArticleId: Long,
    representativeUrl:       String,
    tier:                    Tier,
    windowSize:              Int,
    selectionMetadata:       Json
  )

  /** Entry point. Returns None when the window is empty OR every tier
    * fails to find a viable pick (e.g., a one-article window from a
    * single publisher in a category we don't recognise).
    *
    * The article list MUST already be filtered to a single language
    * (en in the production wiring) so the publisher-counting math
    * isn't muddled by per-language summary rows of the same source.
    */
  def selectFromWindow(articles: List[ArticleV3Row]): Option[Selection] = {
    if (articles.isEmpty) return None
    val windowSize = articles.size
    tier1Heuristic(articles, windowSize)
      .orElse(tier2SinglePublisher(articles, windowSize))
      .orElse(tier3PoliticsOrBusiness(articles, windowSize))
  }

  /** Top-N entry point used by the fallback pool feature (V29). Returns
    * up to `n` distinct (by representativeUrl) selections ordered
    * best-first across tiers:
    *
    *   1. All Tier 1 buckets with ≥ 2 publishers (sorted by publisher
    *      count DESC, then bucket size DESC).
    *   2. All Tier 2 multi-publisher URLs (publisher count DESC), then
    *      one Tier 2 candidate per distinct publisher (article count
    *      DESC for that publisher, picking the publisher's freshest).
    *   3. All Tier 3 politics/business articles (publishedAt DESC).
    *   4. Last-resort: any remaining articles by publishedAt DESC
    *      (Tier 4) — fills the pool to N when strict tiers don't.
    *
    * Result is deduplicated by representativeUrl across tiers — if a
    * URL would be picked by both Tier 1 and Tier 2, the Tier 1 entry
    * wins. Empty window returns an empty Seq.
    *
    * Behavioural difference vs `selectFromWindow`: this method WILL
    * emit Tier 4 picks even when Tiers 1+2+3 are empty (pure-sports
    * windows now produce a notification instead of skipping). Callers
    * that need the pre-V29 "skip dispatch on pure sports/entertainment"
    * behaviour MUST use `selectFromWindow` instead.
    */
  def selectTopN(articles: List[ArticleV3Row], n: Int): Seq[Selection] = {
    if (articles.isEmpty || n <= 0) return Nil
    val windowSize = articles.size

    val strict: Seq[Selection] =
      tier1Candidates(articles, windowSize) ++
        tier2Candidates(articles, windowSize) ++
        tier3Candidates(articles, windowSize)

    // Deduplicate by URL while preserving best-first order.
    val seen   = scala.collection.mutable.Set.empty[String]
    val picks  = scala.collection.mutable.ListBuffer.empty[Selection]
    strict.foreach { s =>
      if (picks.size < n && !seen.contains(s.representativeUrl)) {
        seen += s.representativeUrl
        picks += s
      }
    }

    if (picks.size < n) {
      // Tier 4 fill: any remaining articles by recency. Used only when
      // strict tiers can't fill N. The caller has opted in to fallback
      // pool semantics (per the feature flag), so emitting "anything
      // fresh" is preferred over an undersized pool.
      // Sort by publishedAt DESC, breaking ties on id DESC so newer
      // ingestion order wins when the producer batched articles at the
      // same timestamp.
      val byRecency = articles.sortBy(a => (-a.publishedAt.toEpochSecond, -a.id))
      byRecency.iterator
        .filterNot(a => seen.contains(a.url))
        .take(n - picks.size)
        .foreach { a =>
          seen += a.url
          picks += Selection(
            representativeArticleId = a.id,
            representativeUrl       = a.url,
            tier                    = Tier4LastResort,
            windowSize              = windowSize,
            selectionMetadata       = Json.obj(
              "tier4_method"             -> "latest_any_category".asJson,
              "category"                 -> a.category.asJson,
              "country"                  -> a.country.asJson,
              "publisher"                -> a.author.asJson,
              "representative_article_id"-> a.id.asJson,
              "window_size"              -> windowSize.asJson
            )
          )
        }
    }

    picks.toSeq
  }

  // ───────────────────────────── Tier 1 ─────────────────────────────

  /** Bucket articles by (category, country, 4h-published-window).
    * Within each bucket count distinct publishers. The winning bucket
    * is the one with the MOST distinct publishers (≥ 2). The
    * representative article is the most-recently-published member of
    * the winning bucket — fresh-content bias.
    *
    * "publisher" here = `ArticleV3Row.author`, which on this codebase
    * holds the source domain (e.g. "bbc.com", "reuters.com") thanks
    * to the SummarizedArticleConsumer mapping `ia.source → author`.
    */
  private def tier1Heuristic(
    articles: List[ArticleV3Row], windowSize: Int
  ): Option[Selection] = {
    val buckets: Map[(String, String, OffsetDateTime), List[ArticleV3Row]] =
      articles.groupBy { a =>
        val cat     = a.category.getOrElse("other")
        val country = a.country.getOrElse("_")
        // floor published_at to the nearest 4h boundary
        val pub     = a.publishedAt
        val flooredHour = (pub.getHour / 4) * 4
        val bucketKey   = pub
          .withHour(flooredHour).withMinute(0).withSecond(0).withNano(0)
        (cat, country, bucketKey)
      }

    // pick winning bucket = max distinct publishers ≥ 2
    val winnerOpt = buckets.values
      .map(members => members -> distinctPublishers(members))
      .filter { case (_, pubs) => pubs.size >= 2 }
      .toList
      .sortBy { case (_, pubs) => -pubs.size }
      .headOption

    winnerOpt.map { case (members, publishers) =>
      val rep = members.maxBy(_.publishedAt.toEpochSecond) // most-recent
      Selection(
        representativeArticleId = rep.id,
        representativeUrl       = rep.url,
        tier                    = Tier1Heuristic,
        windowSize              = windowSize,
        selectionMetadata       = Json.obj(
          "tier1_method"             -> "heuristic".asJson,
          "bucket_size"              -> members.size.asJson,
          "publisher_count"          -> publishers.size.asJson,
          "publishers"               -> publishers.toList.sorted.asJson,
          "country"                  -> rep.country.asJson,
          "category"                 -> rep.category.asJson,
          "representative_article_id"-> rep.id.asJson,
          "window_size"              -> windowSize.asJson
        )
      )
    }
  }

  // ───────────────────────────── Tier 2 ─────────────────────────────

  /** Pick the article whose URL appears most often across distinct
    * publishers (republication chain — Reuters wire picked up by BBC,
    * Guardian, Dutch Times etc.). Falls back to "pick latest article
    * from the most-prolific publisher" when no URL is multi-pub.
    *
    * In practice: snelnieuws articles are unique-per-URL upserts, so
    * the URL-multi-pub branch is rare. The publisher-diversity
    * fallback is the workhorse here.
    */
  private def tier2SinglePublisher(
    articles: List[ArticleV3Row], windowSize: Int
  ): Option[Selection] = {
    // Group by (lowercased) URL — if any URL has ≥ 2 publishers, pick
    // it. articleByUrl is the most-recent representative for that URL.
    val byUrl = articles.groupBy(_.url.toLowerCase)
    val multiPub = byUrl.values
      .map(members => members -> distinctPublishers(members))
      .filter { case (_, p) => p.size >= 2 }
      .toList
      .sortBy { case (_, p) => -p.size }
      .headOption

    val pickOpt: Option[(ArticleV3Row, Set[String], String)] = multiPub.map {
      case (members, publishers) =>
        val rep = members.maxBy(_.publishedAt.toEpochSecond)
        (rep, publishers, "url_multipub")
    }.orElse {
      // Fallback: take the publisher with the most articles, then
      // pick that publisher's freshest article. Bias is "what's the
      // most prolific outlet today saying NOW".
      val byPub = articles.flatMap(a => a.author.map(_ -> a)).groupBy(_._1)
      if (byPub.isEmpty) None
      else {
        val (pub, items) = byPub.maxBy(_._2.size)
        val rep = items.map(_._2).maxBy(_.publishedAt.toEpochSecond)
        Some((rep, Set(pub), "single_publisher_freshest"))
      }
    }

    pickOpt.map { case (rep, publishers, method) =>
      Selection(
        representativeArticleId = rep.id,
        representativeUrl       = rep.url,
        tier                    = Tier2SinglePublisher,
        windowSize              = windowSize,
        selectionMetadata       = Json.obj(
          "tier2_method"            -> method.asJson,
          "publisher_count"         -> publishers.size.asJson,
          "publishers"              -> publishers.toList.sorted.asJson,
          "country"                 -> rep.country.asJson,
          "category"                -> rep.category.asJson,
          "representative_article_id"-> rep.id.asJson,
          "window_size"             -> windowSize.asJson
        )
      )
    }
  }

  // ───────────────────────────── Tier 3 ─────────────────────────────

  /** Last-resort safety net — pick the most-recent politics OR
    * business article. Always emits SOMETHING as long as the window
    * has at least one such article. Returns None only when the
    * window is purely sports / entertainment / etc., which would
    * make a "lead news story" feel arbitrary — better to skip the
    * dispatch than send a sports headline as "today's top story".
    */
  private def tier3PoliticsOrBusiness(
    articles: List[ArticleV3Row], windowSize: Int
  ): Option[Selection] = {
    val eligible = articles.filter { a =>
      a.category.exists { c =>
        val lower = c.toLowerCase
        lower == "politics" || lower == "business"
      }
    }
    if (eligible.isEmpty) return None
    val rep = eligible.maxBy(_.publishedAt.toEpochSecond)
    Some(Selection(
      representativeArticleId = rep.id,
      representativeUrl       = rep.url,
      tier                    = Tier3PoliticsOrBusiness,
      windowSize              = windowSize,
      selectionMetadata       = Json.obj(
        "tier3_method"             -> "latest_politics_or_business".asJson,
        "category"                 -> rep.category.asJson,
        "country"                  -> rep.country.asJson,
        "publisher"                -> rep.author.asJson,
        "representative_article_id"-> rep.id.asJson,
        "window_size"              -> windowSize.asJson
      )
    ))
  }

  // ─────────────── Multi-candidate variants (selectTopN) ──────────────

  /** Tier 1 multi-result: one Selection per (category, country, 4h)
    * bucket with ≥ 2 distinct publishers. Sorted by publisher count
    * DESC, then bucket size DESC, then rep.publishedAt DESC. The rep
    * for each bucket is its most-recently-published member. */
  private def tier1Candidates(
    articles: List[ArticleV3Row], windowSize: Int
  ): Seq[Selection] = {
    val buckets: Map[(String, String, OffsetDateTime), List[ArticleV3Row]] =
      articles.groupBy { a =>
        val cat     = a.category.getOrElse("other")
        val country = a.country.getOrElse("_")
        val pub     = a.publishedAt
        val flooredHour = (pub.getHour / 4) * 4
        val bucketKey   = pub
          .withHour(flooredHour).withMinute(0).withSecond(0).withNano(0)
        (cat, country, bucketKey)
      }

    buckets.values.toList
      .map(members => members -> distinctPublishers(members))
      .filter { case (_, pubs) => pubs.size >= 2 }
      .sortBy { case (members, pubs) =>
        // Primary: publisher count DESC; secondary: bucket size DESC;
        // tertiary: max publishedAt DESC. All negated for ascending sort.
        (-pubs.size, -members.size, -members.map(_.publishedAt.toEpochSecond).max)
      }
      .map { case (members, publishers) =>
        val rep = members.maxBy(_.publishedAt.toEpochSecond)
        Selection(
          representativeArticleId = rep.id,
          representativeUrl       = rep.url,
          tier                    = Tier1Heuristic,
          windowSize              = windowSize,
          selectionMetadata       = Json.obj(
            "tier1_method"             -> "heuristic".asJson,
            "bucket_size"              -> members.size.asJson,
            "publisher_count"          -> publishers.size.asJson,
            "publishers"               -> publishers.toList.sorted.asJson,
            "country"                  -> rep.country.asJson,
            "category"                 -> rep.category.asJson,
            "representative_article_id"-> rep.id.asJson,
            "window_size"              -> windowSize.asJson
          )
        )
      }
  }

  /** Tier 2 multi-result: first all multi-publisher URLs (≥ 2
    * publishers on the same URL) sorted by publisher count DESC, then
    * one entry per distinct publisher (publisher's freshest article)
    * sorted by article count DESC. The "one per publisher" branch is
    * the workhorse on this codebase since URLs are unique-per-row. */
  private def tier2Candidates(
    articles: List[ArticleV3Row], windowSize: Int
  ): Seq[Selection] = {
    val byUrl = articles.groupBy(_.url.toLowerCase)

    val multiPubByUrl: Seq[Selection] =
      byUrl.values.toList
        .map(members => members -> distinctPublishers(members))
        .filter { case (_, p) => p.size >= 2 }
        .sortBy { case (_, p) => -p.size }
        .map { case (members, publishers) =>
          val rep = members.maxBy(_.publishedAt.toEpochSecond)
          Selection(
            representativeArticleId = rep.id,
            representativeUrl       = rep.url,
            tier                    = Tier2SinglePublisher,
            windowSize              = windowSize,
            selectionMetadata       = Json.obj(
              "tier2_method"            -> "url_multipub".asJson,
              "publisher_count"         -> publishers.size.asJson,
              "publishers"              -> publishers.toList.sorted.asJson,
              "country"                 -> rep.country.asJson,
              "category"                -> rep.category.asJson,
              "representative_article_id"-> rep.id.asJson,
              "window_size"             -> windowSize.asJson
            )
          )
        }

    // One candidate per distinct publisher, ordered by that publisher's
    // article count DESC; rep = that publisher's freshest article.
    val byPub: Seq[Selection] =
      articles
        .flatMap(a => a.author.map(_ -> a))
        .groupBy(_._1)
        .toList
        .sortBy { case (_, items) => -items.size }
        .map { case (pub, items) =>
          val rep = items.map(_._2).maxBy(_.publishedAt.toEpochSecond)
          Selection(
            representativeArticleId = rep.id,
            representativeUrl       = rep.url,
            tier                    = Tier2SinglePublisher,
            windowSize              = windowSize,
            selectionMetadata       = Json.obj(
              "tier2_method"            -> "single_publisher_freshest".asJson,
              "publisher_count"         -> 1.asJson,
              "publishers"              -> List(pub).asJson,
              "publisher_article_count" -> items.size.asJson,
              "country"                 -> rep.country.asJson,
              "category"                -> rep.category.asJson,
              "representative_article_id"-> rep.id.asJson,
              "window_size"             -> windowSize.asJson
            )
          )
        }

    multiPubByUrl ++ byPub
  }

  /** Tier 3 multi-result: every politics/business article in the
    * window as its own candidate, sorted by publishedAt DESC. The
    * head matches the legacy `tier3PoliticsOrBusiness` single pick. */
  private def tier3Candidates(
    articles: List[ArticleV3Row], windowSize: Int
  ): Seq[Selection] = {
    val eligible = articles.filter { a =>
      a.category.exists { c =>
        val lower = c.toLowerCase
        lower == "politics" || lower == "business"
      }
    }
    eligible.sortBy(-_.publishedAt.toEpochSecond).map { rep =>
      Selection(
        representativeArticleId = rep.id,
        representativeUrl       = rep.url,
        tier                    = Tier3PoliticsOrBusiness,
        windowSize              = windowSize,
        selectionMetadata       = Json.obj(
          "tier3_method"             -> "latest_politics_or_business".asJson,
          "category"                 -> rep.category.asJson,
          "country"                  -> rep.country.asJson,
          "publisher"                -> rep.author.asJson,
          "representative_article_id"-> rep.id.asJson,
          "window_size"              -> windowSize.asJson
        )
      )
    }
  }

  // ────────────────────────── helpers ───────────────────────────────

  private def distinctPublishers(members: List[ArticleV3Row]): Set[String] =
    members.flatMap(_.author).filter(_.nonEmpty).toSet
}
