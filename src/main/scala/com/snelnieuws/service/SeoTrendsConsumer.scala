package com.snelnieuws.service

import com.snelnieuws.repository.SeoTrendsRepository
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.model.SeoTrendsExportEvent
import io.circe.parser.decode
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import java.time.{Duration, OffsetDateTime}
import java.util.{Collections, Properties}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._

class SeoTrendsConsumer(
  seoTrendsRepository: SeoTrendsRepository,
  trendingScoreService: TrendingScoreService,
  kafkaConfig: SummarizedImportKafkaConfig
) {

  import SeoTrendsConsumer._

  private val logger  = LoggerFactory.getLogger(classOf[SeoTrendsConsumer])
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
    val t = new Thread(() => runLoop(), "seo-trends-consumer")
    t.setDaemon(true)
    t
  }

  def start(): Unit = {
    if (running.compareAndSet(false, true)) {
      logger.info(
        s"Starting seo-trends consumer — bootstrap=${kafkaConfig.bootstrapServers}, " +
          s"topic=${kafkaConfig.topic}, group=${kafkaConfig.groupId}"
      )
      thread.start()
    }
  }

  def stop(): Unit = {
    if (running.compareAndSet(true, false)) {
      logger.info("Stopping seo-trends consumer...")
      consumer.wakeup()
      try thread.join(ShutdownTimeoutMs)
      catch { case _: InterruptedException => Thread.currentThread().interrupt() }
    }
  }

  private def runLoop(): Unit = {
    try {
      consumer.subscribe(Collections.singletonList(kafkaConfig.topic))
      while (running.get()) {
        val records = consumer.poll(PollTimeout)
        if (!records.isEmpty) {
          val offsetsToCommit = scala.collection.mutable.Map.empty[TopicPartition, OffsetAndMetadata]
          for (record <- records.asScala) {
            processRecord(record.value())
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
        logger.error(s"Seo-trends consumer crashed: ${e.getMessage}", e)
    } finally {
      try consumer.close()
      catch { case e: Exception => logger.warn(s"Error closing consumer: ${e.getMessage}", e) }
      logger.info("Seo-trends consumer stopped.")
    }
  }

  private def processRecord(payload: String): Unit = {
    if (payload == null) {
      logger.warn("Skipping null payload from Kafka")
      return
    }
    decode[SeoTrendsExportEvent](payload) match {
      case Right(event) =>
        val trends = event.trends
        val geo    = trends.geo
        if (trends.terms.isEmpty) {
          logger.info(s"Seo-trends batch geo=$geo has no terms; skipping")
        } else {
          val source = trends.source.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultSource)
          val collectedAt =
            try OffsetDateTime.parse(trends.collectedAt)
            catch {
              case _: Exception =>
                logger.warn(
                  s"Seo-trends batch geo=$geo has unparseable collectedAt='${trends.collectedAt}'; using now()"
                )
                OffsetDateTime.now()
            }
          seoTrendsRepository.replaceForGeo(geo, collectedAt, source, trends.terms) match {
            case Right(n) =>
              logger.info(s"Upserted seo_trends geo=$geo terms=$n source=$source")
              // Scoring is best-effort and its own try/catch — a scoring
              // failure must NOT undo the raw write above (which already
              // committed) nor poison the partition.
              try trendingScoreService.scoreGeo(geo)
              catch {
                case e: Throwable =>
                  logger.error(s"Seo-trends scoring failed for geo=$geo: ${e.getMessage}", e)
              }
            case Left(e) =>
              // Don't poison the partition — log and skip. The next batch for
              // this geo will replace it anyway.
              logger.error(s"Failed to upsert seo_trends for geo=$geo: ${e.getMessage}", e)
          }
        }
      case Left(err) =>
        logger.error(s"Failed to decode seo-trends event: ${err.getMessage}. Payload=$payload")
    }
  }
}

object SeoTrendsConsumer {
  private val PollTimeout       = Duration.ofMillis(1000)
  private val MaxPollRecords    = 100
  private val ShutdownTimeoutMs = 10000L
  private val DefaultSource     = "google_trends"
}
