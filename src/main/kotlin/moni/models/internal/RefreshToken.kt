package moni.models.internal

import java.time.Instant
import java.util.*

data class RefreshToken(
    val tokenId: UUID,
    val userId: UUID,
    val tokenHash: String,
    val tokenPrefix: String,     // first 8 chars of the plain token — used for fast GSI lookup
    val expiresAt: Instant,
    val createdAt: Instant,
    val deviceInfo: String? = null
)
