package com.snelnieuws.service

import com.snelnieuws.model.UserEventExport
import io.circe.Json
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

import java.util.Properties

/** Publishes first-party engagement events to the `user.events` topic for the
  * LinUCB recommender to consume (recommender Phase-1). One instance per JVM;
  * the underlying producer is thread-safe.
  *
  * Best-effort by design: events are also persisted to Postgres (the system of
  * record), so a dropped publish here just means the recommender learns from
  * that event a little later via backfill, never a data loss. Records are keyed
  * by client_id so one device's events stay ordered on a single partition.
  *
  * Ships dark — only constructed when `kafka.user-events.enabled` is true, so
  * when off there is zero overhead on the event write path. */
class KafkaUserEventsProducer(bootstrapServers: String, topic: String) {

  private val logger = LoggerFactory.getLogger(classOf[KafkaUserEventsProducer])

  private lazy val producer: KafkaProducer[String, String] = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, "1")
    props.put(ProducerConfig.RETRIES_CONFIG, Int.box(3))
    props.put(ProducerConfig.LINGER_MS_CONFIG, Int.box(50))
    props.put(ProducerConfig.CLIENT_ID_CONFIG, "snelnieuws-api-user-events-producer")
    new KafkaProducer[String, String](props)
  }

  private def jstr(o: Option[String]): Json = o.map(Json.fromString).getOrElse(Json.Null)

  private def toJson(e: UserEventExport): String =
    Json
      .obj(
        "client_id"  -> Json.fromString(e.clientId.toString),
        "event_type" -> Json.fromString(e.eventType),
        "article_id" -> jstr(e.articleId),
        "title"      -> jstr(e.title),
        "url"        -> jstr(e.url),
        "category"   -> jstr(e.category),
        "source"     -> jstr(e.source),
        "language"   -> jstr(e.language),
        "country"    -> jstr(e.country),
        "position"   -> e.position.map(Json.fromInt).getOrElse(Json.Null),
        "list_name"  -> jstr(e.listName),
        "dwell_ms"   -> e.dwellMs.map(Json.fromLong).getOrElse(Json.Null),
        "ts"         -> jstr(e.ts)
      )
      .noSpaces

  /** Publish a batch, fire-and-forget. Never throws — a publish failure is
    * logged and swallowed so it can't affect the event write path. */
  def publish(events: List[UserEventExport]): Unit =
    try
      events.foreach { e =>
        val record = new ProducerRecord[String, String](topic, e.clientId.toString, toJson(e))
        producer.send(
          record,
          (_, ex) => if (ex != null) logger.warn(s"user.events publish failed: ${ex.getMessage}")
        )
      }
    catch {
      case ex: Exception =>
        logger.warn(s"user.events publish batch failed (${events.size} events): ${ex.getMessage}")
    }

  def close(): Unit =
    try producer.close()
    catch { case _: Exception => () }
}
