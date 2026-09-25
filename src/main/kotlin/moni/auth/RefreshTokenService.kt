package moni.auth

import kotlinx.coroutines.runBlocking
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import moni.dataStore.RefreshTokenRepository
import moni.models.internal.RefreshToken
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

private const val TOKEN_PREFIX_LENGTH = 8

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder
) {
    private val secureRandom = SecureRandom()

    suspend fun generateRefreshToken(userId: UUID, deviceInfo: String? = null): String {
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)

        val tokenHash   = passwordEncoder.encode(token)
        val tokenPrefix = token.take(TOKEN_PREFIX_LENGTH)

        val refreshToken = RefreshToken(
            tokenId     = UUID.randomUUID(),
            userId      = userId,
            tokenHash   = tokenHash,
            tokenPrefix = tokenPrefix,
            expiresAt   = Instant.now().plus(30, ChronoUnit.DAYS),
            createdAt   = Instant.now(),
            deviceInfo  = deviceInfo
        )

        refreshTokenRepository.save(refreshToken)
        return token
    }

    /**
     * Validates the given refresh token, revokes it atomically, and returns the stored token.
     * Returns null if the token is invalid or expired.
     * Revoking before issuing new tokens eliminates the rotation race window.
     */
    suspend fun validateAndRevoke(token: String): RefreshToken? {
        val prefix = token.take(TOKEN_PREFIX_LENGTH)
        val candidates = refreshTokenRepository.findByPrefix(prefix)

        val matchingToken = candidates.firstOrNull { refreshToken ->
            passwordEncoder.matches(token, refreshToken.tokenHash)
        } ?: return null

        if (matchingToken.expiresAt.isBefore(Instant.now())) {
            refreshTokenRepository.deleteTokenById(matchingToken.tokenId.toString())
            return null
        }

        // Revoke immediately — before the caller issues a new token
        refreshTokenRepository.deleteTokenById(matchingToken.tokenId.toString())
        return matchingToken
    }

    suspend fun revokeToken(token: String) {
        val prefix = token.take(TOKEN_PREFIX_LENGTH)
        val candidates = refreshTokenRepository.findByPrefix(prefix)

        val matchingToken = candidates.firstOrNull { refreshToken ->
            passwordEncoder.matches(token, refreshToken.tokenHash)
        } ?: return

        refreshTokenRepository.deleteTokenById(matchingToken.tokenId.toString())
    }

    suspend fun revokeAllUserTokens(userId: UUID) {
        refreshTokenRepository.deleteByUserId(userId)
    }

    @Scheduled(cron = "0 0 * * * *")
    fun scheduleCleanupExpiredTokens() {
        runBlocking { cleanupExpiredTokens() }
    }

    suspend fun cleanupExpiredTokens() {
        refreshTokenRepository.deleteExpired()
    }
}
