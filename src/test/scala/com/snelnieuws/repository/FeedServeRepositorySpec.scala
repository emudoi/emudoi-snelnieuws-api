package com.snelnieuws.repository

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

/** Recommender Phase-0: the served-slate log records the ordered slate the feed
  * policy actually returned — one row per article, with its position and the
  * serving propensity. */
class FeedServeRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val repo = new FeedServeRepository(Database.transactor)

  private def rowsFor(clientId: UUID): List[(String, Int, Option[String], Double)] =
    sql"""
      SELECT article_id, position, list_name, propensity
      FROM feed_serves
      WHERE client_id = $clientId
      ORDER BY position ASC
    """
      .query[(String, Int, Option[String], Double)]
      .to[List].transact(Database.transactor).unsafeRunSync()

  "FeedServeRepository.logServe" should {

    "write one row per served article with ascending positions" in {
      requireDb()
      val cid = UUID.randomUUID()
      repo.logServe(
        clientId = cid,
        items    = List("123", "e9", "456"),
        listName = Some("technology,sports"),
        country  = Some("nl"),
        language = Some("en")
      ).toOption.get shouldBe 3

      val rows = rowsFor(cid)
      rows.map(_._1) shouldBe List("123", "e9", "456")       // served order preserved
      rows.map(_._2) shouldBe List(0, 1, 2)                  // 0-based positions
      rows.map(_._3).distinct shouldBe List(Some("technology,sports"))
      rows.map(_._4).distinct shouldBe List(1.0)             // deterministic policy
    }

    "no-op on an empty slate" in {
      requireDb()
      val cid = UUID.randomUUID()
      repo.logServe(cid, Nil, None, Some("nl"), Some("en")).toOption.get shouldBe 0
      rowsFor(cid) shouldBe empty
    }
  }
}
