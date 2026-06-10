package com.snelnieuws

import com.snelnieuws.service.FcmMessagingService

import scala.collection.mutable

/** Test double for FCM. Symmetric to StubApnsMessagingService — records
  * batches in memory so tests can assert what was sent, with optional
  * per-token rejection so we can exercise dead-token pruning paths.
  */
class StubFcmMessagingService(
  acceptedTokens: Set[String] = Set.empty,
  acceptAll: Boolean = true
) extends FcmMessagingService {

  case class SentBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None
  )

  private val recorded = mutable.ListBuffer.empty[SentBatch]

  def batches: List[SentBatch] = recorded.synchronized(recorded.toList)

  def clear(): Unit = recorded.synchronized(recorded.clear())

  override def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None
  ): (Int, Int) = {
    recorded.synchronized {
      recorded += SentBatch(tokens, title, body, articleId)
    }
    if (acceptAll) (tokens.size, 0)
    else {
      val accepted = tokens.count(acceptedTokens.contains)
      (accepted, tokens.size - accepted)
    }
  }
}
