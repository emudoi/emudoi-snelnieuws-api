package com.snelnieuws.service

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Unit tests for the pure helpers on the SummarizedArticleConsumer
  * companion. The full consumer body wires up a live Kafka client and
  * is exercised by the §10 verification path (kubectl exec + curl) rather
  * than by spinning up a broker in-process.
  */
class SummarizedArticleConsumerSpec extends AnyWordSpec with Matchers {

  "SummarizedArticleConsumer.isFetchableImageUrl" should {

    "accept plain http(s) URLs" in {
      SummarizedArticleConsumer.isFetchableImageUrl("https://example.com/a.jpg") shouldBe true
      SummarizedArticleConsumer.isFetchableImageUrl("http://example.com/a.jpg")  shouldBe true
      // Case shouldn't matter; the consumer trims + lowercases before checking.
      SummarizedArticleConsumer.isFetchableImageUrl("HTTPS://example.com/a.jpg") shouldBe true
      SummarizedArticleConsumer.isFetchableImageUrl("  https://x.com/a.jpg ")    shouldBe true
    }

    "reject empty / null / whitespace-only input" in {
      SummarizedArticleConsumer.isFetchableImageUrl("")     shouldBe false
      SummarizedArticleConsumer.isFetchableImageUrl("   ")  shouldBe false
      SummarizedArticleConsumer.isFetchableImageUrl(null)   shouldBe false
    }

    "reject the unfetchable scheme prefixes" in {
      SummarizedArticleConsumer.isFetchableImageUrl(
        "data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org"
      ) shouldBe false
      SummarizedArticleConsumer.isFetchableImageUrl(
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAAB..."
      ) shouldBe false
      SummarizedArticleConsumer.isFetchableImageUrl("blob:https://x.com/abc-def") shouldBe false
      SummarizedArticleConsumer.isFetchableImageUrl("ftp://example.com/img.jpg")  shouldBe false
      // Case-insensitive — uppercased data: must still be rejected.
      SummarizedArticleConsumer.isFetchableImageUrl("DATA:image/png;base64,XXX") shouldBe false
    }
  }
}
