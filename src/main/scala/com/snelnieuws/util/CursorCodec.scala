package com.snelnieuws.util

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

import scala.util.Try

/** Opaque cursor for v3 pagination. Encodes the composite key
  * `(published_at_epoch_ms, id)` of the last row returned so the next page
  * can `WHERE (published_at, id) < (?, ?)`. Base64-url so it travels safely
  * in a query string.
  */
object CursorCodec {

  def encode(publishedAt: Instant, id: Long): String = {
    val raw = s"${publishedAt.toEpochMilli}:$id"
    Base64.getUrlEncoder.withoutPadding
      .encodeToString(raw.getBytes(StandardCharsets.UTF_8))
  }

  def decode(s: String): Either[String, (Instant, Long)] =
    Try {
      val raw = new String(Base64.getUrlDecoder.decode(s), StandardCharsets.UTF_8)
      val parts = raw.split(":", 2)
      if (parts.length != 2) throw new IllegalArgumentException("bad cursor shape")
      (Instant.ofEpochMilli(parts(0).toLong), parts(1).toLong)
    }.toEither.left.map(_ => "invalid cursor")
}
