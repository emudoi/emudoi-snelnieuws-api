package com.snelnieuws.service

import com.snelnieuws.model.ArticleV3Row
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{OffsetDateTime, ZoneOffset}

/** Pure-function tests for the top-story selector. No DB, no IO —
  * the selector takes a List[ArticleV3Row] and returns Option[Selection],
  * so every test case is just "construct a window, assert what gets picked".
  *
  * The 6 cases below cover:
  *   1. Empty window → None
  *   2. Tier 1 fires when multi-publisher in same (category, country, 4h)
  *      bucket
  *   3. Tier 1 SKIPS the largest bucket if it's single-publisher (no
  *      semantic noise from re-runs of the same publisher)
  *   4. Tier 2 fires when Tier 1 finds no multi-publisher bucket
  *   5. Tier 3 fires when Tiers 1+2 fail (single article, politics)
  *   6. All tiers None when window has only sports/entertainment with
  *      no multi-publisher overlap
  */
class TopStorySelectorSpec extends AnyWordSpec with Matchers {

  /** Build a minimal ArticleV3Row. Defaults are tuned so most tests
    * can override just the fields they care about. */
  private def row(
    id: Long,
    title: String       = "headline",
    author: String      = "default.example",
    url: String         = "",
    category: String    = "politics",
    country: String     = "nl",
    publishedAt: OffsetDateTime =
      OffsetDateTime.of(2026, 5, 24, 10, 0, 0, 0, ZoneOffset.UTC)
  ): ArticleV3Row = ArticleV3Row(
    id          = id,
    author      = Some(author),
    title       = title,
    description = None,
    url         = if (url.isEmpty) s"https://example.com/$id" else url,
    urlToImage  = None,
    publishedAt = publishedAt,
    content     = None,
    category    = Some(category),
    country     = Some(country),
    isLocal     = false,
    language    = "en"
  )

