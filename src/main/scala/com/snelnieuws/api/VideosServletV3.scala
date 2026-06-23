package com.snelnieuws.api

import com.snelnieuws.repository.AppClientRepository
import com.snelnieuws.service.{MarketingApiClient, VideoFeedService}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.Try

/** One video in the reel response. `stream_url` points back at THIS service's
  * open proxy (`/v3/videos/:id/stream`) — never the cluster-internal MinIO
  * path or the marketing key. `next_cursor` is always null in the feed: like
  * the personalised article feed, the per-client served-set IS the pagination
  * state, so the client just re-calls /feed to get the next unseen page. */
case class VideoItemV3(
  id:           String,
  stream_url:   String,
  duration_sec: Option[Double],
  title:        String,
  variant:      String,
  // Source article publisher link + image (local feed only; null for the
  // legacy marketing source). `url` powers tap-to-article in the app.
  url:          Option[String],
  url_to_image: Option[String]
)

case class VideoFeedResponseV3(
  videos:      List[VideoItemV3],
  next_cursor: Option[String],
  has_more:    Boolean
)

/** Mounted at the `/v3/videos/` prefix (wins over the `/v3/` mount by
  * longest-prefix).
  *
  *   - GET /v3/videos/feed        -> per-client video reel page. Same
  *     X-Client / X-Client-Key gate the article feed uses (the client id
  *     drives the served-video-id dedup), validated inline.
  *   - GET /v3/videos/:id/stream  -> OPEN MP4 proxy (no gate, like
  *     ImageServlet) so native players fetch it header-free and HTTP Range
  *     seeking is cheap. Pipes the marketing-api download through, forwarding
  *     Range. The marketing key stays server-side.
  */
