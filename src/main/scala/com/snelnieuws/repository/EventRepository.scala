package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{ArticleStamp, UserEvent, UserEventInput}
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
  * Batched insert; defensive about input since the body is client-supplied.
  *
  * Article-bearing events are enriched server-side at write time from the live
  * catalog (recommender Phase-0, Option B): title + url are stamped on, and the
  * feature snapshot (category/source/language/country) is taken from the
  * article row rather than trusting the client. This makes each event
  * self-describing so the recommender can reconstruct article features even
  * after the 72h `articles` cleanup, and join back to the ingestion store by
  * title. Articles already purged simply aren't enriched — the client-sent
  * values (if any) are kept as a fallback. */
class EventRepository(
  provideTransactor: => HikariTransactor[IO],
  articleRepository: ArticleRepository,
  eulangArticleRepository: ArticleRepository
) {

  private val logger = LoggerFactory.getLogger(classOf[EventRepository])
  private lazy val transactor: HikariTransactor[IO] = provideTransactor

  private type Row =
    (UUID, String, Option[String], Option[Long], Option[Int],
     Option[String], Option[String], Option[String], Option[OffsetDateTime],
     Option[String], Option[String], Option[String], Option[String], Option[String])

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

  /** Decode the public article id as served: "e123" → (eulang=true, 123);
    * "123" → (false, 123). Mirrors ArticleService.decodePublicId, inlined here
    * to avoid a service-layer dependency from the repository. */
  private def decodeId(public: String): Option[(Boolean, Long)] = {
    val (eulang, digits) = if (public.startsWith("e")) (true, public.drop(1)) else (false, public)
    Try(digits.toLong).toOption.map((eulang, _))
  }

  /** Look up the catalog snapshot for every article referenced in the batch,
    * keyed by the public id string so enrichment is a direct map lookup. A
    * failed lookup degrades to no enrichment rather than failing the insert. */
  private def stampsFor(events: List[UserEventInput]): Map[String, ArticleStamp] = {
    val decoded: List[(String, Boolean, Long)] =
      events.flatMap { e =>
        e.articleId.map(_.trim).filter(_.nonEmpty).flatMap { id =>
          decodeId(id).map { case (eu, raw) => (id, eu, raw) }
        }
      }
    if (decoded.isEmpty) return Map.empty

    val mainIds   = decoded.collect { case (_, false, raw) => raw }.distinct
    val eulangIds = decoded.collect { case (_, true, raw)  => raw }.distinct
    val mainStamps   = articleRepository.findStampsByIds(mainIds).getOrElse(Map.empty)
    val eulangStamps = eulangArticleRepository.findStampsByIds(eulangIds).getOrElse(Map.empty)

    decoded.flatMap { case (pub, eu, raw) =>
      (if (eu) eulangStamps else mainStamps).get(raw).map(pub -> _)
    }.toMap
  }

  /** Insert a batch, dropping events whose `type` isn't in
    * `UserEvent.AllowedTypes`. Returns the number of rows actually written. */
  def insertBatch(clientId: UUID, events: List[UserEventInput]): Either[Throwable, Int] = {
    val accepted = events.filter(e => UserEvent.AllowedTypes.contains(e.`type`))
    val stamps   = stampsFor(accepted)

    val rows: List[Row] = accepted.map { e =>
      val stamp = e.articleId.map(_.trim).filter(_.nonEmpty).flatMap(stamps.get)
      // Server-side snapshot wins when the catalog still has the article;
      // client-sent values are the fallback for already-purged articles.
      (
        clientId,
        e.`type`,
        clip(e.articleId, 64),
        e.dwellMs.filter(_ >= 0).map(d => math.min(d, 86400000L)), // cap at 24h
        e.position.filter(_ >= 0),
        clip(e.list, 64),
        stamp.flatMap(_.country).orElse(clip(e.country, 8)),
        stamp.map(_.language).orElse(clip(e.language, 8)),
        parseTs(e.ts),
        stamp.flatMap(_.category).orElse(clip(e.category, 64)),
        stamp.flatMap(_.source).map(_.take(128)).orElse(clip(e.source, 128)),
        propsJson(e.props),
        stamp.map(_.title.take(500)),
        stamp.map(_.url.take(1000))
      )
    }
    if (rows.isEmpty) return Right(0)

    val sql =
      """INSERT INTO user_events
           (client_id, event_type, article_id, dwell_ms, position, list_name, country, language, event_ts,
            category, source, props, title, url)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)"""
    try
      Right(Update[Row](sql).updateMany(rows).transact(transactor).unsafeRunSync())
    catch {
      case e: Exception =>
        logger.error(s"Failed to insert ${rows.size} user_events for client=$clientId: ${e.getMessage}", e)
        Left(e)
    }
  }
}