  "selectFromWindow" should {

    "return None for an empty window" in {
      TopStorySelector.selectFromWindow(Nil) shouldBe None
    }

    "fire Tier 1 when multiple publishers cover the same (category, country, 4h) bucket" in {
      val baseTs = OffsetDateTime.of(2026, 5, 24, 10, 0, 0, 0, ZoneOffset.UTC)
      val articles = List(
        row(1, "Trump in Beijing", author = "reuters.com", publishedAt = baseTs),
        row(2, "Trump meets Xi",   author = "bbc.com",     publishedAt = baseTs.plusMinutes(30)),
        row(3, "Xi-Trump summit",  author = "theguardian.com", publishedAt = baseTs.plusMinutes(45)),
        // Distractor — different category/country/bucket, single publisher
        row(4, "Local sport recap", author = "single.example", category = "sports",
            country = "de", publishedAt = baseTs.plusHours(5))
      )
      val sel = TopStorySelector.selectFromWindow(articles)
      sel.map(_.tier) shouldBe Some(TopStorySelector.Tier1Heuristic)
      // The representative is the MOST RECENT member of the winning bucket
      // (the Trump-Xi cluster has 3 members; the freshest is id=3).
      sel.map(_.representativeArticleId) shouldBe Some(3)
      sel.map(_.windowSize)              shouldBe Some(4)
    }

    "skip a large single-publisher bucket and fall through if no other tier fires" in {
      // A bucket of 10 articles ALL from one publisher in entertainment
      // (which Tier 3 doesn't accept) should NOT win Tier 1 (need ≥2
      // publishers). Tier 2 falls back to "most prolific publisher's
      // freshest article" — so we DO get a pick, but from Tier 2.
      val baseTs = OffsetDateTime.of(2026, 5, 24, 10, 0, 0, 0, ZoneOffset.UTC)
      val articles = (1L to 10L).toList.map { i =>
        row(i, s"Title $i", author = "spammy.example",
            category = "entertainment", country = "nl",
            publishedAt = baseTs.plusMinutes(i))
      }
      val sel = TopStorySelector.selectFromWindow(articles).getOrElse(fail("expected a selection"))
      sel.tier shouldBe TopStorySelector.Tier2SinglePublisher
      // Most recent from the only publisher = id 10
      sel.representativeArticleId shouldBe 10
    }

    "fire Tier 2 when no bucket has ≥2 publishers but at least one publisher is present" in {
      // 3 sports articles from a single publisher — no Tier 1 (need ≥2
      // publishers), no Tier 3 (sports doesn't qualify). Tier 2's
      // fallback path picks the publisher with the most articles.
      val baseTs = OffsetDateTime.of(2026, 5, 24, 10, 0, 0, 0, ZoneOffset.UTC)
      val articles = List(
        row(1, "Sport A", author = "espn.example", category = "sports",
            publishedAt = baseTs.plusMinutes(5)),
        row(2, "Sport B", author = "espn.example", category = "sports",
            publishedAt = baseTs.plusMinutes(15)),
        row(3, "Sport C", author = "espn.example", category = "sports",
            publishedAt = baseTs.plusMinutes(45))
      )
      val sel = TopStorySelector.selectFromWindow(articles).getOrElse(fail("expected a selection"))
      sel.tier                    shouldBe TopStorySelector.Tier2SinglePublisher
      sel.representativeArticleId shouldBe 3 // freshest
    }

    "fire Tier 3 (politics/business) as last resort when Tier 1+2 fail" in {
      // Single article, politics — Tier 1 needs ≥2 publishers so fails.
      // Tier 2 falls back to most-prolific-publisher; would also pick
      // this article via the single-publisher fallback, so Tier 3
      // doesn't actually get reached here UNLESS Tier 2 returns None.
      // To force Tier 3, use an article whose author is None (Tier 2's
      // fallback only fires when at least one article has an author).
      val articles = List(
        row(42, "Trump signs bill", author = "", category = "politics",
            country = "nl")
      )
      // Manually rewrite to author=None (helper defaults to Some)
      val noAuthor = articles.map(a => a.copy(author = None))
      val sel = TopStorySelector.selectFromWindow(noAuthor).getOrElse(fail("expected a selection"))
      sel.tier                    shouldBe TopStorySelector.Tier3PoliticsOrBusiness
      sel.representativeArticleId shouldBe 42
    }

    "return None when window has only sports/entertainment AND no author info" in {
      // Pure dead-end — no Tier 1 (single publisher only), Tier 2's
      // fallback requires at least one author (none here), Tier 3 only
      // accepts politics/business.
      val articles = List(
        row(1, "Sport recap", author = "", category = "sports", country = "de")
      ).map(a => a.copy(author = None))
      TopStorySelector.selectFromWindow(articles) shouldBe None
    }
  }

