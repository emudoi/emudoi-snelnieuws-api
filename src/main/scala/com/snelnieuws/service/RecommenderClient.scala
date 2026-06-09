package com.snelnieuws.service

import io.circe.Json
import io.circe.parser.{parse => circeParse}
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration => JDuration}

/** Thin HTTP client over emudoi-snelnieuws-recommender's POST /rank
  * (recommender Phase-5). Given a device and a candidate slate, returns the
  * public article ids in the recommender's preferred order.
  *
  * Strictly best-effort and tightly time-boxed: the feed must never block on
  * the recommender. Any error (timeout, non-2xx, parse failure, service down)
  * returns None and the caller keeps its own recency/trending ordering. Uses
  * java.net.http (already on the classpath) — no new dependency. */
class RecommenderClient(baseUrl: String) {

  private val log = LoggerFactory.getLogger(classOf[RecommenderClient])

  private val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(1)).build()

  private val readTimeout: JDuration = JDuration.ofSeconds(2)
  private val base: String = baseUrl.stripSuffix("/")

  /** Returns the candidate ids reordered by the recommender, or None on any
    * failure (caller falls back to its existing order). */
  def rank(clientId: String, candidateIds: List[String]): Option[List[String]] = {
    if (candidateIds.isEmpty) return Some(Nil)
    val body = Json
      .obj(
        "client_id"             -> Json.fromString(clientId),
        "candidate_article_ids" -> Json.fromValues(candidateIds.map(Json.fromString))
      )
      .noSpaces
    try {
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(s"$base/rank"))
        .timeout(readTimeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() / 100 != 2) {
        log.warn(s"recommender /rank responded ${resp.statusCode()}; keeping default order")
        None
      } else parseRanked(resp.body())
    } catch {
      case e: Exception =>
        log.warn(s"recommender /rank failed (${e.getClass.getSimpleName}); keeping default order")
        None
    }
  }

  private def parseRanked(body: String): Option[List[String]] =
    circeParse(body).toOption.flatMap { json =>
      json.hcursor
        .downField("ranked")
        .as[List[Json]]
        .toOption
        .map(_.flatMap(_.hcursor.downField("article_id").as[String].toOption))
    }.filter(_.nonEmpty)
}
