package moni.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import jakarta.annotation.PostConstruct
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import moni.models.internal.PushSubscription
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Service
class WebPushService(
    @Value("\${vapid.publicKey}")  private val vapidPublicKeyB64:  String,
    @Value("\${vapid.privateKey}") private val vapidPrivateKeyB64: String,
    @Value("\${vapid.subject:mailto:moni@localhost}") private val subject: String,
    @Value("\${fcm.projectId:}")    private val fcmProjectId:    String,
    @Value("\${fcm.clientEmail:}")  private val fcmClientEmail:  String,
    @Value("\${fcm.privateKey:}")   private val fcmPrivateKeyRaw: String,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient = OkHttpClient()
    private val logger = LoggerFactory.getLogger(WebPushService::class.java)

    private val PLACEHOLDER = "change-me-default"
    private var vapidKeyPair: KeyPair? = null
    private var fcmPrivateKey: PrivateKey? = null

    // Cached OAuth2 token for FCM v1
    private var fcmAccessToken: String? = null
    private var fcmTokenExpiry: Long = 0L

    val isEnabled: Boolean get() = vapidKeyPair != null

    @PostConstruct
    fun init() {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())

        if (vapidPublicKeyB64 == PLACEHOLDER || vapidPrivateKeyB64 == PLACEHOLDER ||
            vapidPublicKeyB64.isBlank() || vapidPrivateKeyB64.isBlank()
        ) {
            logger.warn("VAPID keys not configured. Web push notifications are disabled.")
            return
        }
        try {
            val pubKeyBytes  = base64Decode(vapidPublicKeyB64)
            val privKeyBytes = base64Decode(vapidPrivateKeyB64)

            val curveSpec = ECNamedCurveTable.getParameterSpec("prime256v1")
            val kf        = KeyFactory.getInstance("EC", "BC")

            val pubPoint = curveSpec.curve.decodePoint(
                if (pubKeyBytes.size == 64) byteArrayOf(0x04) + pubKeyBytes else pubKeyBytes
            )
            val ecSpec  = ECNamedCurveSpec("prime256v1", curveSpec.curve, curveSpec.g, curveSpec.n)
            val ecPoint = ECPoint(pubPoint.xCoord.toBigInteger(), pubPoint.yCoord.toBigInteger())
            val pubKey  = kf.generatePublic(ECPublicKeySpec(ecPoint, ecSpec)) as ECPublicKey

            val privSpec = org.bouncycastle.jce.spec.ECPrivateKeySpec(
                java.math.BigInteger(1, privKeyBytes), curveSpec
            )
            val privKey = kf.generatePrivate(privSpec) as ECPrivateKey

            vapidKeyPair = KeyPair(pubKey, privKey)
            logger.info("Web push service initialised (subject={})", subject)
        } catch (e: Exception) {
            logger.error("Failed to initialise VAPID keys: {}", e.message)
        }

        // Initialise FCM service account private key if configured
        if (fcmProjectId.isNotBlank() && fcmClientEmail.isNotBlank() && fcmPrivateKeyRaw.isNotBlank()) {
            try {
                val pemBody = fcmPrivateKeyRaw
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\s".toRegex(), "")
                val keyBytes = Base64.getDecoder().decode(pemBody)
                fcmPrivateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                logger.info("FCM v1 service account configured (project={}, email={})", fcmProjectId, fcmClientEmail)
            } catch (e: Exception) {
                logger.error("Failed to load FCM service account private key: {}", e.message)
            }
        }
    }

    data class PushPayload(val title: String, val body: String)

    /**
     * Send a Web Push notification.
     * - FCM legacy endpoints (fcm.googleapis.com/fcm/send/) → FCM v1 API
     * - All other endpoints (Apple, Mozilla) → RFC 8291/8292 VAPID
     * Returns true on success or transient error, false on 404/410 (subscription gone).
     */
    fun send(sub: PushSubscription, title: String, body: String): Boolean {
        if (vapidKeyPair == null) {
            logger.debug("Web push disabled — skipping for {}", sub.endpoint.take(60))
            return true
        }
        return if (sub.endpoint.contains("fcm.googleapis.com/fcm/send/")) {
            sendViaFcmV1(sub, title, body)
        } else {
            sendViaVapid(sub, title, body)
        }
    }

    // ── FCM v1 API ────────────────────────────────────────────────────────────

    private fun sendViaFcmV1(sub: PushSubscription, title: String, body: String): Boolean {
        val privKey = fcmPrivateKey
        if (privKey == null) {
            logger.warn("FCM service account not configured — falling back to VAPID for {}", sub.endpoint.take(60))
            return sendViaVapid(sub, title, body)
        }
        return try {
            val accessToken = getFcmAccessToken(privKey)

            // Extract FCM registration token from the endpoint URL
            // endpoint format: https://fcm.googleapis.com/fcm/send/<registration_token>
            val registrationToken = sub.endpoint.substringAfterLast("/")

            val fcmPayload = mapOf(
                "message" to mapOf(
                    "token" to registrationToken,
                    "notification" to mapOf(
                        "title" to title,
                        "body"  to body,
                    ),
                    "webpush" to mapOf(
                        "notification" to mapOf(
                            "title" to title,
                            "body"  to body,
                            "icon"  to "/logo.png",
                        ),
                        "headers" to mapOf("TTL" to "86400"),
                    ),
                )
            )

            val requestBody = objectMapper.writeValueAsString(fcmPayload)
            val url = "https://fcm.googleapis.com/v1/projects/$fcmProjectId/messages:send"

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .build()

            logger.info("FCM v1 request — project: {} token: {}...", fcmProjectId, registrationToken.take(20))
            val response     = httpClient.newCall(request).execute()
            val statusCode   = response.code
            val responseBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
            response.close()

            logger.info("FCM v1 sent to {} — HTTP {} body: {}", registrationToken.take(20), statusCode, responseBody)
            if (statusCode == 404) {
                logger.info("FCM registration token gone (404): {}", sub.endpoint)
                return false
            }
            if (statusCode !in 200..299) {
                logger.warn("FCM v1 non-2xx {} for {}", statusCode, registrationToken.take(20))
            }
            true
        } catch (e: Exception) {
            logger.error("Failed to send FCM v1 push: {}", e.message)
            true
        }
    }

    /**
     * Get a cached OAuth2 access token for the FCM v1 API using the service account.
     * Tokens are valid for 1 hour — we cache and refresh when within 5 minutes of expiry.
     */
    private fun getFcmAccessToken(privateKey: PrivateKey): String {
        val now = System.currentTimeMillis()
        if (fcmAccessToken != null && now < fcmTokenExpiry - TimeUnit.MINUTES.toMillis(5)) {
            return fcmAccessToken!!
        }

        val expiry = Date(now + TimeUnit.HOURS.toMillis(1))
        val jwt = Jwts.builder()
            .setIssuer(fcmClientEmail)
            .setSubject(fcmClientEmail)
            .setAudience("https://oauth2.googleapis.com/token")
            .setIssuedAt(Date(now))
            .setExpiration(expiry)
            .claim("scope", "https://www.googleapis.com/auth/firebase.messaging")
            .signWith(privateKey, SignatureAlgorithm.RS256)
            .compact()

        val tokenRequestBody = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(tokenRequestBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val response     = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("Empty token response")
        response.close()

        if (!response.isSuccessful) throw RuntimeException("OAuth2 token request failed: $responseBody")

        val tokenData    = objectMapper.readValue<Map<String, Any>>(responseBody)
        val accessToken  = tokenData["access_token"] as? String
            ?: throw RuntimeException("No access_token in response: $responseBody")

        fcmAccessToken = accessToken
        fcmTokenExpiry = now + TimeUnit.HOURS.toMillis(1)
        logger.info("FCM OAuth2 access token refreshed")

        return accessToken
    }

    // ── RFC 8291/8292 VAPID ───────────────────────────────────────────────────

    private fun sendViaVapid(sub: PushSubscription, title: String, body: String): Boolean {
        val keyPair = vapidKeyPair ?: return true
        return try {
            val payload     = objectMapper.writeValueAsString(PushPayload(title, body)).toByteArray()
            val encrypted   = encrypt(payload, sub.p256dh, sub.auth)
            val vapidHeader = buildVapidHeader(sub.endpoint, keyPair)

            val request = Request.Builder()
                .url(sub.endpoint)
                .post(encrypted.toRequestBody("application/octet-stream".toMediaType()))
                .header("Authorization",    vapidHeader)
                .header("Content-Encoding", "aes128gcm")
                .header("TTL",              "86400")
                .build()

            logger.info("VAPID push request — endpoint: {}", sub.endpoint.take(60))
            val response     = httpClient.newCall(request).execute()
            val statusCode   = response.code
            val responseBody = try { response.body?.string() ?: "" } catch (_: Exception) { "" }
            response.close()

            logger.info("VAPID push sent to {} — HTTP {} body: {}", sub.endpoint.take(60), statusCode, responseBody)
            if (statusCode == 410 || statusCode == 404) {
                logger.info("Push subscription gone ({}): {}", statusCode, sub.endpoint)
                return false
            }
            if (statusCode !in 200..299) {
                logger.warn("VAPID push non-2xx {} for {}", statusCode, sub.endpoint.take(60))
            }
            true
        } catch (e: Exception) {
            logger.error("Failed to send VAPID push to {}: {}", sub.endpoint.take(60), e.message)
            true
        }
    }

    // ── RFC 8291 aes128gcm encryption ────────────────────────────────────────

    private fun encrypt(plaintext: ByteArray, p256dhB64: String, authB64: String): ByteArray {
        val p256dhBytes = base64Decode(p256dhB64)
        val authBytes   = base64Decode(authB64)

        val curveSpec   = ECNamedCurveTable.getParameterSpec("prime256v1")
        val kf          = KeyFactory.getInstance("EC", "BC")
        val pubPoint    = curveSpec.curve.decodePoint(
            if (p256dhBytes.size == 64) byteArrayOf(0x04) + p256dhBytes else p256dhBytes
        )
        val ecSpec      = ECNamedCurveSpec("prime256v1", curveSpec.curve, curveSpec.g, curveSpec.n)
        val ecPoint     = ECPoint(pubPoint.xCoord.toBigInteger(), pubPoint.yCoord.toBigInteger())
        val receiverPub = kf.generatePublic(ECPublicKeySpec(ecPoint, ecSpec)) as ECPublicKey

        val kpg       = KeyPairGenerator.getInstance("EC", "BC")
        kpg.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"), SecureRandom())
        val senderKp  = kpg.generateKeyPair()
        val senderPub = senderKp.public as ECPublicKey

        val ka = KeyAgreement.getInstance("ECDH", "BC")
        ka.init(senderKp.private)
        ka.doPhase(receiverPub, true)
        val sharedSecret = ka.generateSecret()

        val salt            = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val senderPubBytes  = encodePublicKey(senderPub)
        val receiverPubBytes = if (p256dhBytes.size == 64) byteArrayOf(0x04) + p256dhBytes else p256dhBytes

        val prk        = hkdfExtract(authBytes, sharedSecret)
        val keyInfo    = buildInfo(receiverPubBytes, senderPubBytes)
        val ikm        = hkdfExpand(prk, keyInfo, 32)
        val prkAes     = hkdfExtract(salt, ikm)
        val contentKey = hkdfExpand(prkAes, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1), 16)
        val nonce      = hkdfExpand(prkAes, "Content-Encoding: nonce\u0000".toByteArray()     + byteArrayOf(1), 12)

        val padded     = plaintext + byteArrayOf(0x02)
        val cipher     = Cipher.getInstance("AES/GCM/NoPadding", "BC")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(padded)

        val rs     = 4096
        val header = ByteBuffer.allocate(16 + 4 + 1 + senderPubBytes.size)
            .put(salt)
            .putInt(rs)
            .put(senderPubBytes.size.toByte())
            .put(senderPubBytes)
            .array()

        return header + ciphertext
    }

    private fun buildInfo(receiverKey: ByteArray, senderKey: ByteArray): ByteArray =
        "WebPush: info\u0000".toByteArray() + receiverKey + senderKey

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        return mac.doFinal().copyOf(length)
    }

    private fun encodePublicKey(pub: ECPublicKey): ByteArray {
        val x = pub.w.affineX.toByteArray().let { if (it.size == 33) it.drop(1).toByteArray() else it.padStart(32) }
        val y = pub.w.affineY.toByteArray().let { if (it.size == 33) it.drop(1).toByteArray() else it.padStart(32) }
        return byteArrayOf(0x04) + x + y
    }

    private fun ByteArray.padStart(size: Int): ByteArray =
        if (this.size >= size) this else ByteArray(size - this.size) + this

    // ── RFC 8292 VAPID JWT ────────────────────────────────────────────────────

    private fun buildVapidHeader(endpoint: String, keyPair: KeyPair): String {
        val uri      = URI(endpoint)
        val audience = "${uri.scheme}://${uri.host}"
        val expiry   = Date(System.currentTimeMillis() + 12 * 3600 * 1000)

        val jwt = Jwts.builder()
            .setAudience(audience)
            .setSubject(subject)
            .setExpiration(expiry)
            .signWith(keyPair.private, SignatureAlgorithm.ES256)
            .compact()

        val pubKeyB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(encodePublicKey(keyPair.public as ECPublicKey))

        return "vapid t=$jwt,k=$pubKeyB64"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun base64Decode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s.trimEnd('=').padEnd(s.length + (4 - s.length % 4) % 4, '='))
}