  "selectTopN" should {

    "return an empty Seq for an empty window or non-positive n" in {
      TopStorySelector.selectTopN(Nil, 4)             shouldBe empty
      TopStorySelector.selectTopN(List(row(1)), 0)    shouldBe empty
      TopStorySelector.selectTopN(List(row(1)), -1)   shouldBe empty
    }

    "return up to n distinct-URL selections in best-first tier order" in {
      val baseTs = OffsetDateTime.of(2026, 5, 24, 10, 0, 0, 0, ZoneOffset.UTC)
      // Tier 1 cluster (3 publishers, politics/nl/10:xx bucket)
      val tier1Cluster = List(
        row(1, "Trump Beijing",  author = "reuters.com",     publishedAt = baseTs),
        row(2, "Trump-Xi summit", author = "bbc.com",        publishedAt = baseTs.plusMinutes(30)),
        row(3, "Xi-Trump meets",  author = "theguardian.com", publishedAt = baseTs.plusMinutes(45))
      )
      // Tier 2 single-publisher run (3 entertainment articles from one publisher)
      val tier2SinglePub = (4L to 6L).toList.map { i =>
        row(i, s"Music news $i", author = "musicfeed.example",
            category = "entertainment", publishedAt = baseTs.plusHours(2).plusMinutes(i))
      }
      // Lone politics article in a different bucket. Tier 2's
      // single-publisher pick catches it first (URL dedup wins over the
      // Tier 3 candidate) — that's the design.
      val lonePolitics = row(7, "Budget passed", author = "lone.example",
        category = "politics", country = "de",
        publishedAt = baseTs.plusHours(6))

      val top4 = TopStorySelector.selectTopN(tier1Cluster ++ tier2SinglePub :+ lonePolitics, 4)

      // Distinct URLs.
      top4.map(_.representativeUrl).distinct.size shouldBe top4.size

      // First pick must be Tier 1 (highest publisher count).
      top4.headOption.map(_.tier) shouldBe Some(TopStorySelector.Tier1Heuristic)

      // The lone politics article must surface in the pool (URL-deduped
      // — Tier 2 single_publisher picks it before Tier 3 sees it).
      top4.exists(_.representativeArticleId == 7) shouldBe true

      // At most n.
      top4.size should be <= 4
    }

    "fill with Tier 4 last-resort when strict tiers can't reach n" in {
      // One politics article from a unique publisher (Tier 3 fires
      // once; Tier 2 single-publisher fallback also fires once but on
      // the same URL, so dedup collapses them). Strict tiers produce
      // 1 candidate; ask for 4 → 3 Tier 4 fillers needed but only 0
      // other articles exist, so we get exactly 1 pick total.
      val articles = List(
        row(1, "Lone politics", author = "lone.example", category = "politics")
      )
      val picks = TopStorySelector.selectTopN(articles, 4)
      picks.size shouldBe 1
      picks.head.tier should (be(TopStorySelector.Tier3PoliticsOrBusiness) or
        be(TopStorySelector.Tier2SinglePublisher))
    }

    "produce a 3-strong pool from sports/entertainment without Tier 4" in {
      // Three sports articles from three different publishers in the
      // same (sports, nl, 10:00) bucket → Tier 1 fires once with
      // 3 distinct publishers, then Tier 2's single-publisher branch
      // contributes one entry per publisher (deduped by URL against
      // Tier 1's rep). Net: 1 Tier 1 pick + 2 distinct-URL Tier 2
      // picks = 3 total. No Tier 4 needed.
      val articles = List(
        row(1, "Sport A", author = "espn.example",   category = "sports"),
        row(2, "Sport B", author = "skysports.example", category = "sports"),
        row(3, "Sport C", author = "fox.example",    category = "sports")
      )
      val picks = TopStorySelector.selectTopN(articles, 4)
      picks.size shouldBe 3
      picks.head.tier shouldBe TopStorySelector.Tier1Heuristic
      picks.tail.foreach(p => p.tier shouldBe TopStorySelector.Tier2SinglePublisher)
    }

    "use Tier 4 when articles have NO authors and NO politics/business" in {
      // No tier should fire on this window — author=None disables
      // Tier 1 (need ≥2 publishers) AND Tier 2 (fallback needs at
      // least one author), category=sports disables Tier 3. The new
      // Tier 4 last-resort fills the pool from latest-first articles.
      // Stagger publishedAt so the "newest first" ordering is testable
      // without relying on tie-break behaviour for equal timestamps.
      val baseTs = OffsetDateTime.of(2026, 5, 24, 10, 0, 0, 0, ZoneOffset.UTC)
      val articles = (1L to 3L).toList.map { i =>
        row(i, s"Title $i", author = "", category = "sports",
            publishedAt = baseTs.plusMinutes(i * 10)).copy(author = None)
      }
      val picks = TopStorySelector.selectTopN(articles, 4)
      picks.size shouldBe 3
      picks.foreach(_.tier shouldBe TopStorySelector.Tier4LastResort)
      // Latest first → newest id (3) wins, then 2, then 1.
      picks.head.representativeArticleId shouldBe 3
    }

    "preserve `selectFromWindow` behaviour exactly (no Tier 4 emitted)" in {
      // Pure sports/entertainment, no authors → selectFromWindow MUST
      // still return None even though selectTopN now produces Tier 4
      // picks for the same window.
      val articles = (1L to 3L).toList.map { i =>
        row(i, s"Title $i", author = "", category = "sports").copy(author = None)
      }
      TopStorySelector.selectFromWindow(articles) shouldBe None
      TopStorySelector.selectTopN(articles, 4)    should not be empty
    }
  }
}
