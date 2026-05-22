package com.snelnieuws.service

import com.snelnieuws.model.{ImageCacheRow, ImageCacheStatus}
import com.snelnieuws.repository.ImageCacheRepository
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.http.HttpClient
import java.nio.file.Files
import java.time.OffsetDateTime

/** Unit tests for the deterministic / synchronous parts of
  * ImageCacheService — pathFor, extensionFor, sha256Hex, retry eligibility,
  * and readBytes traversal safety. The HTTP fetch path is exercised via
  * an integration-style stub that bypasses the live network.
  */
class ImageCacheServiceSpec extends AnyWordSpec with Matchers {

  // The repository methods we touch from the unit-tested codepaths are
  // findByRelativePath (in readBytes). pathFor / extensionFor / sha256Hex
  // never hit it. A repo whose `transactor` thunk throws keeps us honest:
  // any code path that secretly does DB I/O fails loudly.
  private def explodingRepo(): ImageCacheRepository =
    new ImageCacheRepository({ throw new AssertionError("repo must not be used in this test") })

  // Override findByRelativePath only — we don't want readBytes' content-type
  // probe to throw; everything else stays in the never-call lane.
  private class StubRepo(rowByRelPath: Map[String, ImageCacheRow])
      extends ImageCacheRepository({ throw new AssertionError("repo must not be used in this test") }) {
    override def findByRelativePath(relativePath: String) =
      Right(rowByRelPath.get(relativePath))
  }

  private def baseConfig(rootDir: String): ImageCacheConfig = ImageCacheConfig(
    rootDir             = rootDir,
    downloadTimeoutMs   = 5000,
    maxBytes            = 10 * 1024 * 1024,
    userAgent           = "test/1.0",
    maxAttempts         = 3,
    retryBackoffMinutes = 30
  )

  private def serviceWith(repo: ImageCacheRepository, rootDir: String): ImageCacheService =
    new ImageCacheService(repo, HttpClient.newHttpClient(), baseConfig(rootDir))

  // ─────────────────────────── pure helpers ─────────────────────────────

  "ImageCacheService.sha256Hex" should {
    "produce 64-char lowercase hex" in {
      val h = ImageCacheService.sha256Hex("https://example.com/a.jpg")
      h.length shouldBe 64
      h should fullyMatch regex "[0-9a-f]{64}"
    }
    "be deterministic" in {
      val u = "https://nos.nl/foo/bar.jpg"
      ImageCacheService.sha256Hex(u) shouldBe ImageCacheService.sha256Hex(u)
    }
    "differ across inputs" in {
      ImageCacheService.sha256Hex("a") should not equal ImageCacheService.sha256Hex("b")
    }
  }

  "ImageCacheService.extensionFor" should {
    "extract recognised image extensions" in {
      ImageCacheService.extensionFor("https://x.com/a.jpg")     shouldBe ".jpg"
      ImageCacheService.extensionFor("https://x.com/a.JPEG")    shouldBe ".jpeg"
      ImageCacheService.extensionFor("https://x.com/a/b.png")   shouldBe ".png"
      ImageCacheService.extensionFor("https://x.com/a.webp?w=4") shouldBe ".webp"
    }
    "fall back to .bin for unknown extensions" in {
      ImageCacheService.extensionFor("https://x.com/page.html") shouldBe ".bin"
      ImageCacheService.extensionFor("https://x.com/foo.exe")   shouldBe ".bin"
    }
    "fall back to .bin for missing extensions" in {
      ImageCacheService.extensionFor("https://x.com/no-extension") shouldBe ".bin"
      ImageCacheService.extensionFor("https://x.com/")             shouldBe ".bin"
      ImageCacheService.extensionFor("https://x.com")              shouldBe ".bin"
    }
  }

  "ImageCacheService.pathFor" should {
    "be deterministic" in {
      val svc = serviceWith(explodingRepo(), "/tmp/images")
      val u   = "https://nos.nl/some-image.jpg"
      svc.pathFor(u) shouldBe svc.pathFor(u)
    }
    "produce the documented aa/bb/<sha>.ext shape" in {
      val svc = serviceWith(explodingRepo(), "/tmp/images")
      val p   = svc.pathFor("https://nos.nl/some-image.jpg")
      p should fullyMatch regex "[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{64}\\.jpg"
      val parts = p.split("/")
      parts(0) shouldBe parts(2).take(2)
      parts(1) shouldBe parts(2).drop(2).take(2)
    }
    "exposed relativeUrlFor wraps in /v2/images/" in {
      val svc = serviceWith(explodingRepo(), "/tmp/images")
      svc.relativeUrlFor("https://x.com/a.png") should startWith("/v2/images/")
    }
  }

