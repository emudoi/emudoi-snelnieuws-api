package com.snelnieuws.service

import io.circe.parser.{parse => circeParse}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration => JDuration}

/** One semantic kNN match handed back by the ingestion-api bridge.
  * Score is the cosine similarity from Milvus (E5 vectors are
  * L2-normalised so cosine == dot product). */
case class SemanticMatch(url: String, score: Double, articleId: Long)

object SemanticMatch {
  // Tolerant decoder — the ingestion-api response uses snake_case
  // `article_id` but the Scala field is camelCase.
  implicit val decoder: Decoder[SemanticMatch] = Decoder.instance { c =>
    for {
      url      <- c.downField("url").as[String]
      score    <- c.downField("score").as[Double]
      articleId <- c.downField("article_id").as[Long]
    } yield SemanticMatch(url, score, articleId)
  }
  implicit val encoder: Encoder[SemanticMatch] = deriveEncoder
}

/** One top-story selection from ingestion-api's /api/internal/top-stories/select.
  * `url` is the join key back to the snelnieuws `articles` row (the notification
  * deep-links by snelnieuws id, not ingestion id); `tier` is the selector tier
  * code (1=heuristic/embedding cluster … 4=last-resort). Ordered best-first. */
case class TopStorySelection(url: String, title: String, tier: Short)

class IngestionApiError(val status: Int, val body: String)
  extends RuntimeException(s"ingestion-api responded $status: ${body.take(200)}")

/** Thin HTTP client over the two internal bridge endpoints exposed by
  * emudoi-snelnieuws-ingestion-api (semantic_search/ingestion_api_tasks.txt §7).
  *
  * Uses java.net.http (already on the classpath via ImageCacheService /
  * ImageSlowDownloader). No sttp/http4s dependency added. 5s connect
  * timeout + 10s per-request read timeout per the spec.
  *
  * Both methods return Either[Throwable, _] — a Left can be either an
  * IngestionApiError (server returned non-2xx) or any java.net.http
  * exception (timeout, connection refused, etc.). The calling service
  * decides whether to fall back or surface.
  */
