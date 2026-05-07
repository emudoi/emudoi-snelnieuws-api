package com.snelnieuws.service

import com.snelnieuws.repository.{NotificationSubscriptionRepository, UserRepository}

class UserService(
  userRepository: UserRepository,
  subscriptionRepository: NotificationSubscriptionRepository
) {
  def upsert(uid: String, email: String): Either[Throwable, Int] =
    userRepository.upsert(uid, email)

  def lastFrequency(uid: String): Either[Throwable, Option[Int]] =
    subscriptionRepository.lastFrequencyByUserId(uid)

  def delete(uid: String): Either[Throwable, Int] =
    userRepository.deleteById(uid)
}
