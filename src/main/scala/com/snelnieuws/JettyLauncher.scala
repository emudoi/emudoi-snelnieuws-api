package com.snelnieuws

import com.typesafe.config.ConfigFactory
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

object JettyLauncher {
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val port = config.getInt("server.port")

    val server = new Server(port)
    val context = new WebAppContext()

    context.setContextPath("/")
    context.setResourceBase("src/main/webapp")
    context.setEventListeners(Array(new ScalatraListener))
    context.addServlet(classOf[DefaultServlet], "/")

    server.setHandler(context)

    println(s"Starting SnelNieuws API on port $port...")
    server.start()
    server.join()
  }
}
