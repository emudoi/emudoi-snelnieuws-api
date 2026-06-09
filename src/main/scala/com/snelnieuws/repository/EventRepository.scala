package com.snelnieuws.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snelnieuws.model.{ArticleStamp, UserEvent, UserEventExport, UserEventInput}
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
  eulangArticleRepository: ArticleRepository,
  // Best-effort sink to the `user.events` Kafka topic (recommender Phase-1).
  // No-op by default so the producer ships dark; Components wires the real
  // publisher only when kafka.user-events.enabled is true.
  publish: List[UserEventExport] => Unit = _ => ()
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

    // Resolve the enriched fields once, then project into both the DB row and
    // the Kafka export so the two never drift.
    case class Enriched(
      articleId: Option[String], dwellMs: Option[Long], position: Option[Int],
      listName: Option[String], country: Option[String], language: Option[String],
      ts: Option[OffsetDateTime], category: Option[String], source: Option[String],
      props: Option[String], title: Option[String], url: Option[String], eventType: String
    )

    val enriched: List[Enriched] = accepted.map { e =>
      val stamp = e.articleId.map(_.trim).filter(_.nonEmpty).flatMap(stamps.get)
      // Server-side snapshot wins when the catalog still has the article;
      // client-sent values are the fallback for already-purged articles.
      Enriched(
        articleId = clip(e.articleId, 64),
        dwellMs   = e.dwellMs.filter(_ >= 0).map(d => math.min(d, 86400000L)), // cap at 24h
        position  = e.position.filter(_ >= 0),
        listName  = clip(e.list, 64),
        country   = stamp.flatMap(_.country).orElse(clip(e.country, 8)),
        language  = stamp.map(_.language).orElse(clip(e.language, 8)),
        ts        = parseTs(e.ts),
        category  = stamp.flatMap(_.category).orElse(clip(e.category, 64)),
        source    = stamp.flatMap(_.source).map(_.take(128)).orElse(clip(e.source, 128)),
        props     = propsJson(e.props),
        title     = stamp.map(_.title.take(500)),
        url       = stamp.map(_.url.take(1000)),
        eventType = e.`type`
      )
    }

    val rows: List[Row] = enriched.map { x =>
      (clientId, x.eventType, x.articleId, x.dwellMs, x.position, x.listName, x.country,
       x.language, x.ts, x.category, x.source, x.props, x.title, x.url)
    }
    if (rows.isEmpty) return Right(0)

    val sql =
      """INSERT INTO user_events
           (client_id, event_type, article_id, dwell_ms, position, list_name, country, language, event_ts,
            category, source, props, title, url)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)"""
    try {
      val n = Update[Row](sql).updateMany(rows).transact(transactor).unsafeRunSync()
      // Best-effort publish AFTER the system-of-record write succeeds. No-op
      // when the producer ships dark; never throws (the callback swallows).
      val exports = enriched.map { x =>
        UserEventExport(
          clientId = clientId, eventType = x.eventType, articleId = x.articleId,
          title = x.title, url = x.url, category = x.category, source = x.source,
          language = x.language, country = x.country, position = x.position,
          listName = x.listName, dwellMs = x.dwellMs,
          ts = x.ts.map(_.toString)
        )
      }
      try publish(exports)
      catch { case e: Exception => logger.warn(s"user.events publish skipped: ${e.getMessage}") }
      Right(n)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to insert ${rows.size} user_events for client=$clientId: ${e.getMessage}", e)
        Left(e)
    }
  }
}
