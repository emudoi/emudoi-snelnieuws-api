package com.snelnieuws.kafka

import com.typesafe.config.{Config, ConfigFactory}

case class SummarizedImportKafkaConfig(
  bootstrapServers: String,
  topic: String,
  groupId: String,
  autoOffsetReset: String,
  enabled: Boolean
)

object SummarizedImportKafkaConfig {

  def load(config: Config = ConfigFactory.load()): SummarizedImportKafkaConfig =
    from(config.getConfig("kafka.summarized-import"))

  /** Eulang summarized import — the `kafka.eulang-import` block (separate topic
    * + consumer group, persisted into eulang_articles). */
  def loadEulang(config: Config = ConfigFactory.load()): SummarizedImportKafkaConfig =
    from(config.getConfig("kafka.eulang-import"))

  private def from(kafka: Config): SummarizedImportKafkaConfig =
    SummarizedImportKafkaConfig(
      bootstrapServers = kafka.getString("bootstrap-servers"),
      topic            = kafka.getString("topic"),
      groupId          = kafka.getString("group-id"),
      autoOffsetReset  = kafka.getString("auto-offset-reset"),
      enabled          = kafka.getBoolean("enabled")
    )
}
