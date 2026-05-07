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
  frequency: Int
)

case class DispatchResponse(
  sent: Int,
  failed: Int,
  newArticles: Int
)

case class User(
  id: String,
  email: String,
  createdAt: String,
  updatedAt: String
)

case class UpsertUserRequest(email: String)

case class LastPreferenceResponse(frequency: Int)
