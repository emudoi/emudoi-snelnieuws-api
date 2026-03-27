package com.snelnieuws

import com.typesafe.config.ConfigFactory
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener
import org.slf4j.LoggerFactory

object JettyLauncher {

  private val logger = LoggerFactory.getLogger("com.snelnieuws.JettyLauncher")

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val port   = config.getInt("server.port")

    logger.info(s"Starting SnelNieuws API on port $port...")

    val server  = new Server(port)
    val context = new WebAppContext()

    context.setContextPath("/")
    context.setResourceBase("src/main/webapp")
    context.setInitParameter(ScalatraListener.LifeCycleKey, "com.snelnieuws.ScalatraBootstrap")
    context.addEventListener(new ScalatraListener)
    context.addServlet(classOf[DefaultServlet], "/")

    server.setHandler(context)

    try {
      server.start()
      logger.info(s"SnelNieuws API started successfully on port $port")
      server.join()
    } catch {
      case e: Exception =>
        logger.error("Failed to start SnelNieuws API", e)
        System.err.println(s"FATAL: Failed to start SnelNieuws API: ${e.getMessage}")
        e.printStackTrace(System.err)
        System.exit(1)
    }
  }
}
