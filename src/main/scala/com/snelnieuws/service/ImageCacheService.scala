package com.snelnieuws.service

import com.snelnieuws.model.{ImageCacheRow, ImageCacheStatus}
import com.snelnieuws.repository.ImageCacheRepository
import org.slf4j.LoggerFactory

import java.awt.image.BufferedImage
import java.awt.{Color, RenderingHints}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.security.MessageDigest
import javax.imageio.stream.MemoryCacheImageOutputStream
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import java.time.{Duration => JDuration, OffsetDateTime}
import scala.util.{Failure, Success, Try}

/** Configuration knobs for the image cache. Mirrors the `images` block in
  * application.conf one-for-one so wiring just reads each value once.
  */
case class ImageCacheConfig(
  rootDir: String,
  downloadTimeoutMs: Long,
  maxBytes: Long,
  userAgent: String,
  maxAttempts: Int,
  retryBackoffMinutes: Long
)

/** Result of a successful download — handed back to the worker so it can
  * also update the article row's url_to_image (already done implicitly via
  * the deterministic pathFor, but the caller may want metadata). */
case class ImageDownloadResult(
  relativePath: String,
  contentType: Option[String],
  sizeBytes: Long
)

/** Closed set of fast-path failure shapes. The worker reads `retryableSlow`
  * to decide whether to hand the URL to the Kafka slow-retry tier; the
  * `reason` token is persisted to image_cache.last_failure_reason so ops
  * can group failures without log-spelunking. */
sealed trait DownloadFailure {
  def reason: String
  def statusCode: Option[Int]
  def retryableSlow: Boolean
}
object DownloadFailure {
  case object Timeout extends DownloadFailure {
    val reason = "timeout"; val statusCode: Option[Int] = None; val retryableSlow = true
  }
  case object ConnectionError extends DownloadFailure {
    val reason = "connection_error"; val statusCode: Option[Int] = None; val retryableSlow = true
  }
  case class Http5xx(code: Int) extends DownloadFailure {
    val reason = "http_5xx"; val statusCode: Option[Int] = Some(code); val retryableSlow = true
  }
  case class Http4xx(code: Int) extends DownloadFailure {
    val reason = "http_4xx"; val statusCode: Option[Int] = Some(code); val retryableSlow = false
  }
  case object Oversize extends DownloadFailure {
    val reason = "oversize"; val statusCode: Option[Int] = None; val retryableSlow = false
  }
  case object UnsupportedScheme extends DownloadFailure {
    val reason = "unsupported_scheme"; val statusCode: Option[Int] = None; val retryableSlow = false
  }
  case object SignedTokenExpired extends DownloadFailure {
    val reason = "signed_token_expired"; val statusCode: Option[Int] = None; val retryableSlow = false
  }
  case object Other extends DownloadFailure {
    val reason = "other"; val statusCode: Option[Int] = None; val retryableSlow = true
  }
}

/** Wraps a fast-path download failure so the worker can pattern-match on
  * the classified shape (and decide whether to enqueue on the slow tier)
  * without re-classifying the raw cause. */
class ClassifiedDownloadException(val cls: DownloadFailure, cause: Throwable)
  extends RuntimeException(Option(cause.getMessage).getOrElse(cls.reason), cause)

/** Pure URL → relative-path math + the side-effecting fetch/read paths.
  *
  * Path scheme: `<aa>/<bb>/<sha256(source_url)><ext>`
  *   - aa, bb are the first 4 hex chars of the digest split into two pairs,
  *     used as fan-out directories so no single dir holds the whole catalog.
  *   - ext is taken from the source URL path when it is a recognised image
  *     extension; otherwise ".bin" (servlet sets Content-Type from the
  *     image_cache row's content_type column anyway).
  *
  * Determinism is the contract: SummarizedArticleConsumer rewrites
  * articles.url_to_image to /v2/images/<pathFor(url)> *before* the worker
  * runs, and the worker (later) writes bytes to that same path. The servlet
  * serves whatever exists at the path, falling back to the bundled logo
  * when nothing is there yet.
  */