  // ──────────────────────────── readBytes ───────────────────────────────

  "ImageCacheService.readBytes" should {
    "reject absolute paths" in {
      val svc = serviceWith(explodingRepo(), "/tmp/images")
      svc.readBytes("/etc/passwd") shouldBe a[Left[_, _]]
      svc.readBytes("/etc/passwd").left.toOption.get shouldBe an[IllegalArgumentException]
    }
    "reject empty paths" in {
      val svc = serviceWith(explodingRepo(), "/tmp/images")
      svc.readBytes("") shouldBe a[Left[_, _]]
    }
    "reject path traversal that escapes the root" in {
      val svc = serviceWith(explodingRepo(), "/tmp/images")
      val res = svc.readBytes("../../../etc/passwd")
      res shouldBe a[Left[_, _]]
      res.left.toOption.get shouldBe a[SecurityException]
    }
    "return NoSuchFileException for missing files inside the root" in {
      val tmp = Files.createTempDirectory("img-spec-")
      val svc = serviceWith(new StubRepo(Map.empty), tmp.toString)
      val res = svc.readBytes("aa/bb/does-not-exist.jpg")
      res shouldBe a[Left[_, _]]
      res.left.toOption.get shouldBe a[java.nio.file.NoSuchFileException]
    }
    "return bytes + content-type from the row when the file exists" in {
      val tmp     = Files.createTempDirectory("img-spec-")
      val relPath = "aa/bb/cafef00d.jpg"
      val target  = tmp.resolve(relPath)
      Files.createDirectories(target.getParent)
      Files.write(target, Array[Byte](1, 2, 3, 4))

      val row = ImageCacheRow(
        sourceUrl     = "https://example.com/x.jpg",
        relativePath  = relPath,
        contentType   = Some("image/jpeg"),
        sizeBytes     = Some(4L),
        status        = ImageCacheStatus.Downloaded,
        downloadedAt  = Some(OffsetDateTime.now()),
        lastAttemptAt = OffsetDateTime.now(),
        attempts      = 1
      )
      val svc = serviceWith(new StubRepo(Map(relPath -> row)), tmp.toString)
      val res = svc.readBytes(relPath)
      res shouldBe a[Right[_, _]]
      val (bytes, ct) = res.toOption.get
      bytes.toSeq shouldBe Seq[Byte](1, 2, 3, 4)
      ct shouldBe Some("image/jpeg")
    }
  }

  // ─────────────────────────── retry policy ─────────────────────────────

  "ImageCacheService.isRetryEligible" should {
    val tmp = Files.createTempDirectory("img-spec-").toString

    def row(attempts: Int, lastAttempt: OffsetDateTime): ImageCacheRow = ImageCacheRow(
      sourceUrl     = "u",
      relativePath  = "p",
      contentType   = None,
      sizeBytes     = None,
      status        = ImageCacheStatus.Failed,
      downloadedAt  = None,
      lastAttemptAt = lastAttempt,
      attempts      = attempts
    )

    "be true for fresh-attempt-count rows past their backoff window" in {
      val svc = serviceWith(explodingRepo(), tmp)
      svc.isRetryEligible(row(0, OffsetDateTime.now().minusHours(2))) shouldBe true
    }
    "be false when attempts hit the cap" in {
      val svc = serviceWith(explodingRepo(), tmp)
      svc.isRetryEligible(row(3, OffsetDateTime.now().minusHours(2))) shouldBe false
    }
    "be false within the backoff window" in {
      val svc = serviceWith(explodingRepo(), tmp)
      svc.isRetryEligible(row(0, OffsetDateTime.now().minusMinutes(5))) shouldBe false
    }
  }

  // ─────────────────────────── classifier ──────────────────────────────

