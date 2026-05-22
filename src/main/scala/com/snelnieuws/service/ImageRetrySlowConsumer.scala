package com.snelnieuws.service

import com.snelnieuws.model.{ImageCacheStatus, ImageRetryEvent}
import com.snelnieuws.repository.ImageCacheRepository
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

/** In-process Kafka consumer for the slow-retry tier.
  *
  *   raw Apache Kafka (no fs2-kafka in this build) — same pattern as
  *   SummarizedArticleConsumer so future-readers don't have to track
  *   two concurrency models.
  *
  *   One thread, one consumer, manual commit per batch after each record
  *   is processed. Decoder failures don't poison the partition — they're
  *   logged and skipped so a bad event from a future producer version
  *   can't stall the topic. */
class ImageRetrySlowConsumer(
  imageCacheRepo: ImageCacheRepository,
  slowDownloader: ImageSlowDownloader,
  bootstrapServers: String,
  topic: String,
  consumerGroup: String,
  autoOffsetReset: String
) {

  import ImageRetrySlowConsumer._

  private val logger  = LoggerFactory.getLogger(classOf[ImageRetrySlowConsumer])
  private val running = new AtomicBoolean(false)

  // Lazy so direct processOne() tests (no start/stop) don't pay the
  // bootstrap-address-validation cost — only the runLoop touches this.
  private lazy val consumer: KafkaConsumer[String, String] = {
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.box(false))
    // Slow tier is low-throughput — small batches keep slow downloads
    // from monopolising a single poll() cycle and starving heartbeats.
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Int.box(MaxPollRecords))
    new KafkaConsumer[String, String](props)
  }

  private val thread: Thread = {
    val t = new Thread(() => runLoop(), "image-retry-slow-consumer")
    t.setDaemon(true)
    t
  }

  def start(): Unit = {
    if (running.compareAndSet(false, true)) {
      logger.info(
        s"Starting image-retry-slow consumer — bootstrap=$bootstrapServers, " +
          s"topic=$topic, group=$consumerGroup"
      )
      thread.start()
    }
  }

  def stop(): Unit = {
    if (running.compareAndSet(true, false)) {
      logger.info("Stopping image-retry-slow consumer...")
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
      // Expected — shutdown was requested.
      case e: Throwable =>
        logger.error(s"image-retry-slow consumer crashed: ${e.getMessage}", e)
    } finally {
      try consumer.close()
      catch {
        case e: Exception =>
          logger.warn(s"Error closing image-retry-slow consumer: ${e.getMessage}", e)
      }
      logger.info("image-retry-slow consumer stopped.")
    }
  }

  private[service] def processOne(payload: String): Unit = {
    if (payload == null) {
      logger.warn("image-retry-slow: skipping null payload")
      return
    }
    decode[ImageRetryEvent](payload) match {
      case Left(err) =>
        logger.warn(s"image-retry-slow: failed to decode event: ${err.getMessage} payload=$payload")
      case Right(event) =>
        // Idempotency guard — skip rows another path already promoted to
        // 'downloaded' (e.g. a re-summarize event triggered a fast-path
        // success in parallel). Cheap DB read avoids a slow network call.
        imageCacheRepo.findByUrl(event.sourceUrl) match {
          case Right(Some(row)) if row.status == ImageCacheStatus.Downloaded =>
            logger.debug(
              s"image-retry-slow: skip already-downloaded url=${event.sourceUrl}"
            )
          case Right(_) =>
            slowDownloader.downloadAndStore(event.sourceUrl) match {
              case Right(_) =>
                logger.info(
                  s"image-retry-slow SUCCESS url=${event.sourceUrl} " +
                    s"(orig reason=${event.originalReason})"
                )
              case Left(e) =>
                logger.info(
                  s"image-retry-slow FAIL url=${event.sourceUrl}: ${e.getMessage}"
                )
            }
          case Left(e) =>
            // DB blip on the idempotency lookup — don't try to download
            // (we might race with a fast-path success). Skip this event;
            // a future re-summarize will re-enqueue if still failing.
            logger.warn(
              s"image-retry-slow: image_cache lookup failed url=${event.sourceUrl}: ${e.getMessage}"
            )
        }
    }
  }
}

object ImageRetrySlowConsumer {
  private val PollTimeout       = Duration.ofMillis(1000)
  private val MaxPollRecords    = 10
  private val ShutdownTimeoutMs = 10000L
}
