package com.snelnieuws

import com.snelnieuws.db.Database
import org.scalatra.LifeCycle
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext
import java.util.concurrent.atomic.AtomicReference

class ScalatraBootstrap extends LifeCycle {

  private val logger        = LoggerFactory.getLogger(classOf[ScalatraBootstrap])
  private val componentsRef = new AtomicReference[Option[Components]](None)

  override def init(context: ServletContext): Unit = {
    logger.info("Initializing snel-nieuws-api servlets...")

    try
      Database.migrate()
    catch {
      case e: Exception =>
        logger.error("Database migration failed — cannot start without tables", e)
        throw e
    }

    val components = Components.default()
    componentsRef.set(Some(components))

    context.mount(components.healthServlet, "/health/*")
    context.mount(components.newsServlet, "/*")

    components.startBackgroundWorkers()

    logger.info("snel-nieuws-api servlets initialized successfully")
  }

  override def destroy(context: ServletContext): Unit = {
    componentsRef.get().foreach(_.close())
    super.destroy(context)
  }
}
