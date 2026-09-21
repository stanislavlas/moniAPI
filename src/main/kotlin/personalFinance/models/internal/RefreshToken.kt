package personalFinance.models.internal

import java.time.Instant
import java.util.*

data class RefreshToken(
    val tokenId: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val deviceInfo: String? = null
)
