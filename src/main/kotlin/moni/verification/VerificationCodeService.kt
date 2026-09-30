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
private const val MAX_FAILED_ATTEMPTS = 5

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
    ): VerificationCode {
        // Remove any pre-existing code for this user to prevent orphaned records
        // when generateAndSend is called more than once (e.g. forgotPassword called twice).
        repository.deleteAllForUser(userId)
        val code = String.format("%06d", random.nextInt(1_000_000))
        val verificationCode = VerificationCode(
            code = code,
            userId = userId,
            type = type,
            expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_SECONDS),
        )
        repository.save(verificationCode)
        logger.info("Verification code generated for userId={} type={}", userId, type)
        return verificationCode
    }

    suspend fun validate(code: String, expectedType: VerificationType): VerificationCode {
        val stored = repository.findByCode(code)

        // Penalise bad guesses before throwing so enumeration attacks are rate-limited.
        // When a code exists but is the wrong type, increment the failed-attempt counter
        // so cross-type probes are counted. We do NOT penalise a different type's code —
        // recordFailedAttempt only targets the stored code of the given user, regardless
        // of which type it belongs to. The actual guard lives in recordFailedAttempt itself.
        // NOTE: when stored == null (code doesn't exist at all) we cannot identify the user
        // and therefore cannot apply a per-user throttle. IP-level rate limiting at the
        // infrastructure layer (API Gateway / load balancer) is required to protect this path.
        if (stored == null || stored.type != expectedType) {
            // stored.type != expectedType is the only reachable sub-case when stored != null,
            // because findByCode is an exact-key lookup — a wrong code returns null, not a
            // wrong-typed record.
            if (stored != null) recordFailedAttempt(stored.userId)
            throw IllegalArgumentException("Invalid verification code")
        }

        if (Instant.now().isAfter(stored.expiresAt)) {
            repository.deleteByCode(code)
            throw IllegalArgumentException("Verification code has expired")
        }

        return stored
    }

    /**
     * Increment the failed-attempt counter for the code currently held by [userId].
     * Called when a code is found but is the wrong type (the only reachable bad-guess
     * scenario given that findByCode is an exact-key lookup).
     * Deletes the code and throws if [MAX_FAILED_ATTEMPTS] is reached.
     */
    suspend fun recordFailedAttempt(userId: UUID) {
        val stored = repository.findByUserId(userId) ?: return
        val updated = stored.copy(failedAttempts = stored.failedAttempts + 1)
        if (updated.failedAttempts >= MAX_FAILED_ATTEMPTS) {
            repository.deleteByCode(stored.code)
            throw IllegalArgumentException("Too many failed attempts. Please request a new code.")
        }
        repository.save(updated)
    }

    suspend fun consume(code: String) {
        repository.deleteByCode(code)
    }

    suspend fun deleteAllForUser(userId: UUID) {
        repository.deleteAllForUser(userId)
    }

    /**
     * Resend a verification code given an email, name, userId, and type.
     * Looks up the most recent pending code for this user and enforces rate limiting.
     * If no existing code is found, generates a fresh one.
     */
    suspend fun resendForUser(
        userId: UUID,
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
            expiresAt = Instant.now().plusSeconds(CODE_EXPIRY_SECONDS),
            resendCount = existingCode.resendCount + 1,
        )
        repository.save(newCode)
        logger.info("Verification code resent for userId={} type={}", existingCode.userId, existingCode.type)
        return newCode
    }
}
