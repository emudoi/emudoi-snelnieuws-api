package com.snelnieuws.api

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.scalatra.test.scalatest.ScalatraSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Covers the open `/v3/videos/allowed-countries` config endpoint. The route
  * only reads the env-backed allow-list, so the heavyweight collaborators
  * (feed service, repository, client repo) are unused and passed as null. */
class VideosServletV3Spec extends AnyWordSpec with ScalatraSuite with Matchers {

  implicit lazy val jsonFormats: Formats = DefaultFormats

  addServlet(new VideosServletV3(null, null, null, ""), "/v3/videos/*")

  "GET /v3/videos/allowed-countries" should {
    "return the allow-list as a JSON array under allowed_countries" in {
      get("/v3/videos/allowed-countries") {
        status shouldBe 200
        val countries = (parse(body) \ "allowed_countries").extract[List[String]]
        countries should not be empty
        // No env override in the test env → the Netherlands-only default.
        countries shouldBe List("nl")
        all(countries) should fullyMatch regex "^[a-z]{2}$"
      }
    }
  }
}
