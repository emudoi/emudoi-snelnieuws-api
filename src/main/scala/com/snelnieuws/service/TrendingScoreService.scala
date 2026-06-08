package com.snelnieuws.service

import com.snelnieuws.model.ArticleV3Row
import com.snelnieuws.repository.{ArticleRepository, ArticleTrendingScoresRepository, SeoTrendsRepository}
import org.slf4j.LoggerFactory

import java.time.OffsetDateTime
import scala.collection.mutable

/** Scores recent articles against a geo's currently-trending search terms and
  * writes the result into `article_trending_scores`. Runs inside the
  * SeoTrendsConsumer per batch. Entirely best-effort: every path catches +
  * logs and returns, never throwing out of the consumer. When matching yields
  * nothing, nothing is written (but the geo's stale score rows are still
  * cleared by upsertForGeo's delete).
  *
  * Matching, per term (rank-ordered; weight w(rank) = 1/log2(rank+1)):
  *   - semantic: embed the term via IngestionApiClient.embedQuery, kNN via
  *     IngestionApiClient.searchSemantic over the snelnieuws collection
  *     (source=None) and the eulang collection (source=Some("eulang")), keep
  *     cos >= CosFloor, matchStrength = (cos - CosFloor) / (1 - CosFloor).
  *   - lexical: all term tokens present in title/description → matchStrength = 1.0.
  * Per article: raw = Σ_terms w(rank)·matchStrength; score = min(1.0, raw/SAT). */
class TrendingScoreService(
  seoTrendsRepository: SeoTrendsRepository,
  articleTrendingScoresRepository: ArticleTrendingScoresRepository,
  articleRepository: ArticleRepository,
  eulangArticleRepository: ArticleRepository,
  ingestionApiClient: IngestionApiClient
) {

  import TrendingScoreService._

  private val logger = LoggerFactory.getLogger(classOf[TrendingScoreService])

  /** Score the geo's latest stored batch against the last-48h candidate set
    * and write `article_trending_scores`. Best-effort: any failure is logged
    * and swallowed so the raw seo_trends write (done by the caller) still
    * stands. */
  def scoreGeo(geo: String): Unit =
    try {
      val terms = seoTrendsRepository.latestTermsByGeo(geo) match {
        case Right(ts) => ts.take(MaxTerms)
        case Left(e) =>
          logger.warn(s"trending scoring: failed to load terms for geo=$geo: ${e.getMessage}")
          return
      }
      if (terms.isEmpty) {
        logger.debug(s"trending scoring: no terms for geo=$geo, skipping")
        return
      }

      val since = OffsetDateTime.now().minusHours(WindowH.toLong)
      val mainCandidates = articleRepository.findPublishedSince(since) match {
        case Right(rows) => rows
        case Left(e) =>
          logger.warn(s"trending scoring: failed to load articles candidates: ${e.getMessage}")
          Nil
      }
      val eulangCandidates = eulangArticleRepository.findPublishedSince(since) match {
        case Right(rows) => rows
        case Left(e) =>
          logger.warn(s"trending scoring: failed to load eulang candidates: ${e.getMessage}")
          Nil
      }
      if (mainCandidates.isEmpty && eulangCandidates.isEmpty) {
        logger.debug(s"trending scoring: no recent candidates for geo=$geo, skipping")
        return
      }

      // Candidate lookup by (source, articleId). url -> (source, id) lets us
      // map semantic matches (which carry url + article_id) back to the
      // candidate set regardless of which the bridge keyed on.
      val candidateKeys: Set[(String, Long)] =
        mainCandidates.map(r => (SourceArticles, r.id)).toSet ++
          eulangCandidates.map(r => (SourceEulang, r.id)).toSet
      val idBySourceUrl: Map[(String, String), Long] =
        (mainCandidates.map(r => (SourceArticles, r.url) -> r.id) ++
          eulangCandidates.map(r => (SourceEulang, r.url) -> r.id)).toMap

      // Accumulated raw score per (source, articleId).
      val raw = mutable.Map.empty[(String, Long), Double].withDefaultValue(0.0)

      terms.foreach { case (term, rank) =>
        val weight = w(rank)

        // ── Lexical ──────────────────────────────────────────────────────
        val termTokens = tokenize(term)
        if (termTokens.nonEmpty) {
          def lexicalHit(row: ArticleV3Row): Boolean = {
            val hay = (row.title + " " + row.description.getOrElse("")).toLowerCase
            val hayTokens = tokenize(hay).toSet
            termTokens.forall(hayTokens.contains)
          }
          mainCandidates.foreach { c =>
            if (lexicalHit(c)) raw((SourceArticles, c.id)) += weight * 1.0
          }
          eulangCandidates.foreach { c =>
            if (lexicalHit(c)) raw((SourceEulang, c.id)) += weight * 1.0
          }
        }

        // ── Semantic ─────────────────────────────────────────────────────
        // One embed, two kNN searches (snelnieuws + eulang collections).
        ingestionApiClient.embedQuery(term) match {
          case Right(embedding) =>
            applySemantic(embedding, source = None, SourceArticles, weight, candidateKeys, idBySourceUrl, raw)
            applySemantic(embedding, source = Some(SourceEulang), SourceEulang, weight, candidateKeys, idBySourceUrl, raw)
          case Left(e) =>
            logger.debug(s"trending scoring: embed failed for term='$term': ${e.getMessage}")
        }
      }

      if (raw.isEmpty) {
        logger.info(s"trending scoring: geo=$geo matched no articles, writing nothing")
        return
      }

      val scoreRows: List[(String, Long, Double)] = raw.iterator.map {
        case ((source, articleId), rawScore) =>
          (source, articleId, math.min(1.0, rawScore / SAT))
      }.toList

      val expiresAt = OffsetDateTime.now().plusHours(ScoreTtlH.toLong)
      articleTrendingScoresRepository.upsertForGeo(geo, scoreRows, expiresAt) match {
        case Right(n) =>
          logger.info(
            s"trending scoring: geo=$geo terms=${terms.size} candidates=" +
              s"${mainCandidates.size + eulangCandidates.size} scored=$n expiresAt=$expiresAt"
          )
        case Left(e) =>
          logger.warn(s"trending scoring: failed to write scores for geo=$geo: ${e.getMessage}")
      }
    } catch {
      case e: Throwable =>
        logger.error(s"trending scoring: unexpected failure for geo=$geo: ${e.getMessage}", e)
    }

  /** kNN against one Milvus collection; fold matches that map back to a
    * candidate into `raw`. */
  private def applySemantic(
    embedding: Array[Float],
    source: Option[String],
    candidateSource: String,
    weight: Double,
    candidateKeys: Set[(String, Long)],
    idBySourceUrl: Map[(String, String), Long],
    raw: mutable.Map[(String, Long), Double]
  ): Unit =
    ingestionApiClient.searchSemantic(
      embedding = embedding,
      language  = None,
      country   = None,
      limit     = SemanticLimit,
      minScore  = CosFloor,
      source    = source
    ) match {
      case Right(matches) =>
        matches.foreach { m =>
          // Prefer the article_id keying; fall back to url when the bridge's
          // id doesn't line up with our candidate set.
          val key: Option[(String, Long)] =
            if (candidateKeys.contains((candidateSource, m.articleId))) Some((candidateSource, m.articleId))
            else idBySourceUrl.get((candidateSource, m.url)).map(id => (candidateSource, id))
          key.foreach { k =>
            val matchStrength = math.max(0.0, (m.score - CosFloor) / (1.0 - CosFloor))
            if (matchStrength > 0.0) raw(k) += weight * matchStrength
          }
        }
      case Left(e) =>
        logger.debug(s"trending scoring: semantic search failed (source=$source): ${e.getMessage}")
    }
}

