package com.snelnieuws.service

import com.snelnieuws.model.{ArticleV3Row, TopNewsVideoRow}
import com.snelnieuws.repository.{ArticleRepository, TopNewsVideosRepository}
import org.slf4j.LoggerFactory

/** Inserts two video-script rows per call into `top_news_videos`:
  *   - one `v21` deep-dive of the top story
  *   - one `yellow` rundown of the top 5 stories
  *
  * Both rows share `anchor = "erica"` and `status = "pending"` (table
  * default). The litikai-video-generator pipeline polls
  * `WHERE status='pending'` and renders each.
  *
  * Selection reuses the notification-path window
  * (`ArticleRepository.findInWindowForTopStory`) and the same 3-tier
  * heuristic via `TopStorySelector.selectTopN(_, 5)`. NoFreshTopStory
  * is returned when the selector picks zero articles — symmetric with
  * the iOS/Android dispatch path's 503 semantics.
  */
class VideoDispatchService(
  articleRepository: ArticleRepository,
  videosRepository:  TopNewsVideosRepository
) {

  private val logger = LoggerFactory.getLogger(classOf[VideoDispatchService])

  /** Mirrors the notification flow's window: full table (sinceId=None,
    * upToId=None), English-language only. We deliberately don't apply
    * a "since last dispatch" watermark — the video pipeline is a
    * once-per-day cadence, not a per-fire-rate-limit cadence, so
    * scheduling collisions aren't a concern. */
  def dispatch(): Either[Throwable, VideoDispatchOutcome] =
    for {
      window <- articleRepository.findInWindowForTopStory(None, None, "en")
      outcome <- TopStorySelector.selectTopN(window, 5) match {
        case Nil =>
          logger.info(
            s"video dispatch: no viable top story in window of ${window.size} articles"
          )
          Right(VideoDispatchOutcome.NoFreshTopStory)
        case selections =>
          val ids       = selections.map(_.representativeArticleId)
          val byId: Map[Long, ArticleV3Row] = window.map(a => a.id -> a).toMap
          val articles  = ids.flatMap(byId.get)
          if (articles.isEmpty) {
            logger.warn("video dispatch: selectTopN returned picks but none matched window")
            Right(VideoDispatchOutcome.NoFreshTopStory)
          } else {
            val top1 = articles.head
            val v21Text   = s"Top news of the day. ${bodyOf(top1)}"
            val yellowText = "Top 5 news of the day. " +
              articles.take(5).map(a => endWithPeriod(a.title)).mkString(" ")
            val today = java.time.LocalDate.now.toString
            val rows = List(
              TopNewsVideoRow(
                text        = v21Text,
                anchor      = "erica",
                variant     = "v21",
                description = Some(s"top news of the day — $today")
              ),
              TopNewsVideoRow(
                text        = yellowText,
                anchor      = "erica",
                variant     = "yellow",
                description = Some(s"top 5 news of the day — $today")
              )
            )
            videosRepository.insertBatch(rows).map { createdIds =>
              VideoDispatchOutcome.Inserted(
                createdIds       = createdIds,
                top1ArticleId    = top1.id,
                top5ArticleIds   = articles.take(5).map(_.id)
              )
            }
          }
      }
    } yield outcome

  /** Body text the v21 anchor reads aloud. `content` is always NULL on
    * snelnieuws articles (summarized articles don't carry full body),
    * so the actual usable body text lives in `description` (the
    * summary). Falls back to title if a row somehow has neither. */
  private def bodyOf(a: ArticleV3Row): String =
    a.content.filter(_.nonEmpty)
      .orElse(a.description.filter(_.nonEmpty))
      .getOrElse(a.title)

  private def endWithPeriod(s: String): String =
    if (s.isEmpty) s else if (".!?".contains(s.last)) s else s + "."
}

sealed trait VideoDispatchOutcome
object VideoDispatchOutcome {
  case class Inserted(
    createdIds:     List[Long],
    top1ArticleId:  Long,
    top5ArticleIds: List[Long]
  ) extends VideoDispatchOutcome
  case object NoFreshTopStory extends VideoDispatchOutcome
}
