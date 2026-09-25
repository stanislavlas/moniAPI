package moni.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val DEV_ONLY_SECRET = "dev-only-insecure-secret"

@Component
class JwtAuth(
    @Value("\${jwt.secret}") private val secret: String
){
    @PostConstruct
    fun validateSecret() {
        if (secret == DEV_ONLY_SECRET || secret.length < 32) {
            throw IllegalStateException(
                "JWT_SECRET is insecure or missing. " +
                "Set a base64-encoded secret of at least 32 bytes via the JWT_SECRET environment variable."
            )
        }
    }

    // Convert the secret to a SecretKeySpec object
    private fun getSigningKey(): SecretKey {
        val decodedKey = Base64.getDecoder().decode(secret)
        return SecretKeySpec(decodedKey, 0, decodedKey.size, "HmacSHA256")
    }

    fun generateJWT(userId: UUID): String {
        val now = LocalDateTime.now()
        return Jwts.builder()
            .setSubject(userId.toString())
            .setIssuedAt(now.toDate())
            .setExpiration(now.plusMinutes(15).toDate()) // 15 minutes for access token
            .signWith(getSigningKey(), SignatureAlgorithm.HS256)
            .compact()
    }

    fun validateJWT(jwt: String, userId: UUID): Boolean {
        return try {
            val tokenUUID = getUserIdFromJWT(jwt)
            tokenUUID == userId
        } catch (_: Exception) {
            false
        }
    }

    fun getUserIdFromJWT(jwt: String): UUID {
        val userIdString = Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(jwt)
            .body
            .subject

        return UUID.fromString(userIdString)
    }

    private fun LocalDateTime.toDate() = Date.from(this.atZone(ZoneId.systemDefault()).toInstant())
}