class ImageCacheService(
  repository: ImageCacheRepository,
  httpClient: HttpClient,
  config: ImageCacheConfig
) {

  import ImageCacheService._

  private val logger = LoggerFactory.getLogger(classOf[ImageCacheService])

  // Resolved & normalised once so every readBytes path check is a cheap
  // startsWith comparison. Anything that escapes this prefix is rejected.
  private val rootPath: Path = Paths.get(config.rootDir).toAbsolutePath.normalize()

  /** Pure: relative path under rootDir for a given source URL. Never
    * touches disk or DB. Returns the same value for the same URL forever. */
  def pathFor(sourceUrl: String): String = {
    val digest = sha256Hex(sourceUrl)
    val ext    = extensionFor(sourceUrl)
    val a      = digest.substring(0, 2)
    val b      = digest.substring(2, 4)
    s"$a/$b/$digest$ext"
  }

  /** Same as pathFor but with the public-route prefix. Used at write-time
    * by the consumer / create endpoint to populate articles.url_to_image. */
  def relativeUrlFor(sourceUrl: String): String =
    s"/v2/images/${pathFor(sourceUrl)}"

  /** Bypass URL for empty / unusable source URLs. */
  val fallbackRelativeUrl: String = "/v2/images/_fallback"

  /** Idempotent fetch. Returns the relative path on success. Behaviour:
    *
    *  - Row exists and status=downloaded: no-op, returns relative_path.
    *  - Row exists and status=failed but not retry-eligible: returns Left.
    *  - Otherwise: downloads, writes atomically to NFS, upserts the row.
    *
    *  On any download/write failure, the row is upserted as failed so
    *  attempts/last_attempt_at advance and the retry policy can throttle.
    *  The Left is a ClassifiedDownloadException carrying the failure
    *  shape so the caller can decide whether to hand off to the slow tier. */
  def resolveOrFetch(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
    val trimmed = sourceUrl.trim
    if (trimmed.isEmpty) {
      Left(new IllegalArgumentException("source URL is empty"))
    } else {
      repository.findByUrl(trimmed) match {
        case Left(e) => Left(e)
        case Right(Some(row)) if row.status == ImageCacheStatus.Downloaded =>
          Right(
            ImageDownloadResult(
              relativePath = row.relativePath,
              contentType  = row.contentType,
              sizeBytes    = row.sizeBytes.getOrElse(0L)
            )
          )
        case Right(rowOpt) =>
          val tooSoon = rowOpt.exists(r => !isRetryEligible(r))
          if (tooSoon) {
            val msg = s"image cache: not retry-eligible for url=$trimmed (attempts=${rowOpt.map(_.attempts).getOrElse(0)})"
            logger.debug(msg)
            Left(new RetryNotDueException(msg))
          } else {
            download(trimmed)
          }
      }
    }
  }

  /** Returns true if the row should be retried right now. New rows
    * (passed in via Some) get their attempts checked against maxAttempts;
    * the backoff window is enforced from last_attempt_at. */
  def isRetryEligible(row: ImageCacheRow): Boolean = {
    val attemptsOk = row.attempts < config.maxAttempts
    val backoffOk =
      row.lastAttemptAt.isBefore(OffsetDateTime.now().minusMinutes(config.retryBackoffMinutes))
    attemptsOk && backoffOk
  }

  /** Read bytes for a relative path on NFS. Rejects path traversal — the
    * resolved path must canonicalise to a descendant of rootDir. Returns
    * Left(NoSuchFileException) when the file doesn't exist on disk so the
    * servlet can decide to serve the fallback. */
  def readBytes(relativePath: String): Either[Throwable, (Array[Byte], Option[String])] = {
    val safe = canonicalizeUnderRoot(relativePath)
    safe match {
      case Left(e) => Left(e)
      case Right(target) =>
        if (!Files.exists(target)) {
          Left(new java.nio.file.NoSuchFileException(target.toString))
        } else {
          try {
            val bytes = Files.readAllBytes(target)
            // Prefer the recorded content_type from image_cache; fall back
            // to filesystem probe for entries written before that column
            // was populated (e.g. tests that touch the disk directly).
            val ct = repository.findByRelativePath(relativePath) match {
              case Right(Some(r)) => r.contentType
              case _              => Option(Files.probeContentType(target))
            }
            Right((bytes, ct))
          } catch {
            case e: Exception => Left(e)
          }
        }
    }
  }

  /** Read a small JPEG thumbnail (≤ `width` px wide) of a cached image, for
    * use as a push-notification image where the original (up to max-bytes,
    * 10 MB) is far too heavy to download on cellular.
    *
    * The resized variant is cached on NFS next to the original
    * (`<rel>.w<width>.jpg`) so the resize cost is one-time. Returns
    * Left(NoSuchFileException) when the original isn't on disk yet, and
    * Left(UnsupportedImageException) when the bytes can't be decoded by
    * ImageIO (WebP/AVIF/HEIC sources) — callers should then simply omit the
    * image rather than fall back to the full-size original. */
  def readResized(relativePath: String, width: Int): Either[Throwable, (Array[Byte], Option[String])] = {
    val w = width.max(ImageCacheService.MinThumbWidth).min(ImageCacheService.MaxThumbWidth)
    val variantPath = s"$relativePath.w$w.jpg"
    // Serve the cached variant if it already exists.
    canonicalizeUnderRoot(variantPath) match {
      case Right(v) if Files.exists(v) =>
        return (try Right((Files.readAllBytes(v), Some("image/jpeg")))
        catch { case e: Exception => Left(e) })
      case Left(e) => return Left(e)
      case _       => // fall through and generate
    }
    readBytes(relativePath) match {
      case Left(e) => Left(e)
      case Right((bytes, _)) =>
        ImageCacheService.resizeToJpeg(bytes, w) match {
          case Some(jpeg) =>
            try writeAtomic(variantPath, jpeg)
            catch { case e: Exception => logger.warn(s"thumb cache write failed $variantPath: ${e.getMessage}") }
            Right((jpeg, Some("image/jpeg")))
          case None =>
            Left(new ImageCacheService.UnsupportedImageException(relativePath))
        }
    }
  }

  /** Map a Throwable from the fast-path download attempt to its
    * DownloadFailure classification. Used by the worker (after
    * upsertFailed runs) and by tests. */
  def classifyFailure(e: Throwable, sourceUrl: String): DownloadFailure = e match {
    case _: java.net.http.HttpTimeoutException =>
      DownloadFailure.Timeout
    case _: java.net.ConnectException
       | _: java.net.UnknownHostException
       | _: javax.net.ssl.SSLException
       | _: java.net.SocketException =>
      DownloadFailure.ConnectionError
    case _: java.lang.IllegalArgumentException =>
      DownloadFailure.UnsupportedScheme
    case re: RuntimeException if re.getMessage != null &&
         re.getMessage.startsWith("image exceeds max-bytes") =>
      DownloadFailure.Oversize
    case re: RuntimeException if re.getMessage != null &&
         re.getMessage.startsWith("non-2xx status ") =>
      parseStatusFromMsg(re.getMessage) match {
        case Some(c) if c >= 500 => DownloadFailure.Http5xx(c)
        case Some(c) if c == 401 || c == 403 || c == 410 =>
          if (looksSigned(sourceUrl)) DownloadFailure.SignedTokenExpired
          else DownloadFailure.Http4xx(c)
        case Some(c) if c >= 400 => DownloadFailure.Http4xx(c)
        case _                   => DownloadFailure.Other
      }
    case _ => DownloadFailure.Other
  }

  /** Atomically replace the bytes at the given relative path. Package-
    * visible so the slow-tier downloader can reuse the exact same write
    * path (NFS atomic-move + traversal guard) without duplicating it. */
  private[service] def writeAtomicPublic(relativePath: String, bytes: Array[Byte]): Unit =
    writeAtomic(relativePath, bytes)

  // ───────────────────────────── internals ─────────────────────────────

  private def download(sourceUrl: String): Either[Throwable, ImageDownloadResult] = {
    val relPath = pathFor(sourceUrl)
    Try {
      // Short-circuit unsupported schemes (data:, blob:, ftp:, …) BEFORE
      // any network attempt. Without this, the underlying URI parse / send
      // either throws something the classifier can't tell apart from a
      // genuine 4xx, or burns retries that can never succeed.
      val scheme = schemeOf(sourceUrl)
      if (!SupportedSchemes.contains(scheme)) {
        throw new IllegalArgumentException(s"unsupported scheme '$scheme' for $sourceUrl")
      }

      val req = HttpRequest
        .newBuilder()
        .uri(URI.create(sourceUrl))
        .timeout(JDuration.ofMillis(config.downloadTimeoutMs))
        .header("User-Agent", config.userAgent)
        .GET()
        .build()

      val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
      if (resp.statusCode() / 100 != 2) {
        throw new RuntimeException(
          s"non-2xx status ${resp.statusCode()} fetching $sourceUrl"
        )
      }
      val bytes = resp.body()
      if (bytes.length.toLong > config.maxBytes) {
        throw new RuntimeException(
          s"image exceeds max-bytes (${bytes.length} > ${config.maxBytes}) at $sourceUrl"
        )
      }
      val contentType = Option(resp.headers().firstValue("Content-Type").orElse(null))
        .map(_.split(";")(0).trim)
        .filter(_.nonEmpty)

      writeAtomic(relPath, bytes)
      ImageDownloadResult(
        relativePath = relPath,
        contentType  = contentType,
        sizeBytes    = bytes.length.toLong
      )
    } match {
      case Success(result) =>
        repository.upsertDownloaded(
          sourceUrl    = sourceUrl,
          relativePath = result.relativePath,
          contentType  = result.contentType,
          sizeBytes    = result.sizeBytes
        ) match {
          case Right(_) => Right(result)
          case Left(e) =>
            // The bytes are on disk, but the bookkeeping write failed.
            // Don't unwind the file — a later retry will simply upsert
            // the row and the servlet will keep serving the bytes.
            logger.warn(
              s"image_cache row upsert failed after successful download url=$sourceUrl: ${e.getMessage}"
            )
            Right(result)
        }
      case Failure(e) =>
        val cls = classifyFailure(e, sourceUrl)
        logger.warn(s"image download failed url=$sourceUrl reason=${cls.reason}: ${e.getMessage}")
        repository.upsertFailed(
          sourceUrl    = sourceUrl,
          relativePath = relPath,
          reason       = Some(cls.reason),
          statusCode   = cls.statusCode
        ) match {
          case Right(_) => Left(new ClassifiedDownloadException(cls, e))
          case Left(e2) =>
            logger.error(s"image_cache failure-bookkeeping also failed url=$sourceUrl: ${e2.getMessage}")
            Left(new ClassifiedDownloadException(cls, e))
        }
    }
  }

  private def writeAtomic(relativePath: String, bytes: Array[Byte]): Unit = {
    val target = rootPath.resolve(relativePath).normalize()
    if (!target.startsWith(rootPath)) {
      // Defense-in-depth — pathFor never produces traversing paths, but a
      // future code path that bypasses pathFor mustn't be able to escape.
      throw new SecurityException(s"refusing to write outside root: $target")
    }
    Files.createDirectories(target.getParent)
    val tmp = target.resolveSibling(target.getFileName.toString + ".tmp")
    Files.write(tmp, bytes)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        // NFS subdir provisioner sometimes refuses ATOMIC_MOVE across
        // an extended attribute boundary; the fallback REPLACE_EXISTING
        // is still good enough — readers either see the old file or the
        // fully-written new one, never a half-written byte stream.
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private def canonicalizeUnderRoot(relativePath: String): Either[Throwable, Path] = {
    if (relativePath == null || relativePath.isEmpty) {
      Left(new IllegalArgumentException("relative path is empty"))
    } else if (relativePath.startsWith("/")) {
      Left(new IllegalArgumentException(s"relative path must not be absolute: $relativePath"))
    } else {
      val resolved = rootPath.resolve(relativePath).normalize()
      if (!resolved.startsWith(rootPath)) {
        Left(new SecurityException(s"path traversal rejected: $relativePath"))
      } else {
        Right(resolved)
      }
    }
  }
}

object ImageCacheService {

  // Lifted out of resolveOrFetch.fail-path so callers can pattern-match
  // distinctly from arbitrary network errors and choose to suppress logs.
  class RetryNotDueException(msg: String) extends RuntimeException(msg)

  // Thumbnail sizing bounds for readResized (push-notification images).
  private[service] val MinThumbWidth = 64
  private[service] val MaxThumbWidth = 1080

  /** Raised when ImageIO can't decode the cached bytes (e.g. WebP/AVIF). */
  class UnsupportedImageException(rel: String)
    extends RuntimeException(s"cannot decode image for thumbnail: $rel")

  /** Resize image bytes to a JPEG no wider than `width` (never upscales),
    * flattening transparency onto white. Returns None if the bytes can't be
    * decoded by the stock ImageIO codecs. Pure/CPU-only — no IO. */
  def resizeToJpeg(bytes: Array[Byte], width: Int, quality: Float = 0.78f): Option[Array[Byte]] = {
    Try {
      val src = ImageIO.read(new ByteArrayInputStream(bytes))
      if (src == null) None
      else {
        val w0 = src.getWidth
        val h0 = src.getHeight
        if (w0 <= 0 || h0 <= 0) None
        else {
          val targetW = math.min(width, w0)
          val targetH = math.max(1, math.round(h0.toDouble * targetW / w0).toInt)
          val dst     = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
          val g       = dst.createGraphics()
          g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
          g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
          g.setColor(Color.WHITE) // flatten any alpha — JPEG has no transparency
          g.fillRect(0, 0, targetW, targetH)
          g.drawImage(src, 0, 0, targetW, targetH, null)
          g.dispose()

          val baos   = new ByteArrayOutputStream()
          val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
          val param  = writer.getDefaultWriteParam
          if (param.canWriteCompressed) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
            param.setCompressionQuality(quality)
          }
          val ios = new MemoryCacheImageOutputStream(baos)
          writer.setOutput(ios)
          try writer.write(null, new IIOImage(dst, null, null), param)
          finally { writer.dispose(); ios.close() }
          Some(baos.toByteArray)
        }
      }
    }.toOption.flatten
  }

  // Conservative allow-list. Anything else falls back to ".bin" — the
  // servlet still serves correctly because Content-Type comes from the
  // image_cache row, not the filename.
  private val KnownImageExtensions: Set[String] =
    Set("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "heic")

  private val HexAlphabet: Array[Char] = "0123456789abcdef".toCharArray

  private[service] val SupportedSchemes: Set[String] = Set("http", "https")

  // Substrings that, when present in the URL, mark it as a signed/expiring
  // CDN URL — retrying with a longer timeout doesn't help once the token
  // is past TTL.
  private val SignedTokenHints: Set[String] =
    Set("auth=", "token=", "signature=", "x-amz-signature=", "x-goog-signature=")

  def schemeOf(url: String): String =
    Try(URI.create(url).getScheme).toOption.filter(_ != null).map(_.toLowerCase).getOrElse("")

  def looksSigned(url: String): Boolean = {
    val lower = url.toLowerCase
    SignedTokenHints.exists(lower.contains)
  }

  // download() throws RuntimeException("non-2xx status NNN fetching <url>");
  // re-extract the code so the classifier can split 4xx vs 5xx.
  private val NonTwoXxStatusPattern = """non-2xx status (\d+)""".r
  def parseStatusFromMsg(msg: String): Option[Int] =
    NonTwoXxStatusPattern.findFirstMatchIn(msg).map(_.group(1).toInt)

  def sha256Hex(input: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val out = md.digest(input.getBytes("UTF-8"))
    val sb  = new StringBuilder(out.length * 2)
    var i   = 0
    while (i < out.length) {
      val b = out(i) & 0xff
      sb.append(HexAlphabet(b >>> 4))
      sb.append(HexAlphabet(b & 0x0f))
      i += 1
    }
    sb.toString
  }

  // Extract a sane extension from the URL's path component, ignoring query
  // strings and fragments. Returns including the leading dot, e.g. ".jpg".
  def extensionFor(sourceUrl: String): String = {
    val path = Try(URI.create(sourceUrl).getPath).toOption.filter(_ != null).getOrElse("")
    val lastSlash = path.lastIndexOf('/')
    val fileName  = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    val lastDot   = fileName.lastIndexOf('.')
    if (lastDot < 0 || lastDot == fileName.length - 1) ".bin"
    else {
      val rawExt = fileName.substring(lastDot + 1).toLowerCase
      if (KnownImageExtensions.contains(rawExt)) s".$rawExt" else ".bin"
    }
  }

  /** Build the default HttpClient. Follows redirects (CDNs love 302s) and
    * caps connect time at the configured download timeout. The per-request
    * total timeout is set on the request itself in download(). */
  def defaultHttpClient(config: ImageCacheConfig): HttpClient =
    HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(JDuration.ofMillis(config.downloadTimeoutMs))
      .build()
}