  "ImageCacheService.classifyFailure" should {
    val tmp = Files.createTempDirectory("img-spec-").toString
    val svc = serviceWith(explodingRepo(), tmp)

    "tag java.net.http.HttpTimeoutException as Timeout (retryableSlow)" in {
      val e   = new java.net.http.HttpTimeoutException("read timed out")
      val cls = svc.classifyFailure(e, "https://example.com/a.jpg")
      cls            shouldBe DownloadFailure.Timeout
      cls.reason     shouldBe "timeout"
      cls.statusCode shouldBe None
      cls.retryableSlow shouldBe true
    }

    "tag UnknownHostException as ConnectionError (retryableSlow)" in {
      val cls = svc.classifyFailure(
        new java.net.UnknownHostException("nope"),
        "https://nope.invalid/a.jpg"
      )
      cls            shouldBe DownloadFailure.ConnectionError
      cls.reason     shouldBe "connection_error"
      cls.retryableSlow shouldBe true
    }

    "tag ConnectException as ConnectionError" in {
      val cls = svc.classifyFailure(new java.net.ConnectException("refused"), "https://x/")
      cls shouldBe DownloadFailure.ConnectionError
    }

    "tag SSLException as ConnectionError" in {
      val cls = svc.classifyFailure(new javax.net.ssl.SSLException("handshake"), "https://x/")
      cls shouldBe DownloadFailure.ConnectionError
    }

    "tag 'non-2xx status 503' as Http5xx(503) (retryableSlow)" in {
      val cls = svc.classifyFailure(
        new RuntimeException("non-2xx status 503 fetching https://x/a.jpg"),
        "https://x/a.jpg"
      )
      cls            shouldBe DownloadFailure.Http5xx(503)
      cls.reason     shouldBe "http_5xx"
      cls.statusCode shouldBe Some(503)
      cls.retryableSlow shouldBe true
    }

    "tag 'non-2xx status 404' as Http4xx(404) (NOT retryableSlow)" in {
      val cls = svc.classifyFailure(
        new RuntimeException("non-2xx status 404 fetching https://x/a.jpg"),
        "https://x/a.jpg"
      )
      cls            shouldBe DownloadFailure.Http4xx(404)
      cls.statusCode shouldBe Some(404)
      cls.retryableSlow shouldBe false
    }

    "tag 403 on a signed URL as SignedTokenExpired" in {
      val cls = svc.classifyFailure(
        new RuntimeException("non-2xx status 403 fetching https://reuters.com/img?auth=xyz"),
        "https://reuters.com/img?auth=xyz"
      )
      cls               shouldBe DownloadFailure.SignedTokenExpired
      cls.reason        shouldBe "signed_token_expired"
      cls.retryableSlow shouldBe false
    }

    "tag 403 on a non-signed URL as plain Http4xx(403)" in {
      val cls = svc.classifyFailure(
        new RuntimeException("non-2xx status 403 fetching https://example.com/x.jpg"),
        "https://example.com/x.jpg"
      )
      cls shouldBe DownloadFailure.Http4xx(403)
    }

    "tag 'image exceeds max-bytes' as Oversize (NOT retryableSlow)" in {
      val cls = svc.classifyFailure(
        new RuntimeException("image exceeds max-bytes (200 > 100) at https://x/a.jpg"),
        "https://x/a.jpg"
      )
      cls            shouldBe DownloadFailure.Oversize
      cls.retryableSlow shouldBe false
    }

    "tag IllegalArgumentException as UnsupportedScheme (NOT retryableSlow)" in {
      val cls = svc.classifyFailure(
        new IllegalArgumentException("unsupported scheme 'data' for data:image/png;base64,..."),
        "data:image/png;base64,iVBORw0..."
      )
      cls            shouldBe DownloadFailure.UnsupportedScheme
      cls.retryableSlow shouldBe false
    }

    "tag arbitrary other exceptions as Other (retryableSlow)" in {
      val cls = svc.classifyFailure(new RuntimeException("something weird"), "https://x/")
      cls               shouldBe DownloadFailure.Other
      cls.retryableSlow shouldBe true
    }
  }

  "ImageCacheService.looksSigned" should {
    "match common signed-URL hints (case-insensitive)" in {
      ImageCacheService.looksSigned("https://example.com/img?auth=xyz")        shouldBe true
      ImageCacheService.looksSigned("https://example.com/img?TOKEN=abc")       shouldBe true
      ImageCacheService.looksSigned("https://x/?X-Amz-Signature=blah")         shouldBe true
      ImageCacheService.looksSigned("https://x/?something-else=1")             shouldBe false
    }
  }

  "ImageCacheService.schemeOf" should {
    "return the lowercased scheme for valid URLs" in {
      ImageCacheService.schemeOf("https://example.com")  shouldBe "https"
      ImageCacheService.schemeOf("HTTP://example.com")   shouldBe "http"
      ImageCacheService.schemeOf("data:image/png;base64,XXXX") shouldBe "data"
      ImageCacheService.schemeOf("ftp://example.com")    shouldBe "ftp"
    }
    "return empty string for unparseable input" in {
      ImageCacheService.schemeOf("just a string")     shouldBe ""
      ImageCacheService.schemeOf("///garbage//")      shouldBe ""
    }
  }

  "ImageCacheService.parseStatusFromMsg" should {
    "extract the HTTP status from the download() error message" in {
      ImageCacheService.parseStatusFromMsg("non-2xx status 503 fetching https://x") shouldBe Some(503)
      ImageCacheService.parseStatusFromMsg("non-2xx status 404 …")                  shouldBe Some(404)
      ImageCacheService.parseStatusFromMsg("something unrelated")                   shouldBe None
    }
  }
}
