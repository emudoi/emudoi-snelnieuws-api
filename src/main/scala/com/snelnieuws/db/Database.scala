package com.snelnieuws.db

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import doobie.hikari.HikariTransactor
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

import java.sql.DriverManager

object Database {

  private val logger = LoggerFactory.getLogger(getClass)
  private val config = ConfigFactory.load()

  private val driver   = config.getString("database.driver")
  private val url      = config.getString("database.url")
  private val user     = config.getString("database.user")
  private val password = config.getString("database.password")

  private def createTransactor(d: String, u: String, usr: String, pwd: String): HikariTransactor[IO] = {
    logger.info(s"Creating database connection pool for $u")
    try {
      HikariTransactor
        .newHikariTransactor[IO](d, u, usr, pwd, scala.concurrent.ExecutionContext.global)
        .allocated
        .unsafeRunSync()
        ._1
    } catch {
      case e: Exception =>
        logger.error(s"Failed to create database connection pool for $u: ${e.getMessage}", e)
        throw e
    }
  }

  private var _transactor: HikariTransactor[IO] = _
  private var _configured: Boolean = false
  private var _jdbcUrl: String = url
  private var _dbUser: String = user
  private var _dbPassword: String = password

  def transactor: HikariTransactor[IO] = {
    if (!_configured) {
      _transactor = createTransactor(driver, _jdbcUrl, _dbUser, _dbPassword)
      _configured = true
    }
    _transactor
  }

  def configure(jdbcUrl: String, dbUser: String, dbPassword: String): Unit = {
    _transactor = createTransactor(driver, jdbcUrl, dbUser, dbPassword)
    _jdbcUrl = jdbcUrl
    _dbUser = dbUser
    _dbPassword = dbPassword
    _configured = true
  }

  private def ensureDatabase(): Unit = {
    val uri = new java.net.URI(_jdbcUrl.stripPrefix("jdbc:"))
    val host = uri.getHost
    val port = uri.getPort
    val dbName = uri.getPath.stripPrefix("/").takeWhile(_ != '?')
    val adminUrl = s"jdbc:postgresql://$host:$port/postgres"
    logger.info(s"Checking if database '$dbName' exists...")

    var conn: java.sql.Connection = null
    try {
      conn = DriverManager.getConnection(adminUrl, _dbUser, _dbPassword)
      val rs = conn.createStatement().executeQuery(
        s"SELECT 1 FROM pg_database WHERE datname = '$dbName'"
      )
      if (!rs.next()) {
        logger.info(s"Database '$dbName' not found. Creating...")
        conn.createStatement().execute(s"""CREATE DATABASE "$dbName"""")
        logger.info(s"Database '$dbName' created successfully.")
      } else {
        logger.info(s"Database '$dbName' already exists.")
      }
      rs.close()
    } catch {
      case e: Exception =>
        logger.error(s"Failed to ensure database '$dbName' exists: ${e.getMessage}", e)
        throw e
    } finally {
      if (conn != null) {
        try { conn.close() } catch {
          case e: Exception => logger.warn(s"Failed to close admin connection: ${e.getMessage}", e)
        }
      }
    }
  }

  def migrate(): Unit = {
    try {
      ensureDatabase()
    } catch {
      case e: Exception =>
        logger.error(s"Database creation failed — attempting migration anyway: ${e.getMessage}", e)
    }

    try {
      logger.info("Running database migrations...")
      val result = Flyway.configure()
        .dataSource(_jdbcUrl, _dbUser, _dbPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate()
      logger.info(s"Migrations complete. Applied ${result.migrationsExecuted} migration(s).")
    } catch {
      case e: Exception =>
        logger.error(s"Database migration failed: ${e.getMessage}", e)
        throw e
    }
  }
}