class VideosServletV3(
  videoFeedService: VideoFeedService,
  marketingClient: MarketingApiClient,
  videoRepository: com.snelnieuws.repository.VideoRepository,
  appClientRepository: AppClientRepository,
  publicBaseUrl: String
) extends ScalatraServlet
    with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  private val logger = LoggerFactory.getLogger(classOf[VideosServletV3])

  private val ClientHeaderRe = """^(ios|android)/[^\s]+$""".r
  private val DefaultLimit   = 10
  private val MaxLimit       = 30
  private val baseTrimmed    = publicBaseUrl.stripSuffix("/")

  error {
    case e: Exception =>
      logger.error(s"Unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "Internal server error"))
  }

  private def clientIdFromHeader(): Option[UUID] = {
    val xClient = Option(request.getHeader("X-Client")).map(_.trim).getOrElse("")
    if (!ClientHeaderRe.pattern.matcher(xClient).matches()) None
    else Try(UUID.fromString(Option(request.getHeader("X-Client-Key")).map(_.trim).getOrElse(""))).toOption
  }

  // ───────────────────────────── Reel feed ──────────────────────────────

  get("/feed") {
    contentType = formats("json")
    clientIdFromHeader() match {
      case None =>
        Unauthorized(Map("error" -> "missing or invalid X-Client / X-Client-Key"))
      case Some(cid) =>
        appClientRepository.isActive(cid) match {
          case Right(false) =>
            Unauthorized(Map("error" -> "unknown or revoked X-Client-Key"))
          case Left(e) =>
            logger.error(s"app_client lookup failed for $cid: ${e.getMessage}", e)
            InternalServerError(Map("error" -> "client lookup failed"))
          case Right(true) =>
            appClientRepository.markSeen(cid)
            val limit = params.get("limit")
              .flatMap(s => Try(s.toInt).toOption)
              .map(n => math.min(math.max(n, 1), MaxLimit))
              .getOrElse(DefaultLimit)
            videoFeedService.fetch(cid, limit) match {
              case Right((videos, hasMore)) =>
                VideoFeedResponseV3(
                  videos = videos.map(toItem),
                  next_cursor = None,
                  has_more    = hasMore
                )
              case Left(e) =>
                logger.warn(s"video feed fetch failed for $cid: ${e.getMessage}")
                InternalServerError(Map("error" -> "video service temporarily unavailable"))
            }
        }
    }
  }

  // ───────────────── Single video by id (shared links) ──────────────────
  // The video analogue of articleV3(id): resolves a shared video id to its
  // playable stream + source-article link. Local table only (the legacy
  // marketing source has no persistent by-id record). Open — no gate — so a
  // shared link opens header-free, like the article deep link.
  get("/:id") {
    contentType = formats("json")
    Try(params("id").toLong).toOption match {
      case None => NotFound(Map("error" -> "invalid id"))
      case Some(id) =>
        videoRepository.findById(id) match {
          case Right(Some(v)) =>
            VideoItemV3(
              id           = v.id.toString,
              stream_url   = v.streamUrl,
              duration_sec = v.durationSec,
              title        = v.title,
              variant      = v.variant.getOrElse(""),
              url          = v.url,
              url_to_image = v.urlToImage.map(absolutize)
            )
          case Right(None) => NotFound(Map("error" -> "video not found"))
          case Left(e) =>
            logger.warn(s"video by-id $id failed: ${e.getMessage}")
            InternalServerError(Map("error" -> "video lookup failed"))
        }
    }
  }

  /** Map a unified FeedVideo to the wire item. Local videos carry their CDN
    * stream_url directly; marketing videos fall back to the proxy route. */
  private def toItem(v: com.snelnieuws.service.FeedVideo): VideoItemV3 =
    VideoItemV3(
      id           = v.id.toString,
      stream_url   = v.streamUrl.getOrElse(s"$baseTrimmed/v3/videos/${v.id}/stream"),
      duration_sec = v.durationSec,
      title        = v.title,
      variant      = v.variant,
      url          = v.url,
      url_to_image = v.urlToImage.map(absolutize)
    )

  /** Relative /v2/images paths → absolute (so the app's image loader can fetch
    * them directly), matching how article image URLs are served. */
  private def absolutize(u: String): String =
    if (u.startsWith("/")) s"$baseTrimmed$u" else u

  // ──────────────────────────── MP4 proxy ───────────────────────────────

  get("/:id/stream") {
    Try(params("id").toLong).toOption match {
      case None =>
        NotFound(Map("error" -> "invalid id"))
      case Some(id) =>
        marketingClient.downloadFull(id) match {
          case Left(e) =>
            logger.warn(s"video stream id=$id failed: ${e.getMessage}")
            NotFound(Map("error" -> "video unavailable"))
          case Right(bytes) =>
            val total = bytes.length
            response.setHeader("Content-Type", "video/mp4")
            response.setHeader("Accept-Ranges", "bytes")
            response.setHeader("Cache-Control", "public, max-age=86400")
            val out = response.getOutputStream
            // Honour HTTP Range (iOS AVPlayer requires correct 206 behaviour).
            parseRange(Option(request.getHeader("Range")), total) match {
              case Some((start, end)) =>
                val len = end - start + 1
                response.setStatus(206)
                response.setHeader("Content-Range", s"bytes $start-$end/$total")
                response.setHeader("Content-Length", len.toString)
                out.write(bytes, start, len)
              case None =>
                response.setStatus(200)
                response.setHeader("Content-Length", total.toString)
                out.write(bytes)
            }
            out.flush()
            ()
        }
    }
  }

  /** Parse a single-range `bytes=start-end` (or `bytes=start-`, `bytes=-N`)
    * header against `total`. Returns the inclusive (start, end) clamped to the
    * file, or None when absent / multi-range / unsatisfiable. */
  private def parseRange(header: Option[String], total: Int): Option[(Int, Int)] =
    header.map(_.trim).filter(_.startsWith("bytes=")).flatMap { h =>
      val spec = h.stripPrefix("bytes=")
      if (spec.contains(",")) None // multi-range unsupported
      else
        Try {
          if (spec.startsWith("-")) {
            val n     = spec.drop(1).toInt
            val start = math.max(0, total - n)
            (start, total - 1)
          } else {
            val parts = spec.split("-", 2)
            val start = parts(0).toInt
            val end =
              if (parts.length > 1 && parts(1).nonEmpty) math.min(parts(1).toInt, total - 1)
              else total - 1
            (start, end)
          }
        }.toOption.filter { case (s, e) => s >= 0 && e >= s && s < total }
    }
}
