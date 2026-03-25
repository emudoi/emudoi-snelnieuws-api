package com.snelnieuws

import com.snelnieuws.api.NewsServlet
import com.snelnieuws.db.Database
import org.scalatra._
import javax.servlet.ServletContext

class ScalatraBootstrap extends LifeCycle {
  override def init(context: ServletContext): Unit = {
    // Initialize database schema
    Database.initSchema()

    // Mount the API servlet
    context.mount(new NewsServlet, "/*")
  }
}
