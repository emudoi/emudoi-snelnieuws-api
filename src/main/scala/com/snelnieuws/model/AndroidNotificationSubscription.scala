package com.snelnieuws.model

/** Wire-format payload for POST /v2/android/notifications/subscribe.
  *
  * Mirrors `SubscribeRequest` (iOS) but carries `fcmToken` instead of
  * `apnsToken` and has no `environment` field — FCM has no sandbox/prod
  * split (Firebase routes by API key, not endpoint host).
  *
  * `deviceId` is generated on the device and is NOT the same as
  * `clientId` (which is the install-level UUID kept in encrypted storage
  * and sent as `X-Client-Key`). Mirroring iOS's two-UUID model keeps the
  * platforms symmetric.
  */
case class AndroidSubscribeRequest(
  deviceId: String,
  fcmToken: String,
  frequency: Int,
  // Picker code from Languages.codes (de/fr/it/en/es/pl/nl). None →
  // 'en' at the service layer.
  notificationLanguage: Option[String] = None
)

case class AndroidNotificationSubscription(
  deviceId: String,
  fcmToken: String,
  frequency: Int,
  createdAt: String,
  updatedAt: String
)

/** Per-environment broadcast result for the Android side. Kept separate
  * from `BroadcastResponse` (iOS) so the iOS response shape stays exactly
  * what the iOS app already deserializes — adding fields would be safe in
  * theory but the user has explicitly asked to keep the two stacks apart.
  */
case class AndroidBroadcastResponse(
  enabled: Boolean,
  sent: Int,
  failed: Int
)