object TrendingScoreService {

  // Source discriminators — mirror article_trending_scores.source and the
  // IngestionApiClient.searchSemantic `source` param ("eulang" routes the
  // bridge to the eulang collection).
  val SourceArticles = "articles"
  val SourceEulang   = "eulang"

  // Cap the term list (rank-ordered, hottest first). The minion sends ~300.
  val MaxTerms = 100

  // Candidate window — only articles published within the last WindowH hours
  // are scored. Mirrors the feed's WindowH so a scored article is always
  // inside the boost window.
  val WindowH = 48.0

  // Cosine floor for a semantic match; matchStrength = (cos-floor)/(1-floor).
  val CosFloor = 0.70

  // kNN top-K per (term, collection). The bridge caps at 50.
  val SemanticLimit = 50

  // Matches-to-saturate: raw score that maps to score = 1.0. Tunable.
  val SAT = 3.0

  // Score validity after a batch.
  val ScoreTtlH = 8.0

  /** Term weight by rank (1 = hottest). w(1)=1, w(2)≈0.63, w(3)=0.5, … */
  def w(rank: Int): Double = 1.0 / (math.log(rank + 1) / math.log(2))

  /** Lowercase alphanumeric tokens, length >= 2. Splits on any non-alnum so
    * "AI-2026!" → List("ai","2026"). */
  def tokenize(s: String): List[String] =
    s.toLowerCase.split("[^\\p{Alnum}]+").iterator.filter(_.length >= 2).toList
}
