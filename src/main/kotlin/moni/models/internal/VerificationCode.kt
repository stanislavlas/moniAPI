package moni.models.internal

import java.time.Instant
import java.util.UUID

enum class VerificationType {
    REGISTRATION,
    PASSWORD_RESET,
}

data class VerificationCode(
    val code: String,
    val userId: UUID,
    val type: VerificationType,
    val newEmail: String? = null,
    val expiresAt: Instant,
    val resendCount: Int = 0,
)
