package moni.models.internal

import java.time.Instant
import java.util.UUID

data class PushSubscription(
    val subscriptionId: UUID,
    val userId: UUID,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val createdAt: Instant,
)
