package com.snelnieuws.repository

import cats.effect.unsafe.implicits.global
import com.snelnieuws.{DatabaseTestSupport, SharedPostgresContainer}
import com.snelnieuws.db.Database
import com.snelnieuws.model.{ArticleCreate, UserEventInput}
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

/** Recommender Phase-0: the write path must stamp each article-bearing event
  * with the catalog snapshot (title/url + features), taking the article row as
  * the source of truth and falling back to client-sent values only when the
  * article is no longer in the catalog (e.g. already purged by the 72h
  * cleanup). */
class EventRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articles      = new ArticleRepository(Database.transactor)
  private lazy val eulangArticles = new ArticleRepository(Database.transactor, "eulang_articles")
  private lazy val repo          = new EventRepository(Database.transactor, articles, eulangArticles)

  private val tag = s"event-repo-spec-${UUID.randomUUID().toString.take(8)}"

  // Don't leave seeded articles in the shared container for other suites.
  override def afterAll(): Unit = {
    if (SharedPostgresContainer.isAvailable) {
      sql"DELETE FROM articles WHERE author = $tag"
        .update.run.transact(Database.transactor).unsafeRunSync()
      sql"DELETE FROM eulang_articles WHERE author = $tag"
        .update.run.transact(Database.transactor).unsafeRunSync()
    }
    super.afterAll()
  }

  private def seedArticle(
    eulang: Boolean,
    category: Option[String],
    titleSuffix: String
  ): Long = {
    val r = if (eulang) eulangArticles else articles
    r.create(ArticleCreate(
      author      = Some(tag),
      title       = s"$tag-$titleSuffix-${UUID.randomUUID()}",
      description = None,
      url         = s"https://example.com/$tag/$titleSuffix",
      urlToImage  = None,
      content     = None,
      category    = category
    )).toOption.get.id
  }

  /** Stamped columns for a client's single article event. */
  private def stampedRow(
    clientId: UUID
  ): Option[(Option[String], Option[String], Option[String], Option[String], Option[String], Option[String])] =
    sql"""
      SELECT title, url, category, source, language, country
      FROM user_events
      WHERE client_id = $clientId
      ORDER BY id DESC LIMIT 1
    """
      .query[(Option[String], Option[String], Option[String], Option[String], Option[String], Option[String])]
      .option.transact(Database.transactor).unsafeRunSync()

  "EventRepository.insertBatch enrichment" should {

    "stamp title/url + features from the articles catalog" in {
      requireDb()
      val id  = seedArticle(eulang = false, category = Some("technology"), "art")
      val cid = UUID.randomUUID()
      repo.insertBatch(cid, List(
        UserEventInput(`type` = "impression", articleId = Some(id.toString))
      )).toOption.get shouldBe 1

      val (title, url, category, source, language, _) = stampedRow(cid).get
      title.get   should startWith(tag)
      url.get     shouldBe s"https://example.com/$tag/art"
      category    shouldBe Some("technology")
      source      shouldBe Some(tag)     // source := article author
      language    shouldBe Some("en")    // DB default on the article row
    }

    "resolve the eulang catalog for e-prefixed ids" in {
      requireDb()
      val id  = seedArticle(eulang = true, category = Some("politics"), "eu")
      val cid = UUID.randomUUID()
      repo.insertBatch(cid, List(
        UserEventInput(`type` = "open", articleId = Some(s"e$id"))
      )).toOption.get shouldBe 1

      val (title, url, category, _, _, _) = stampedRow(cid).get
      title.get should startWith(tag)
      url.get   shouldBe s"https://example.com/$tag/eu"
      category  shouldBe Some("politics")
    }

    "let the server snapshot OVERRIDE inconsistent client-sent values" in {
      requireDb()
      val id  = seedArticle(eulang = false, category = Some("technology"), "ovr")
      val cid = UUID.randomUUID()
      repo.insertBatch(cid, List(
        UserEventInput(
          `type`   = "impression",
          articleId = Some(id.toString),
          category  = Some("WRONG-client-cat"),
          source    = Some("WRONG-client-src"),
          language  = Some("xx")
        )
      )).toOption.get shouldBe 1

      val (_, _, category, source, language, _) = stampedRow(cid).get
      category shouldBe Some("technology") // catalog wins
      source   shouldBe Some(tag)
      language shouldBe Some("en")
    }

    "fall back to client-sent values when the article is not in the catalog" in {
      requireDb()
      val cid = UUID.randomUUID()
      // An id that does not exist (purged): no stamp, keep what the client sent.
      repo.insertBatch(cid, List(
        UserEventInput(
          `type`   = "impression",
          articleId = Some("999999999"),
          category  = Some("clientcat"),
          source    = Some("clientsrc"),
          language  = Some("nl")
        )
      )).toOption.get shouldBe 1

      val (title, url, category, source, language, _) = stampedRow(cid).get
      title    shouldBe None
      url      shouldBe None
      category shouldBe Some("clientcat")
      source   shouldBe Some("clientsrc")
      language shouldBe Some("nl")
    }

    "accept non-article events with no stamp" in {
      requireDb()
      val cid = UUID.randomUUID()
      repo.insertBatch(cid, List(
        UserEventInput(`type` = "category_select", articleId = None, category = Some("sports"))
      )).toOption.get shouldBe 1

      val (title, url, category, _, _, _) = stampedRow(cid).get
      title    shouldBe None
      url      shouldBe None
      category shouldBe Some("sports") // client value kept (no article to override from)
    }

    "drop events whose type is not allowed" in {
      requireDb()
      val cid = UUID.randomUUID()
      repo.insertBatch(cid, List(
        UserEventInput(`type` = "not_a_real_type", articleId = None)
      )).toOption.get shouldBe 0
    }
  }
}
