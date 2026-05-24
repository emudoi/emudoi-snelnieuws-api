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
}
