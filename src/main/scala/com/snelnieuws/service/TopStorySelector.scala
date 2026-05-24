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

  /** Three categories of selection outcome. Embedded into the audit
    * `selection_tier` column so we can later analyze which path was
    * fired most often.
    */
  sealed trait Tier { def code: Short }
  case object Tier1Heuristic extends Tier { val code: Short = 1 }
  case object Tier2SinglePublisher extends Tier { val code: Short = 2 }
  case object Tier3PoliticsOrBusiness extends Tier { val code: Short = 3 }

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

  // ────────────────────────── helpers ───────────────────────────────

  private def distinctPublishers(members: List[ArticleV3Row]): Set[String] =
    members.flatMap(_.author).filter(_.nonEmpty).toSet
}
