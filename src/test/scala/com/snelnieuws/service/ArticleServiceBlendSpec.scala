package com.snelnieuws.service

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Pure-function tests for the eulang feed blender's two correctness-critical
  * primitives: the local:other interleave, and the gate id encode/decode that
  * makes GET /v3/articles/:id route to the right table. No DB needed. */
class ArticleServiceBlendSpec extends AnyWordSpec with Matchers {

  "blendBuckets" should {
    "interleave 3 local : 1 other and append the remainder once a side drains" in {
      val local = List(1, 2, 3, 4, 5)
      val other = List(10, 20)
      // 3 local, 1 other, 3 local (only 2 left), 1 other → 1,2,3,10,4,5,20
      ArticleService.blendBuckets(local, other, 3, 1) shouldBe List(1, 2, 3, 10, 4, 5, 20)
    }

    "append all of other when local is empty" in {
      ArticleService.blendBuckets(Nil, List(10, 20, 30), 3, 1) shouldBe List(10, 20, 30)
    }

    "append all of local when other is empty" in {
      ArticleService.blendBuckets(List(1, 2, 3, 4), Nil, 3, 1) shouldBe List(1, 2, 3, 4)
    }

    "preserve every element exactly once (no loss, no dup)" in {
      val local = (1 to 17).toList
      val other = (100 to 105).toList
      val blended = ArticleService.blendBuckets(local, other, 3, 1)
      blended.toSet shouldBe (local ++ other).toSet
      blended.size shouldBe (local.size + other.size)
    }

    "clamp non-positive per-cycle counts so it always terminates" in {
      // localPer 0 is clamped to 1 — must not infinite-loop, must keep all.
      val out = ArticleService.blendBuckets(List(1, 2), List(9), 0, 0)
      out.toSet shouldBe Set(1, 2, 9)
    }
  }

  "encodePublicId / decodePublicId" should {
    "namespace eulang ids with an 'e' prefix and leave articles numeric" in {
      ArticleService.encodePublicId(eulang = true, 7)  shouldBe "e7"
      ArticleService.encodePublicId(eulang = false, 7) shouldBe "7"
    }

    "round-trip both sources" in {
      ArticleService.decodePublicId(ArticleService.encodePublicId(eulang = true, 42)) shouldBe Some((true, 42L))
      ArticleService.decodePublicId(ArticleService.encodePublicId(eulang = false, 42)) shouldBe Some((false, 42L))
    }

    "reject malformed ids" in {
      ArticleService.decodePublicId("eabc") shouldBe None
      ArticleService.decodePublicId("xyz")  shouldBe None
      ArticleService.decodePublicId("")     shouldBe None
      ArticleService.decodePublicId("e")    shouldBe None
    }
  }
}
