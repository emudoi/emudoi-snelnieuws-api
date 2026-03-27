package com.snelnieuws

import com.snelnieuws.api.NewsServlet
import com.snelnieuws.db.Database
import org.scalatra.LifeCycle
import javax.servlet.ServletContext
import org.slf4j.LoggerFactory

class ScalatraBootstrap extends LifeCycle {

  private val logger = LoggerFactory.getLogger(classOf[ScalatraBootstrap])

  override def init(context: ServletContext): Unit = {
    logger.info("Initializing snel-nieuws-api servlets...")

    try {
      Database.migrate()
    } catch {
      case e: Exception =>
        logger.error("Database migration failed — cannot start without tables", e)
        throw e
    }

    context.mount(new NewsServlet, "/*")

    logger.info("snel-nieuws-api servlets initialized successfully")
  }

  override def destroy(context: ServletContext): Unit = {
    super.destroy(context)
  }
}
