package com.snelnieuws

import cats.effect.unsafe.implicits.global
import com.snelnieuws.db.Database
import com.snelnieuws.model.{ArticleCreate, UserEventInput}
import com.snelnieuws.repository.{ArticleRepository, EventRepository, FeedServeRepository}
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

/** End-to-end Phase-0 data foundation for the LinUCB recommender:
  *
  *   serve a slate          → feed_serves   (what the policy showed)
  *   client posts events    → user_events   (enriched, with reward signal)
  *   labeling join          → (article, reward) tuples the bandit trains on
  *
  * This locks in that the served slate and the engagement events join cleanly
  * on (client_id, public article_id), and that reward shaping comes out as
  * specified (read_engaged/share = 1.0, open = 0.3, nothing = 0.0). */
class RecommenderPhase0FlowSpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val articles       = new ArticleRepository(Database.transactor)
  private lazy val eulangArticles = new ArticleRepository(Database.transactor, "eulang_articles")
  private lazy val events         = new EventRepository(Database.transactor, articles, eulangArticles)
  private lazy val serves         = new FeedServeRepository(Database.transactor)

  private val tag = s"reco-flow-${UUID.randomUUID().toString.take(8)}"

  override def afterAll(): Unit = {
    if (SharedPostgresContainer.isAvailable) {
      sql"DELETE FROM articles WHERE author = $tag"
        .update.run.transact(Database.transactor).unsafeRunSync()
    }
    super.afterAll()
  }

  private def seed(suffix: String): Long =
    articles.create(ArticleCreate(
      author      = Some(tag),
      title       = s"$tag-$suffix-${UUID.randomUUID()}",
      description = None,
      url         = s"https://example.com/$tag/$suffix",
      urlToImage  = None,
      content     = None,
      category    = Some("technology")
    )).toOption.get.id

  /** The recommender labeling query (mirrors docs/recommender-handoff.md §5.1),
    * joined against the served slate as ground truth. */
  private def rewards(clientId: UUID): Map[String, Double] =
    sql"""
      SELECT fs.article_id,
             CASE
               WHEN MAX(CASE WHEN ue.event_type IN ('read_engaged','share') THEN 1 ELSE 0 END) = 1 THEN 1.0
               WHEN MAX(CASE WHEN ue.event_type = 'open'                    THEN 1 ELSE 0 END) = 1 THEN 0.3
               ELSE 0.0
             END AS reward
      FROM feed_serves fs
      LEFT JOIN user_events ue
        ON ue.client_id = fs.client_id
       AND ue.article_id = fs.article_id
       AND ue.event_type IN ('open','read_engaged','share')
      WHERE fs.client_id = $clientId
      GROUP BY fs.article_id
    """
      .query[(String, Double)]
      .to[List].transact(Database.transactor).unsafeRunSync()
      .toMap

  "The Phase-0 serve → events → label flow" should {
    "produce correctly-shaped reward labels joined on the served slate" in {
      requireDb()

      val a = seed("a").toString // engaged read  → 1.0
      val b = seed("b").toString // opened only    → 0.3
      val c = seed("c").toString // shown, ignored → 0.0
      val cid = UUID.randomUUID()

      // 1. Policy serves the slate.
      serves.logServe(cid, List(a, b, c), Some("technology"), Some("nl"), Some("en"))
        .toOption.get shouldBe 3

      // 2. Client reports engagement.
      events.insertBatch(cid, List(
        UserEventInput(`type` = "impression",   articleId = Some(a)),
        UserEventInput(`type` = "open",         articleId = Some(a)),
        UserEventInput(`type` = "read_engaged", articleId = Some(a)),
        UserEventInput(`type` = "impression",   articleId = Some(b)),
        UserEventInput(`type` = "open",         articleId = Some(b)),
        UserEventInput(`type` = "impression",   articleId = Some(c))
      )).toOption.get shouldBe 6

      // 3. Labeling join yields the training tuples.
      val r = rewards(cid)
      r(a) shouldBe 1.0
      r(b) shouldBe 0.3
      r(c) shouldBe 0.0

      // And the events carried the stamped article features for context-vector
      // reconstruction (proving the serve→event→feature chain is intact).
      val stampedCategories =
        sql"""SELECT DISTINCT category FROM user_events
              WHERE client_id = $cid AND article_id IS NOT NULL"""
          .query[Option[String]].to[List].transact(Database.transactor).unsafeRunSync()
      stampedCategories.flatten.toSet shouldBe Set("technology")
    }
  }
}
