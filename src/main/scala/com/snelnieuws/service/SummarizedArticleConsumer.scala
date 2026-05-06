package com.snelnieuws.service

import com.snelnieuws.repository.ArticleRepository
import com.snelnieuws.kafka.SummarizedImportKafkaConfig
import com.snelnieuws.model.SummarizedArticleExportEvent
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

class SummarizedArticleConsumer(
  articleRepository: ArticleRepository,
  kafkaConfig: SummarizedImportKafkaConfig
) {

  import SummarizedArticleConsumer._

  private val logger  = LoggerFactory.getLogger(classOf[SummarizedArticleConsumer])
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
    val t = new Thread(() => runLoop(), "summarized-article-consumer")
    t.setDaemon(true)
    t
  }

  def start(): Unit = {
    if (running.compareAndSet(false, true)) {
      logger.info(
        s"Starting summarized-article consumer — bootstrap=${kafkaConfig.bootstrapServers}, " +
          s"topic=${kafkaConfig.topic}, group=${kafkaConfig.groupId}"
      )
      thread.start()
    }
  }

  def stop(): Unit = {
    if (running.compareAndSet(true, false)) {
      logger.info("Stopping summarized-article consumer...")
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
        logger.error(s"Summarized-article consumer crashed: ${e.getMessage}", e)
    } finally {
      try consumer.close()
      catch { case e: Exception => logger.warn(s"Error closing consumer: ${e.getMessage}", e) }
      logger.info("Summarized-article consumer stopped.")
    }
  }

  private def processRecord(payload: String): Unit = {
    if (payload == null) {
      logger.warn("Skipping null payload from Kafka")
      return
    }
    decode[SummarizedArticleExportEvent](payload) match {
      case Right(event) =>
        articleRepository.upsertByTitle(event.article) match {
          case Right(_) =>
            logger.info(s"Upserted summarized article title=${event.article.title}")
          case Left(e) =>
            // Don't poison the partition — log and skip. Re-throwing here would block this
            // partition forever since we'd never advance the offset.
            logger.error(
              s"Failed to upsert summarized article url=${event.article.url}: ${e.getMessage}",
              e
            )
        }
      case Left(err) =>
        logger.error(s"Failed to decode summarized-article event: ${err.getMessage}. Payload=$payload")
    }
  }
}

object SummarizedArticleConsumer {
  private val PollTimeout       = Duration.ofMillis(1000)
  private val MaxPollRecords    = 100
  private val ShutdownTimeoutMs = 10000L
}
