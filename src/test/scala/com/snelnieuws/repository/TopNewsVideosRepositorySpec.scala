package com.snelnieuws.repository

import cats.effect.unsafe.implicits.global
import com.snelnieuws.DatabaseTestSupport
import com.snelnieuws.db.Database
import com.snelnieuws.model.TopNewsVideoRow
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.UUID

class TopNewsVideosRepositorySpec
    extends AnyWordSpec
    with Matchers
    with DatabaseTestSupport {

  private lazy val repo = new TopNewsVideosRepository(Database.transactor)

  private val tag = s"video-repo-spec-${UUID.randomUUID().toString.take(8)}"

  private def wipeOurRows(): Unit =
    sql"DELETE FROM top_news_videos WHERE description LIKE ${tag + "%"}"
      .update.run.transact(Database.transactor).unsafeRunSync()

  "TopNewsVideosRepository.insertBatch" should {

    "return a generated id per inserted row" in {
      requireDb()
      wipeOurRows()
      val rows = List(
        TopNewsVideoRow(
          text        = "Top news of the day. Body.",
          anchor      = "erica",
          variant     = "v21",
          description = Some(s"$tag-row1")
        ),
        TopNewsVideoRow(
          text        = "Top 5 news of the day. T1. T2.",
          anchor      = "erica",
          variant     = "yellow",
          description = Some(s"$tag-row2")
        )
      )
      val ids = repo.insertBatch(rows).toOption.get
      ids should have length 2
      ids.distinct should have length 2
    }

    "write the expected columns" in {
      requireDb()
      wipeOurRows()
      val rows = List(
        TopNewsVideoRow("speech-A", "erica", "v21",    Some(s"$tag-A")),
        TopNewsVideoRow("speech-B", "erica", "yellow", Some(s"$tag-B"))
      )
      val ids = repo.insertBatch(rows).toOption.get
      val stored = sql"""
        SELECT id, text, anchor, variant, description, status
          FROM top_news_videos
         WHERE id = ANY($ids)
         ORDER BY id ASC
      """
        .query[(Long, String, String, String, Option[String], String)]
        .to[List]
        .transact(Database.transactor)
        .unsafeRunSync()
      stored should have length 2
      stored.map(_._2) shouldBe List("speech-A", "speech-B")
      stored.map(_._3).toSet shouldBe Set("erica")
      stored.map(_._4) shouldBe List("v21", "yellow")
      stored.map(_._5) shouldBe List(Some(s"$tag-A"), Some(s"$tag-B"))
      stored.map(_._6).toSet shouldBe Set("pending")
    }

    "return Nil for an empty input list" in {
      requireDb()
      repo.insertBatch(Nil).toOption.get shouldBe Nil
    }
  }
}
