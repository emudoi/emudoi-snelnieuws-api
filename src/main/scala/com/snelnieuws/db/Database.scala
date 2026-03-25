package com.snelnieuws.db

import cats.effect.IO
import com.typesafe.config.ConfigFactory
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import cats.effect.unsafe.implicits.global

object Database {
  private val config = ConfigFactory.load()

  val transactor: HikariTransactor[IO] = {
    HikariTransactor.newHikariTransactor[IO](
      config.getString("database.driver"),
      config.getString("database.url"),
      config.getString("database.user"),
      config.getString("database.password"),
      scala.concurrent.ExecutionContext.global
    ).allocated.unsafeRunSync()._1
  }

  def initSchema(): Unit = {
    val createTable = sql"""
      CREATE TABLE IF NOT EXISTS articles (
        id BIGSERIAL PRIMARY KEY,
        author VARCHAR(255),
        title VARCHAR(500) NOT NULL,
        description TEXT,
        url VARCHAR(1000) NOT NULL,
        url_to_image VARCHAR(1000),
        published_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
        content TEXT,
        category VARCHAR(100)
      )
    """.update.run

    val createIndex = sql"""
      CREATE INDEX IF NOT EXISTS idx_articles_category ON articles(category)
    """.update.run

    (createTable *> createIndex).transact(transactor).unsafeRunSync()
  }
}
