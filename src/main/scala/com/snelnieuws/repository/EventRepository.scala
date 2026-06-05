package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{UserEvent, UserEventInput}
import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import doobie.postgres.implicits._
import io.circe.Json
import org.slf4j.LoggerFactory

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID
import scala.util.Try

/** Write path for first-party engagement events (POST /v3/events → user_events).
  * Batched insert; defensive about input since the body is client-supplied. */
class EventRepository(provideTransactor: => HikariTransactor[IO]) {

  private val logger = LoggerFactory.getLogger(classOf[EventRepository])
  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  private type Row =
    (UUID, String, Option[String], Option[Long], Option[Int],
     Option[String], Option[String], Option[String], Option[OffsetDateTime],
     Option[String], Option[String], Option[String])

  private def propsJson(p: Option[Map[String, String]]): Option[String] =
    p.map(_.filter { case (k, v) => k.trim.nonEmpty && v.trim.nonEmpty })
      .filter(_.nonEmpty)
      .map(m => Json.fromFields(m.map { case (k, v) => k -> Json.fromString(v) }).noSpaces)

  private def parseTs(s: Option[String]): Option[OffsetDateTime] =
    s.map(_.trim).filter(_.nonEmpty).flatMap { v =>
      Try(OffsetDateTime.parse(v)).toOption
        .orElse(Try(Instant.parse(v).atOffset(ZoneOffset.UTC)).toOption)
    }

  private def clip(s: Option[String], max: Int): Option[String] =
    s.map(_.trim).filter(_.nonEmpty).map(v => if (v.length > max) v.take(max) else v)

  /** Insert a batch, dropping events whose `type` isn't in
    * `UserEvent.AllowedTypes`. Returns the number of rows actually written. */
  def insertBatch(clientId: UUID, events: List[UserEventInput]): Either[Throwable, Int] = {
    val rows: List[Row] = events
      .filter(e => UserEvent.AllowedTypes.contains(e.`type`))
      .map { e =>
        (
          clientId,
          e.`type`,
          clip(e.articleId, 64),
          e.dwellMs.filter(_ >= 0).map(d => math.min(d, 86400000L)), // cap at 24h
          e.position.filter(_ >= 0),
          clip(e.list, 64),
          clip(e.country, 8),
          clip(e.language, 8),
          parseTs(e.ts),
          clip(e.category, 64),
          clip(e.source, 128),
          propsJson(e.props)
        )
      }
    if (rows.isEmpty) return Right(0)

    val sql =
      """INSERT INTO user_events
           (client_id, event_type, article_id, dwell_ms, position, list_name, country, language, event_ts,
            category, source, props)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb)"""
    try
      Right(Update[Row](sql).updateMany(rows).transact(transactor).unsafeRunSync())
    catch {
      case e: Exception =>
        logger.error(s"Failed to insert ${rows.size} user_events for client=$clientId: ${e.getMessage}", e)
        Left(e)
    }
  }
}
