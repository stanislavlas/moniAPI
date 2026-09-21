package personalFinance.auth

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import personalFinance.dataStore.RefreshTokenRepository
import personalFinance.models.internal.RefreshToken
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: BCryptPasswordEncoder
) {
    private val secureRandom = SecureRandom()

    suspend fun generateRefreshToken(userId: UUID, deviceInfo: String? = null): String {
        // Generate cryptographically secure random token
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)

        // Hash the token before storing (like password)
        val tokenHash = passwordEncoder.encode(token)

        // Create refresh token with 30-day expiration
        val refreshToken = RefreshToken(
            tokenId = UUID.randomUUID(),
            userId = userId,
            tokenHash = tokenHash,
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
            createdAt = Instant.now(),
            deviceInfo = deviceInfo
        )

        refreshTokenRepository.save(refreshToken)

        // Return the plain token to send to client
        return token
    }

    suspend fun validateAndRotate(token: String): RefreshToken? {
        // Find all tokens and check hash match (DynamoDB doesn't support bcrypt comparison)
        // For production, consider using a different approach or indexing
        val allTokens = findAllTokens()

        val matchingToken = allTokens.firstOrNull { refreshToken ->
            passwordEncoder.matches(token, refreshToken.tokenHash)
        } ?: return null

        // Check if expired
        if (matchingToken.expiresAt.isBefore(Instant.now())) {
            refreshTokenRepository.deleteByTokenHash(matchingToken.tokenHash)
            return null
        }

        return matchingToken
    }

    suspend fun revokeToken(token: String) {
        val allTokens = findAllTokens()
        val matchingToken = allTokens.firstOrNull { refreshToken ->
            passwordEncoder.matches(token, refreshToken.tokenHash)
        }

        matchingToken?.let {
            refreshTokenRepository.deleteByTokenHash(it.tokenHash)
        }
    }

    suspend fun revokeAllUserTokens(userId: UUID) {
        refreshTokenRepository.deleteByUserId(userId)
    }

    suspend fun cleanupExpiredTokens() {
        refreshTokenRepository.deleteExpired()
    }

    // Temporary helper - in production, use a better query strategy
    private suspend fun findAllTokens(): List<RefreshToken> {
        // This is inefficient - should use GSI or better indexing
        // For now, we'll use scan which works for small datasets
        return emptyList() // Placeholder - full implementation would need scan
    }
}
