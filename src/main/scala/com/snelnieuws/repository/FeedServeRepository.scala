package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import org.slf4j.LoggerFactory

import java.util.UUID

/** Write path for the served-slate log (recommender Phase-0). Records what the
  * feed policy actually served — the ordered slate the client received — so the
  * recommender has ground truth of "what was shown" independent of the lossy
  * client-reported `impression` events, plus a place to carry the serving
  * propensity for off-policy evaluation. Best-effort: a logging failure must
  * never affect the feed response. */
class FeedServeRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[FeedServeRepository])
  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  /** (publicArticleId, 0-based position) for one served slate. */
  private type Row = (UUID, String, Int, Option[String], Option[String], Option[String], Double)

  /** Insert one row per served article. `items` is the slate in served order;
    * position is the index. `propensity` is 1.0 for the current deterministic
    * policy. Returns rows written; never throws (errors are logged). */
  def logServe(
    clientId: UUID,
    items: List[String],
    listName: Option[String],
    country: Option[String],
    language: Option[String],
    propensity: Double = 1.0
  ): Either[Throwable, Int] = {
    if (items.isEmpty) return Right(0)
    val ln = listName.map(_.trim).filter(_.nonEmpty)
    val co = country.map(_.trim).filter(_.nonEmpty)
    val la = language.map(_.trim).filter(_.nonEmpty)
    val rows: List[Row] = items.zipWithIndex.map { case (articleId, pos) =>
      (clientId, articleId, pos, ln, co, la, propensity)
    }
    val sql =
      """INSERT INTO feed_serves
           (client_id, article_id, position, list_name, country, language, propensity)
         VALUES (?,?,?,?,?,?,?)"""
    try
      Right(Update[Row](sql).updateMany(rows).transact(transactor).unsafeRunSync())
    catch {
      case e: Exception =>
        logger.warn(s"Failed to log feed serve (${rows.size} items) for client=$clientId: ${e.getMessage}")
        Left(e)
    }
  }
}
