package com.snelnieuws.model

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Pure-data validation for the locale-aware picker enums. Catches
  * typos and missing cells on day one — adding a new category or a new
  * locale without updating ALL the cross-cells would otherwise only
  * fail at request time. */
class LocalizedPickersSpec extends AnyWordSpec with Matchers {

  "Languages.codes" should {
    "match the rendering order documented in the scaladoc" in {
      Languages.codes shouldBe Seq("de", "fr", "it", "en", "es", "pl", "nl")
    }
  }

  "Languages.localizedNames" should {
    "have one outer row per code" in {
      Languages.localizedNames.keySet shouldBe Languages.codes.toSet
    }

    "have a non-empty entry for every (locale, code) cell" in {
      Languages.localizedNames.foreach { case (locale, row) =>
        row.keySet shouldBe Languages.codes.toSet
        row.foreach { case (code, name) =>
          withClue(s"locale=$locale code=$code: ") { name should not be empty }
        }
      }
    }

    "render autonyms on the diagonal" in {
      Languages.localizedNames("de")("de") shouldBe "Deutsch"
      Languages.localizedNames("fr")("fr") shouldBe "Français"
      Languages.localizedNames("it")("it") shouldBe "Italiano"
      Languages.localizedNames("en")("en") shouldBe "English"
      Languages.localizedNames("es")("es") shouldBe "Español"
      Languages.localizedNames("pl")("pl") shouldBe "Polski"
      Languages.localizedNames("nl")("nl") shouldBe "Nederlands"
    }
  }

  "Languages.forLocale" should {
    "fall back to English for unknown locales" in {
      val ja = Languages.forLocale("ja")
      ja.map(_.code) shouldBe Languages.codes
      ja.find(_.code == "de").map(_.name) shouldBe Some("German")
    }

    "return entries in rendering order" in {
      Languages.forLocale("nl").map(_.code) shouldBe Languages.codes
    }
  }

  "CategoryNames.localizedNames" should {
    "cover every locale defined in Languages.codes" in {
      CategoryNames.localizedNames.keySet shouldBe Languages.codes.toSet
    }

    "have a non-empty entry for every (locale, code) cell" in {
      val codeSet = Categories.all.toSet
      CategoryNames.localizedNames.foreach { case (locale, row) =>
        withClue(s"locale=$locale: row keys") {
          row.keySet shouldBe codeSet
        }
        row.foreach { case (code, name) =>
          withClue(s"locale=$locale code=$code: ") { name should not be empty }
        }
      }
    }
  }

  "CategoryNames.forLocale" should {
    "return entries in Categories.all order" in {
      CategoryNames.forLocale("nl").map(_.code) shouldBe Categories.all
    }

    "fall back to English for unknown locales" in {
      CategoryNames.forLocale("ja").map(_.name).headOption shouldBe Some("Politics")
    }
  }
}
