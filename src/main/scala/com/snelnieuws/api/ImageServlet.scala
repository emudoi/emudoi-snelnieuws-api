package com.snelnieuws.api

import com.snelnieuws.service.ImageCacheService
import org.scalatra._
import org.slf4j.LoggerFactory

import java.nio.file.NoSuchFileException

/** Public image-serving endpoint. Open route — no X-Client / X-Client-Key
  * gate, so iOS's plain `AsyncImage(url: URL(string: ...))` works without
  * header plumbing. The servlet is mounted at /v2/images (with a wildcard
  * suffix) ahead of the v1 catch-all and the v2 NewsServletV2 (the longer
  * prefix wins under the Servlet API spec, regardless of declaration order).
  *
  * Two routes:
  *   - GET /_fallback           → bundled SnelNieuws logo from classpath.
  *   - GET /aa/bb/sha.ext       → bytes from NFS, with the fallback served
  *                                in place when the file isn't on disk yet.
  *
  * Content addressing means the URL never needs to change: the consumer
  * writes the relative URL (under /v2/images) onto articles.url_to_image
  * at upsert time, and the worker fills in the bytes asynchronously. iOS
  * sees the fallback for up to ~60 seconds (URLCache TTL on the miss
  * response), then the same URL starts returning the real image.
  */
class ImageServlet(imageCacheService: ImageCacheService) extends ScalatraServlet {

  import ImageServlet._

  private val logger = LoggerFactory.getLogger(classOf[ImageServlet])

  // Cached at first access. The bundled file ships in src/main/resources/
  // so getResourceAsStream is the standard classpath read path. Holding
  // the bytes in memory is fine — the PNG is ~1.5 MB.
  private lazy val fallbackBytes: Array[Byte] = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(FallbackResource))
    stream match {
      case Some(s) =>
        try s.readAllBytes()
        finally s.close()
      case None =>
        logger.error(
          s"Fallback image $FallbackResource not on classpath — image misses will return 404 instead of the logo."
        )
        Array.emptyByteArray
    }
  }

  error {
    case e: Exception =>
      logger.error(s"ImageServlet unhandled error: ${e.getMessage}", e)
      InternalServerError(Map("error" -> "internal error"))
  }

  // Single splat route. Specific paths (like _fallback) are handled
  // inside the body rather than as separate get() blocks because Scalatra
  // route precedence with overlapping wildcards is order-sensitive in
  // ways that have surprised us in tests; a single dispatch keeps the
  // behaviour boring and explicit.
  get("/*") {
    val rel = Option(params("splat")).getOrElse("")
    if (rel.isEmpty) {
      BadRequest(Map("error" -> "missing image path"))
    } else if (rel == "_fallback") {
      serveFallback(longCache = true)
    } else if (params.get("w").exists(_.forall(_.isDigit)) && params.get("w").exists(_.nonEmpty)) {
      // Resized thumbnail (push-notification images). Returns a small JPEG,
      // or 404 when the original isn't on disk yet / can't be decoded — we
      // deliberately DON'T serve the ~1.5 MB fallback logo here, so a push
      // image download is always tiny or absent.
      val width = params("w").toInt
      imageCacheService.readResized(rel, width) match {
        case Right((bytes, contentTypeOpt)) =>
          response.setHeader("Content-Type", contentTypeOpt.getOrElse("image/jpeg"))
          response.setHeader("Cache-Control", LongCacheControl)
          response.getOutputStream.write(bytes)
          response.getOutputStream.flush()
          ()
        case Left(_: SecurityException) | Left(_: IllegalArgumentException) =>
          BadRequest(Map("error" -> "invalid image path"))
        case Left(_) =>
          // Not downloaded yet, or undecodable source (WebP/AVIF/HEIC).
          NotFound(Map("error" -> "thumbnail unavailable"))
      }
    } else {
      imageCacheService.readBytes(rel) match {
        case Right((bytes, contentTypeOpt)) =>
          // Set Content-Type via setHeader rather than `contentType =` or
          // `response.setContentType` so Scalatra's render pipeline
          // doesn't append `;charset=utf-8` (wrong for binary payloads).
          response.setHeader("Content-Type", contentTypeOpt.getOrElse("application/octet-stream"))
          response.setHeader("Cache-Control", LongCacheControl)
          response.getOutputStream.write(bytes)
          response.getOutputStream.flush()
          ()
        case Left(_: NoSuchFileException) =>
          // File hasn't been downloaded yet (or was cleaned up). Serve the
          // bundled fallback with a short TTL so the next AsyncImage view
          // re-validates and picks up the real bytes once the worker
          // finishes.
          serveFallback(longCache = false)
        case Left(_: SecurityException) =>
          BadRequest(Map("error" -> "invalid image path"))
        case Left(_: IllegalArgumentException) =>
          BadRequest(Map("error" -> "invalid image path"))
        case Left(e) =>
          logger.error(s"image read failed path=$rel: ${e.getMessage}", e)
          InternalServerError(Map("error" -> "image read failed"))
      }
    }
  }

  private def serveFallback(longCache: Boolean): Any = {
    if (fallbackBytes.isEmpty) {
      // Belt-and-braces — without the bundled asset there's nothing
      // useful to return, so 404 is honest.
      NotFound(Map("error" -> "fallback image unavailable"))
    } else {
      response.setHeader("Content-Type", "image/png")
      response.setHeader(
        "Cache-Control",
        if (longCache) FallbackLongCacheControl else FallbackShortCacheControl
      )
      response.getOutputStream.write(fallbackBytes)
      response.getOutputStream.flush()
      ()
    }
  }
}

object ImageServlet {
  private val FallbackResource = "static/fallback-image.png"

  // Real images are content-addressed, so their bytes never change at a
  // given URL — safe to mark immutable for a year.
  private val LongCacheControl = "public, max-age=31536000, immutable"

  // The /_fallback route is hit explicitly by clients (when the consumer
  // sets url_to_image to the fallback for an empty source URL). One day
  // is plenty — it's the same bundled asset for every install.
  private val FallbackLongCacheControl = "public, max-age=86400"

  // Short TTL on the miss path so iOS's URLCache re-validates within a
  // minute and picks up the real image once the worker finishes the
  // download. Same content-type/length as the long-TTL path; only the
  // freshness window differs.
  private val FallbackShortCacheControl = "public, max-age=60"
}
