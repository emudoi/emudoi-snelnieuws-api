package com.snelnieuws.api

import org.json4s.{DefaultFormats, Formats}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

class HealthServlet extends ScalatraServlet with JacksonJsonSupport {

  protected implicit lazy val jsonFormats: Formats = DefaultFormats

  before() {
    contentType = formats("json")
  }

  // GET /health
  get("/") {
    Map("status" -> "ok", "service" -> "SnelNieuws API")
  }
}
