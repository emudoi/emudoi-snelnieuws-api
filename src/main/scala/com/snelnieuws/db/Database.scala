package com.snelnieuws.db

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

object Database {

  private val logger = LoggerFactory.getLogger(getClass)
  private val config = ConfigFactory.load()

  private val driver   = config.getString("database.driver")
  private val url      = config.getString("database.url")
  private val user     = config.getString("database.user")
  private val password = config.getString("database.password")

  lazy val transactor: HikariTransactor[IO] = {
    logger.info(s"Creating database connection pool for $url")
    HikariTransactor
      .newHikariTransactor[IO](driver, url, user, password, scala.concurrent.ExecutionContext.global)
      .allocated
      .unsafeRunSync()
      ._1
  }

  def migrate(): Unit = {
    logger.info("Running database migrations...")
    val result = Flyway.configure()
      .dataSource(url, user, password)
      .locations("classpath:db/migration")
      .load()
      .migrate()
    logger.info(s"Migrations complete. Applied ${result.migrationsExecuted} migration(s).")
  }
}
