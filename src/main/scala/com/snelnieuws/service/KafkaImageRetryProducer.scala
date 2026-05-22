package com.snelnieuws.service

import com.snelnieuws.model.ImageRetryEvent
import io.circe.syntax._
import org.apache.kafka.clients.producer.{
  KafkaProducer,
  ProducerConfig,
  ProducerRecord,
  RecordMetadata
}
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

import java.util.Properties

/** Tiny wrapper over Apache Kafka's KafkaProducer for the slow-retry
  * topic. One instance per JVM; the underlying producer is thread-safe.
  * Records are keyed by sourceUrl so duplicate enqueues collapse onto
  * the same partition — the slow consumer additionally dedupes via
  * image_cache.status before doing any work, so cross-partition
  * duplicates are also harmless.
  *
  * The producer's send is asynchronous and best-effort: a network blip
  * here just means the bytes don't land on the slow tier this round.
  * The article row already shows the fallback to the iOS client, so a
  * dropped retry only delays the eventual replacement, not the user's
  * read path. */
class KafkaImageRetryProducer(
  bootstrapServers: String,
  topic: String
) {

  private val logger = LoggerFactory.getLogger(classOf[KafkaImageRetryProducer])

  // Lazy so tests that subclass and override .send/.close don't pay the
  // bootstrap-address-validation cost (would fail on stub hostnames).
  private lazy val producer: KafkaProducer[String, String] = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    // acks=1 mirrors the operational tradeoff used elsewhere: we want
    // the bytes acknowledged by the leader before counting the send
    // as successful, but the slow-retry tier is recoverable enough that
    // waiting for all in-sync replicas (acks=all) isn't worth the
    // latency cost.
    props.put(ProducerConfig.ACKS_CONFIG, "1")
    // Retries are cheap on the producer side — the broker will dedupe
    // idempotently when enable.idempotence is true at the cluster level.
    props.put(ProducerConfig.RETRIES_CONFIG, Int.box(3))
    props.put(ProducerConfig.LINGER_MS_CONFIG, Int.box(50))
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "snelnieuws-api-image-retry-slow-producer")
    new KafkaProducer[String, String](props)
  }

  def send(event: ImageRetryEvent): Unit = {
    val payload = event.asJson.noSpaces
    val record  = new ProducerRecord[String, String](topic, event.sourceUrl, payload)
    producer.send(
      record,
      (metadata: RecordMetadata, exception: Exception) => {
        if (exception != null) {
          logger.warn(
            s"failed to send image-retry event url=${event.sourceUrl} " +
              s"reason=${event.originalReason}: ${exception.getMessage}"
          )
        } else if (logger.isDebugEnabled) {
          logger.debug(
            s"image-retry event sent topic=${metadata.topic()} " +
              s"partition=${metadata.partition()} offset=${metadata.offset()} " +
              s"url=${event.sourceUrl}"
          )
        }
      }
    )
  }

  def close(): Unit = {
    try {
      producer.flush()
      producer.close()
    } catch {
      case e: Throwable =>
        logger.warn(s"image-retry producer close failed: ${e.getMessage}")
    }
  }
}
