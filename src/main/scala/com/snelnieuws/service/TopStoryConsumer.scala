package com.snelnieuws.service

import com.snelnieuws.model.{TopStoryEvent, TopStoryPayload}
import com.snelnieuws.repository.TopSummaryRepository
import io.circe.parser.decode
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.{Collections, Properties}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

/** Raw Apache Kafka consumer for the `top-stories` topic — mirrors the
  * SummarizedArticleConsumer's lifecycle (single-thread, daemon,
  * manual offset commit). On each event it INSERTs into top_summaries
  * via TopSummaryRepository; the watcher picks up the new row on its
  * next tick (notifications_clickbait_tasks.txt §8).
  *
  * Decoder failures and DB errors are logged + skipped so a bad event
  * never poisons the partition. */
class TopStoryConsumer(
  topSummaryRepository: TopSummaryRepository,
  bootstrapServers: String,
  topic: String,
  consumerGroup: String,
  autoOffsetReset: String
) {

  import TopStoryConsumer._

  private val logger  = LoggerFactory.getLogger(classOf[TopStoryConsumer])
  private val running = new AtomicBoolean(false)

  // Lazy so unit tests that exercise processOne directly don't pay the
  // bootstrap-address-validation cost.
  private lazy val consumer: KafkaConsumer[String, String] = {
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.box(false))
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Int.box(MaxPollRecords))
    new KafkaConsumer[String, String](props)
  }

  private val thread: Thread = {
    val t = new Thread(() => runLoop(), "top-story-consumer")
    t.setDaemon(true)
    t
  }

  def start(): Unit = {
    if (running.compareAndSet(false, true)) {
      logger.info(
        s"Starting top-story consumer — bootstrap=$bootstrapServers, " +
          s"topic=$topic, group=$consumerGroup"
      )
      thread.start()
    }
  }

  def stop(): Unit = {
    if (running.compareAndSet(true, false)) {
      logger.info("Stopping top-story consumer...")
      consumer.wakeup()
      try thread.join(ShutdownTimeoutMs)
      catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }
  }

  private def runLoop(): Unit = {
    try {
      consumer.subscribe(Collections.singletonList(topic))
      while (running.get()) {
        val records = consumer.poll(PollTimeout)
        if (!records.isEmpty) {
          val offsetsToCommit =
            scala.collection.mutable.Map.empty[TopicPartition, OffsetAndMetadata]
          for (record <- records.asScala) {
            processOne(record.value())
            val tp = new TopicPartition(record.topic(), record.partition())
            offsetsToCommit.update(tp, new OffsetAndMetadata(record.offset() + 1))
          }
          if (offsetsToCommit.nonEmpty) {
            consumer.commitSync(offsetsToCommit.asJava)
          }
        }
      }
    } catch {
      case _: WakeupException if !running.get() =>
      case e: Throwable =>
        logger.error(s"top-story consumer crashed: ${e.getMessage}", e)
    } finally {
      try consumer.close()
      catch {
        case e: Exception =>
          logger.warn(s"Error closing top-story consumer: ${e.getMessage}", e)
      }
      logger.info("top-story consumer stopped.")
    }
  }

  private[service] def processOne(payload: String): Unit = {
    if (payload == null) {
      logger.warn("top-story: skipping null payload")
      return
    }
    decode[TopStoryEvent](payload) match {
      case Left(err) =>
        logger.warn(s"top-story: failed to decode event: ${err.getMessage} payload=$payload")
      case Right(event) =>
        if (event.eventType != TopStoryEvent.EventTypeSelected) {
          logger.warn(s"top-story: unknown eventType=${event.eventType}; skipping")
        } else {
          insertOne(event.payload)
        }
    }
  }

  private def insertOne(payload: TopStoryPayload): Unit = {
    topSummaryRepository.insert(payload) match {
      case Right(id) =>
        logger.info(
          s"top_story.consumed id=$id articleId=${payload.representativeArticleId} " +
            s"tier=${payload.selectionTier} languages=${payload.notificationMessages.keys.mkString(",")}"
        )
      case Left(e) =>
        logger.error(
          s"top_story.consume.failed articleId=${payload.representativeArticleId}: ${e.getMessage}",
          e
        )
    }
  }
}

object TopStoryConsumer {
  private val PollTimeout       = Duration.ofMillis(1000)
  private val MaxPollRecords    = 10
  private val ShutdownTimeoutMs = 10000L
}
