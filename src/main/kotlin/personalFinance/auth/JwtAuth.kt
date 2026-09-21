package personalFinance.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Component
class JwtAuth(
    @Value("\${jwt.secret}") private val secret: String
){
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
        val tokenUUID = getUserIdFromJWT(jwt)

        return tokenUUID == userId && !isJWTExpired(jwt)
    }

    fun getUserIdFromJWT(jwt: String): UUID {
        val userIdString = Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(jwt)
            .body
            .subject

        if (isJWTExpired(jwt)) throw Exception("JWT Token is expired")

        return UUID.fromString(userIdString)
    }

    private fun isJWTExpired(jwt: String): Boolean {
        val expiration = Jwts.parserBuilder()
            .setSigningKey(getSigningKey())
            .build()
            .parseClaimsJws(jwt)
            .body
            .expiration

        return expiration.before(Date())
    }

    private fun LocalDateTime.toDate() = Date.from(this.atZone(ZoneId.systemDefault()).toInstant())
}