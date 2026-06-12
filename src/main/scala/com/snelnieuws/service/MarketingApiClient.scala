package com.snelnieuws.service

import io.circe.parser.{parse => circeParse}
import org.slf4j.LoggerFactory

import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration => JDuration}

/** One completed short video from litikai-marketing-api's GET /api/videos.
  * `id` is the marketing `top_news_videos` row id; `videoPath` is the
  * (cluster-internal) MinIO URL we never expose to clients — the app streams
  * via our own /v3/videos/:id/stream proxy instead. `title` is a best-effort
  * human label (description, else the first clause of the anchor text). */
case class MarketingVideo(
  id:          Long,
  title:       String,
  durationSec: Option[Double],
  variant:     String
)

class MarketingApiError(val status: Int, val body: String)
  extends RuntimeException(s"marketing-api responded $status: ${body.take(200)}")

/** Thin HTTP client over litikai-marketing-api's video library
  * (marketing.litikai.com). Uses java.net.http (already on the classpath via
  * IngestionApiClient / ImageCacheService) — no new dependency. 5s connect +
  * 30s read timeout (video bytes are larger than JSON). X-API-Key auth.
  *
  * Two methods:
  *   - listCompletedVideos: GET /api/videos → the catalogue (completed only).
  *   - streamVideo: GET /api/videos/:id/download → the raw MP4 InputStream,
  *     forwarding the caller's Range header so native players can seek.
  */
class MarketingApiClient(baseUrl: String, apiKey: String) {

  private val log = LoggerFactory.getLogger(classOf[MarketingApiClient])

  private val client: HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(JDuration.ofSeconds(5))
    .build()

  private val jsonReadTimeout: JDuration   = JDuration.ofSeconds(10)
  private val streamReadTimeout: JDuration = JDuration.ofSeconds(30)
  private val baseTrimmed: String          = baseUrl.stripSuffix("/")

  /** Fetch up to `limit` newest completed videos. Pending/failed rows and
    * rows with no rendered file are filtered out. */
  def listCompletedVideos(limit: Int = 200, offset: Int = 0): Either[Throwable, List[MarketingVideo]] =
    if (apiKey.isEmpty)
      Left(new IllegalStateException("marketing-api apiKey not configured"))
    else
      try {
        val req = HttpRequest
          .newBuilder()
          .uri(URI.create(s"$baseTrimmed/api/videos?offset=$offset&limit=$limit"))
          .timeout(jsonReadTimeout)
          .header("X-API-Key", apiKey)
          .GET()
          .build()
        val resp   = client.send(req, HttpResponse.BodyHandlers.ofString())
        val status = resp.statusCode()
        if (status / 100 == 2) decodeList(resp.body())
        else {
          log.warn(s"marketing-api GET /api/videos returned $status: ${resp.body().take(200)}")
          Left(new MarketingApiError(status, resp.body()))
        }
      } catch {
        case e: Exception =>
          log.warn(s"marketing-api GET /api/videos failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
          Left(e)
      }

  /** Open a streaming connection to the MP4 for `id`, forwarding `range` when
    * present. Returns the live HttpResponse[InputStream] so the servlet can
    * pass through status (200/206) + content headers and pipe the body. */
  def streamVideo(id: Long, range: Option[String]): Either[Throwable, HttpResponse[InputStream]] =
    if (apiKey.isEmpty)
      Left(new IllegalStateException("marketing-api apiKey not configured"))
    else
      try {
        val builder = HttpRequest
          .newBuilder()
          .uri(URI.create(s"$baseTrimmed/api/videos/$id/download"))
          .timeout(streamReadTimeout)
          .header("X-API-Key", apiKey)
        range.foreach(r => builder.header("Range", r))
        val resp   = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        val status = resp.statusCode()
        if (status / 100 == 2) Right(resp)
        else {
          // Drain so the connection can be reused; the body is an error page.
          try resp.body().close()
          catch { case _: Exception => () }
          log.warn(s"marketing-api download id=$id returned $status")
          Left(new MarketingApiError(status, s"download id=$id status=$status"))
        }
      } catch {
        case e: Exception =>
          log.warn(s"marketing-api download id=$id failed: ${e.getClass.getSimpleName}: ${e.getMessage}")
          Left(e)
      }

  private def decodeList(raw: String): Either[Throwable, List[MarketingVideo]] =
    circeParse(raw).left.map(identity[Throwable]).flatMap { json =>
      json.hcursor.downField("items").as[List[io.circe.Json]]
        .left.map(identity[Throwable])
        .map(_.flatMap { item =>
          val c       = item.hcursor
          val status  = c.downField("status").as[String].toOption.getOrElse("")
          val hasFile = c.downField("videoPath").as[String].toOption.exists(_.nonEmpty)
          if (status != "completed" || !hasFile) None
          else
            c.downField("id").as[Long].toOption.map { id =>
              val desc  = c.downField("description").as[String].toOption.filter(_.nonEmpty)
              val text  = c.downField("text").as[String].toOption.getOrElse("")
              val title = desc.getOrElse(text.split('•').headOption.map(_.trim).getOrElse("")).take(120)
              MarketingVideo(
                id          = id,
                title       = title,
                durationSec = c.downField("durationSec").as[Double].toOption,
                variant     = c.downField("variant").as[String].toOption.getOrElse("")
              )
            }
        })
    }
}
