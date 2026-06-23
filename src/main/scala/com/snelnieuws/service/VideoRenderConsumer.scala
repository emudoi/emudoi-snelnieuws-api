package com.snelnieuws.service

import com.snelnieuws.repository.VideoRepository
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.model.VideoRenderExportEvent
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

/** Consumes topic snelnieuws.videos.rendered (pushed by ingestion-api when a
  * video_render becomes playable / is removed) and upserts the local `videos`
  * table — the video analogue of SummarizedArticleConsumer. The poster image
  * (url_to_image) is routed through the same content-addressed image cache the
  * articles use, so it serves from /v2/images with the bundled fallback. */
class VideoRenderConsumer(
  videoRepository: VideoRepository,
  kafkaConfig: SummarizedImportKafkaConfig,
  imageCacheService: ImageCacheService,
  imageDownloadWorker: ImageDownloadWorker
) {

  import VideoRenderConsumer._

  private val logger  = LoggerFactory.getLogger(classOf[VideoRenderConsumer])
  private val running = new AtomicBoolean(false)

  private val consumer: KafkaConsumer[String, String] = {
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.bootstrapServers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaConfig.groupId)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, kafkaConfig.autoOffsetReset)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.box(false))
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Int.box(MaxPollRecords))
    new KafkaConsumer[String, String](props)
  }

  private val thread: Thread = {
    val t = new Thread(() => runLoop(), "video-render-consumer")
    t.setDaemon(true)
    t
  }

  def start(): Unit =
    if (running.compareAndSet(false, true)) {
      logger.info(
        s"Starting video-render consumer — bootstrap=${kafkaConfig.bootstrapServers}, " +
          s"topic=${kafkaConfig.topic}, group=${kafkaConfig.groupId}"
      )
      thread.start()
    }

  def stop(): Unit =
    if (running.compareAndSet(true, false)) {
      logger.info("Stopping video-render consumer...")
      consumer.wakeup()
      try thread.join(ShutdownTimeoutMs)
      catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }

  private def runLoop(): Unit = {
    try {
      consumer.subscribe(Collections.singletonList(kafkaConfig.topic))
      while (running.get()) {
        val records = consumer.poll(PollTimeout)
        if (!records.isEmpty) {
          val offsets = scala.collection.mutable.Map.empty[TopicPartition, OffsetAndMetadata]
          for (record <- records.asScala) {
            processRecord(record.value())
            offsets.update(
              new TopicPartition(record.topic(), record.partition()),
              new OffsetAndMetadata(record.offset() + 1)
            )
          }
          if (offsets.nonEmpty) consumer.commitSync(offsets.asJava)
        }
      }
    } catch {
      case _: WakeupException if !running.get() => // expected on shutdown
      case e: Throwable => logger.error(s"Video-render consumer crashed: ${e.getMessage}", e)
    } finally {
      try consumer.close() catch { case e: Exception => logger.warn(s"Error closing consumer: ${e.getMessage}", e) }
      logger.info("Video-render consumer stopped.")
    }
  }

  private def processRecord(payload: String): Unit = {
    if (payload == null) { logger.warn("Skipping null payload"); return }
    decode[VideoRenderExportEvent](payload) match {
      case Right(event) if event.eventType == "deleted" =>
        videoRepository.deleteById(event.video.renderId) match {
          case Right(_) => logger.info(s"Deleted video render id=${event.video.renderId}")
          case Left(e)  => logger.error(s"Failed to delete video id=${event.video.renderId}: ${e.getMessage}", e)
        }
      case Right(event) =>
        // Route the poster through the content-addressed cache (same as
        // articles): persist the local /v2/images path now, enqueue the
        // fetch; the servlet serves the fallback until bytes land.
        val sourceImg = event.video.urlToImage.map(_.trim).filter(_.nonEmpty)
        val rewritten = sourceImg
          .map(imageCacheService.relativeUrlFor)
          .orElse(Some(imageCacheService.fallbackRelativeUrl))
        videoRepository.upsert(event.video.copy(urlToImage = rewritten)) match {
          case Right(_) =>
            logger.info(s"Upserted video render id=${event.video.renderId} title=${event.video.title}")
            sourceImg.foreach { src =>
              if (SummarizedArticleConsumer.isFetchableImageUrl(src)) imageDownloadWorker.enqueue(src)
            }
          case Left(e) =>
            logger.error(s"Failed to upsert video id=${event.video.renderId}: ${e.getMessage}", e)
        }
      case Left(err) =>
        logger.error(s"Failed to decode video-render event: ${err.getMessage}. Payload=$payload")
    }
  }
}

object VideoRenderConsumer {
  private val PollTimeout       = Duration.ofMillis(1000)
  private val MaxPollRecords    = 100
  private val ShutdownTimeoutMs = 10000L
}
