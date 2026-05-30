package com.snelnieuws.model

case class NotificationSubscription(
  deviceId: String,
  apnsToken: String,
  frequency: Int,
  createdAt: String,
  updatedAt: String
)

case class SubscribeRequest(
  deviceId: String,
  apnsToken: String,
  frequency: Int,
  // "production" | "sandbox". Defaults to "production" so iOS builds that
  // predate the field keep working — old binaries can only have shipped
  // production tokens (no sandbox build path existed before this change).
  environment: String = "production",
  // Picker code from Languages.codes (de/fr/it/en/es/pl/nl). Defaults
  // to "en" at the service layer when None; the V25 column also has
  // DEFAULT 'en' so older app builds without this field stay on
  // English without explicit handling.
  notificationLanguage: Option[String] = None
)

case class DispatchResponse(
  sent: Int,
  failed: Int
)

case class User(
  id: String,
  email: Option[String],
  createdAt: String,
  updatedAt: String
)

case class UpsertUserRequest(email: String)

case class LastPreferenceResponse(frequency: Int)

case class RegisterClientRequest(
  clientId: String,
  bundleId: String,
  osVersion: Option[String]
)

case class CategoriesPayload(categories: List[String])

case class FeatureFlag(
  id: Long,
  feature: String,
  isEnabled: Boolean
)

case class BroadcastRequest(text: String)

/** Per-environment broadcast outcome. `enabled` reflects the feature flag
  * read from the DB at request time; `sent`/`failed` come from APNs.
  * `enabled=true, sent=0, failed=0` legitimately means "the flag was on
  * but there were no subscribers in that environment, or the matching
  * APNs client wasn't initialized at boot". */
case class BroadcastEnvResult(
  enabled: Boolean,
  sent: Int,
  failed: Int
)

case class BroadcastResponse(
  production: BroadcastEnvResult,
  sandbox: BroadcastEnvResult
)