class IngestionApiClient(
  baseUrl: String,
  apiKey: String,
  // Separate key for the internal top-story selection endpoint
  // (ingestion-api/ingestion-internal-key) — distinct from the
  // semantic-search-key above. Optional so existing test harnesses
  // construct unchanged; selectTopStories returns Left if it's empty.
  internalApiKey: String = ""
) {

  private val log = LoggerFactory.getLogger(classOf[IngestionApiClient])

  private val client: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(JDuration.ofSeconds(5))
    .build()

  private val readTimeout: JDuration = JDuration.ofSeconds(10)

  private val baseTrimmed: String = baseUrl.stripSuffix("/")

  /** Embed a short user query. Returns the 1024-dim vector. */
  def embedQuery(text: String): Either[Throwable, Array[Float]] = {
    val body = Json
      .obj("text" -> Json.fromString(text))
      .noSpaces
    postJson("/api/internal/embeddings/query", body).flatMap { resp =>
      decodeEmbedResponse(resp)
    }
  }

  /** Top-K kNN search against Milvus. `limit` ≤ 50 and `minScore` ≥ 0.70
    * are the defaults; the bridge respects whatever we send. */
  def searchSemantic(
    embedding: Array[Float],
    language: Option[String],
    country: Option[String],
    limit: Int = 50,
    minScore: Double = 0.70,
    // source = "eulang" routes the bridge to the eulang Milvus collection +
    // eulang_articles hydrate; None keeps the default snelnieuws collection.
    source: Option[String] = None
  ): Either[Throwable, List[SemanticMatch]] = {
    val fields = scala.collection.mutable.ListBuffer[(String, Json)](
      "embedding" -> Json.fromValues(embedding.toSeq.map(f => Json.fromDoubleOrNull(f.toDouble))),
      "limit"     -> Json.fromInt(limit),
      "min_score" -> Json.fromDoubleOrNull(minScore)
    )
    language.foreach(l => fields += "language" -> Json.fromString(l))
    country.foreach(c => fields += "country" -> Json.fromString(c))
    source.foreach(s => fields += "source" -> Json.fromString(s))
    val body = Json.fromFields(fields.toList).noSpaces
    postJson("/api/internal/search/semantic", body).flatMap { resp =>
      decodeSearchResponse(resp)
    }
  }

  /** Call ingestion-api's POST /api/internal/top-stories/select — the SAME
    * top-story selector marketing-api uses (server-side window fetch + Milvus
    * embedding clustering Tier 1a + the heuristic tier ladder). Returns the
    * ordered selections (best-first) for the requested language. A 503
    * (`no_fresh_top_story`) maps to an empty list, not an error. Uses the
    * internal key; returns Left if that key wasn't configured. */
  def selectTopStories(
    language: String,
    excludeArticleIds: List[Long] = Nil
  ): Either[Throwable, List[TopStorySelection]] = {
    if (internalApiKey.isEmpty)
      return Left(new IllegalStateException("ingestion internalApiKey not configured"))
    val body = Json
      .obj(
        "language"            -> Json.fromString(language),
        "require_image"       -> Json.fromBoolean(false),
        "exclude_article_ids" -> Json.fromValues(excludeArticleIds.map(Json.fromLong))
      )
      .noSpaces
    try {
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(s"$baseTrimmed/api/internal/top-stories/select"))
        .timeout(readTimeout)
        .header("Content-Type", "application/json")
        .header("X-API-Key", internalApiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp   = client.send(req, HttpResponse.BodyHandlers.ofString())
      val status = resp.statusCode()
      if (status == 503) Right(Nil) // no_fresh_top_story — normal "no work"
      else if (status / 100 == 2) decodeSelectResponse(resp.body())
      else {
        log.warn(s"ingestion-api top-stories/select returned $status: ${resp.body().take(200)}")
        Left(new IngestionApiError(status, resp.body()))
      }
    } catch {
      case e: Exception =>
        log.warn(s"ingestion-api top-stories/select failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        Left(e)
    }
  }

  private def decodeSelectResponse(raw: String): Either[Throwable, List[TopStorySelection]] =
    circeParse(raw).left.map(identity[Throwable]).flatMap { json =>
      json.hcursor.downField("selections").as[List[Json]]
        .left.map(identity[Throwable])
        .map(_.flatMap { sel =>
          val c = sel.hcursor
          for {
            url   <- c.downField("url").as[String].toOption
            title <- c.downField("title").as[String].toOption
            tier  <- c.downField("tier").as[Int].toOption
          } yield TopStorySelection(url, title, tier.toShort)
        })
    }

  // ────────────────────────────── internals ──────────────────────────

  private def postJson(path: String, body: String): Either[Throwable, String] =
    try {
      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(s"$baseTrimmed$path"))
        .timeout(readTimeout)
        .header("Content-Type", "application/json")
        .header("X-API-Key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      val status = resp.statusCode()
      if (status / 100 == 2) Right(resp.body())
      else {
        log.warn(s"ingestion-api $path returned $status: ${resp.body().take(200)}")
        Left(new IngestionApiError(status, resp.body()))
      }
    } catch {
      case e: Exception =>
        log.warn(s"ingestion-api $path failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
        Left(e)
    }

  private def decodeEmbedResponse(raw: String): Either[Throwable, Array[Float]] =
    circeParse(raw).left.map(identity[Throwable]).flatMap { json =>
      json.hcursor.downField("embedding").as[List[Double]]
        .left.map(identity[Throwable])
        .map(_.iterator.map(_.toFloat).toArray)
    }

  private def decodeSearchResponse(raw: String): Either[Throwable, List[SemanticMatch]] =
    circeParse(raw).left.map(identity[Throwable]).flatMap { json =>
      json.hcursor.downField("matches").as[List[SemanticMatch]]
        .left.map(identity[Throwable])
    }
}
