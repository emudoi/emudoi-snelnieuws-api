package com.snelnieuws

import com.snelnieuws.service.ApnsMessagingService

import scala.collection.mutable

class StubApnsMessagingService(
  acceptedTokens: Set[String] = Set.empty,
  acceptAll: Boolean = true
) extends ApnsMessagingService {

  case class SentBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None,
    imageUrl: Option[String] = None
  )

  private val recorded = mutable.ListBuffer.empty[SentBatch]

  def batches: List[SentBatch] = recorded.synchronized(recorded.toList)

  def clear(): Unit = recorded.synchronized(recorded.clear())

  override def sendBatch(
    tokens: List[String],
    title: String,
    body: String,
    articleId: Option[String] = None,
    imageUrl: Option[String] = None
  ): (Int, Int) = {
    recorded.synchronized {
      recorded += SentBatch(tokens, title, body, articleId, imageUrl)
    }
    if (acceptAll) (tokens.size, 0)
    else {
      val accepted = tokens.count(acceptedTokens.contains)
      (accepted, tokens.size - accepted)
    }
  }
}
