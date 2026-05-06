package com.snelnieuws.api

import org.json4s.{DefaultFormats, Formats}
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HealthServletSpec extends AnyWordSpec with ScalatraSuite with Matchers {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  addServlet(new HealthServlet, "/health/*")

  "GET /health" should {
    "return 200 with ok status" in {
      get("/health") {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
        body should include("SnelNieuws API")
      }
    }
  }

  "GET /health/" should {
    "return 200 with ok status" in {
      get("/health/") {
        status shouldBe 200
        body should include("\"status\":\"ok\"")
      }
    }
  }
}
