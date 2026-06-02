package com.snelnieuws.repository

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import com.snelnieuws.model.SummarizedArticleExport
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

/** Verifies the table-name parameterization: an ArticleRepository bound to
  * `eulang_articles` writes/reads that table and never touches `articles`. */
class EulangArticleRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val eulangRepo = new ArticleRepository(Database.transactor, tableName = "eulang_articles")
  private lazy val mainRepo   = new ArticleRepository(Database.transactor)

  private val tag = s"eulang-repo-spec-${UUID.randomUUID().toString.take(8)}"

  private def export(title: String, urlToImage: Option[String] = Some("/v2/images/x")) =
    SummarizedArticleExport(
      author = Some(tag),
      title = title,
      description = Some("Samenvatting."),
      url = s"https://example.com/$tag/${UUID.randomUUID()}",
      urlToImage = urlToImage,
      publishedAt = "2026-06-02T10:00:00Z",
      createdAt = None,
      category = Some("news"),
      sharedCategories = Some(List("politics")),
      country = Some("nl"),
      sharedCountries = Some(List("nl", "be")),
      language = Some("nl")
    )

  "ArticleRepository(eulang_articles)" should {
    "upsert into eulang_articles and not into articles" in {
      requireDb()
      val title = s"$tag-${UUID.randomUUID()}"
      eulangRepo.upsertByTitle(export(title)).toOption.get should be >= 1

      val inEulang =
        sql"SELECT COUNT(*) FROM eulang_articles WHERE title = $title"
          .query[Int].unique.transact(Database.transactor).unsafeRunSync()
      val inArticles =
        sql"SELECT COUNT(*) FROM articles WHERE title = $title"
          .query[Int].unique.transact(Database.transactor).unsafeRunSync()
      inEulang shouldBe 1
      inArticles shouldBe 0
    }

    "read back by url via findV3ByUrls and honor the language filter" in {
      requireDb()
      val e   = export(s"$tag-${UUID.randomUUID()}")
      eulangRepo.upsertByTitle(e).toOption.get should be >= 1
      val hits = eulangRepo.findV3ByUrls(List(e.url), country = "nl", language = "nl").toOption.get
      hits.map(_.url) should contain(e.url)
      eulangRepo.findV3ByUrls(List(e.url), country = "nl", language = "de").toOption.get shouldBe empty
    }

    "rewrite url_to_image to the fallback route" in {
      requireDb()
      val title = s"$tag-${UUID.randomUUID()}"
      eulangRepo.upsertByTitle(export(title, urlToImage = Some("/v2/images/abc"))).toOption.get should be >= 1
      eulangRepo.setUrlToImageFallback(title).toOption.get shouldBe 1
      val img =
        sql"SELECT url_to_image FROM eulang_articles WHERE title = $title"
          .query[String].unique.transact(Database.transactor).unsafeRunSync()
      img shouldBe "/v2/images/_fallback"
    }
  }
}
