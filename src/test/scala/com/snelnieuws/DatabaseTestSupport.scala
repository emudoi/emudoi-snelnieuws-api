package com.snelnieuws

import com.dimafeng.testcontainers.PostgreSQLContainer
import com.snelnieuws.db.Database
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.DockerClientFactory
import org.testcontainers.utility.DockerImageName

import java.util.concurrent.atomic.AtomicBoolean

object SharedPostgresContainer {

  private val initialized: AtomicBoolean = new AtomicBoolean(false)
  private val available: AtomicBoolean   = new AtomicBoolean(false)

  lazy val isDockerAvailable: Boolean =
    try DockerClientFactory.instance().isDockerAvailable
    catch { case _: Exception => false }

  def start(): Unit = synchronized {
    if (!initialized.get()) {
      if (isDockerAvailable) {
        val containerDef = PostgreSQLContainer.Def(
          dockerImageName = DockerImageName.parse("postgres:15-alpine"),
          databaseName = "snelnieuws_api_test",
          username = "test",
          password = "test"
        )
        val container = containerDef.start()
        Database.configure(container.jdbcUrl, container.username, container.password)
        Database.migrate()
        available.set(true)
      }
      initialized.set(true)
    }
  }

  def isAvailable: Boolean = {
    start()
    available.get()
  }
}

trait DatabaseTestSupport extends BeforeAndAfterAll { self: Suite =>

  override def beforeAll(): Unit = {
    SharedPostgresContainer.start()
    super.beforeAll()
  }

  def requireDb(): Unit =
    assume(
      SharedPostgresContainer.isAvailable,
      "Skipped: Docker not available — cannot start PostgreSQL testcontainer"
    )
}
