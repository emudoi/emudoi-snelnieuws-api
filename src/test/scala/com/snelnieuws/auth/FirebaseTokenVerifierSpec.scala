package com.snelnieuws.auth

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FirebaseTokenVerifierSpec extends AnyWordSpec with Matchers {

  "extractBearerToken" should {
    "strip the Bearer prefix (case-insensitive)" in {
      FirebaseTokenVerifier.extractBearerToken("Bearer abc.def.ghi") shouldBe Some("abc.def.ghi")
      FirebaseTokenVerifier.extractBearerToken("bearer abc.def.ghi") shouldBe Some("abc.def.ghi")
      FirebaseTokenVerifier.extractBearerToken("BEARER abc.def.ghi") shouldBe Some("abc.def.ghi")
    }

    "trim surrounding whitespace" in {
      FirebaseTokenVerifier.extractBearerToken("  Bearer   tok  ") shouldBe Some("tok")
    }

    "accept a raw token without the Bearer prefix" in {
      FirebaseTokenVerifier.extractBearerToken("plain-token") shouldBe Some("plain-token")
    }

    "return None for empty headers" in {
      FirebaseTokenVerifier.extractBearerToken("") shouldBe None
      FirebaseTokenVerifier.extractBearerToken("   ") shouldBe None
      FirebaseTokenVerifier.extractBearerToken("Bearer ") shouldBe None
      FirebaseTokenVerifier.extractBearerToken("Bearer    ") shouldBe None
    }
  }

  "Stub verifier" should {
    val stub = new FirebaseTokenVerifier.Stub(Map(
      "alice-token" -> "uid-alice",
      "bob-token"   -> "uid-bob"
    ))

    "resolve a known bearer token to its uid" in {
      stub.verify("Bearer alice-token") shouldBe Right("uid-alice")
      stub.verify("Bearer bob-token")   shouldBe Right("uid-bob")
    }

    "reject an unknown token" in {
      stub.verify("Bearer mallory-token") shouldBe a[Left[_, _]]
    }

    "reject an empty header" in {
      stub.verify("") shouldBe a[Left[_, _]]
    }
  }

  "RejectAll verifier" should {
    "always return Left regardless of input" in {
      FirebaseTokenVerifier.RejectAll.verify("Bearer anything") shouldBe a[Left[_, _]]
      FirebaseTokenVerifier.RejectAll.verify("")                shouldBe a[Left[_, _]]
    }
  }
}
