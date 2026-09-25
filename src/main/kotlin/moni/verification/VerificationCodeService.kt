package moni.verification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import moni.dataStore.VerificationCodeRepository
import moni.models.internal.VerificationCode
import moni.models.internal.VerificationType
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

private const val CODE_EXPIRY_SECONDS = 5 * 60L  // 5 minutes
private const val MAX_RESEND_ATTEMPTS = 3

@Service
class VerificationCodeService(
    private val repository: VerificationCodeRepository,
) {
    private val logger = LoggerFactory.getLogger(VerificationCodeService::class.java)
    private val random = SecureRandom()

    suspend fun generateAndSend(
        userId: UUID,
        type: VerificationType,
        email: String,
        name: String,
        newEmail: String? = null,
    ): VerificationCode {
        val code = String.format("%06d", random.nextInt(1_000_000))
        val verificationCode = VerificationCode(
            code = code,
            userId = userId,
            type = type,
            newEmail = newEmail,
            expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_SECONDS),
        )
        repository.save(verificationCode)
        logger.info("VERIFICATION CODE for {} ({}): {}", email, type, code)
        return verificationCode
    }

    suspend fun validate(code: String, expectedType: VerificationType): VerificationCode {
        val stored = repository.findByCode(code)
            ?: throw IllegalArgumentException("Invalid verification code")

        if (stored.type != expectedType) {
            throw IllegalArgumentException("Invalid verification code")
        }

        if (Instant.now().isAfter(stored.expiresAt)) {
            repository.deleteByCode(code)
            throw IllegalArgumentException("Verification code has expired")
        }

        return stored
    }

    suspend fun consume(code: String) {
        repository.deleteByCode(code)
    }

    suspend fun deleteAllForUser(userId: java.util.UUID) {
        repository.deleteAllForUser(userId)
    }

    /**
     * Resend a verification code given an email, name, userId, and type.
     * Looks up the most recent pending code for this user and enforces rate limiting.
     * If no existing code is found, generates a fresh one.
     */
    suspend fun resendForUser(
        userId: java.util.UUID,
        type: VerificationType,
        email: String,
        name: String,
    ): VerificationCode {
        val existing = repository.findByUserId(userId)
        return if (existing != null && existing.type == type) {
            resend(existing, email, name)
        } else {
            generateAndSend(userId, type, email, name)
        }
    }

    /**
     * Re-send a verification code for users who already have an unverified registration.
     * Rate-limited to MAX_RESEND_ATTEMPTS.
     */
    suspend fun resend(
        existingCode: VerificationCode,
        email: String,
        name: String,
    ): VerificationCode {
        if (existingCode.resendCount >= MAX_RESEND_ATTEMPTS) {
            throw IllegalArgumentException("Maximum resend attempts reached. Please try again later.")
        }

        repository.deleteByCode(existingCode.code)
        val newCodeStr = String.format("%06d", random.nextInt(1_000_000))
        val newCode = VerificationCode(
            code = newCodeStr,
            userId = existingCode.userId,
            type = existingCode.type,
            newEmail = existingCode.newEmail,
            expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_SECONDS),
            resendCount = existingCode.resendCount + 1,
        )
        repository.save(newCode)
        logger.info("VERIFICATION CODE for {} ({}): {}", email, existingCode.type, newCodeStr)
        return newCode
    }
}
