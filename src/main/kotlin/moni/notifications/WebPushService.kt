package moni.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.google.firebase.messaging.WebpushConfig
import com.google.firebase.messaging.WebpushNotification
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
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Date
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
    @Value("\${fcm.projectId:}")   private val fcmProjectId:   String,
    @Value("\${fcm.clientEmail:}") private val fcmClientEmail: String,
    @Value("\${fcm.privateKey:}")  private val fcmPrivateKeyRaw: String,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient = OkHttpClient()
    private val logger = LoggerFactory.getLogger(WebPushService::class.java)

    private val PLACEHOLDER = "change-me-default"
    private var vapidKeyPair: KeyPair? = null
    private var firebaseMessaging: FirebaseMessaging? = null

    val isEnabled: Boolean get() = vapidKeyPair != null

    @PostConstruct
    fun init() {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())

        // Initialise VAPID keys
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

        // Initialise Firebase Admin SDK for FCM v1
        if (fcmProjectId.isNotBlank() && fcmClientEmail.isNotBlank() && fcmPrivateKeyRaw.isNotBlank()) {
            try {
                // Reconstruct the service account JSON from individual env vars
                val serviceAccountJson = """
                    {
                        "type": "service_account",
                        "project_id": "$fcmProjectId",
                        "client_email": "$fcmClientEmail",
                        "private_key": "${fcmPrivateKeyRaw.replace("\\n", "\n")}",
                        "token_uri": "https://oauth2.googleapis.com/token"
                    }
                """.trimIndent()

                val credentials = GoogleCredentials
                    .fromStream(ByteArrayInputStream(serviceAccountJson.toByteArray()))
                    .createScoped("https://www.googleapis.com/auth/firebase.messaging")

                val options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .setProjectId(fcmProjectId)
                    .build()

                // Only initialise once (guard against hot reload)
                val app = if (FirebaseApp.getApps().isEmpty()) {
                    FirebaseApp.initializeApp(options)
                } else {
                    FirebaseApp.getInstance()
                }

                firebaseMessaging = FirebaseMessaging.getInstance(app)
                logger.info("Firebase Admin SDK initialised (project={})", fcmProjectId)
            } catch (e: Exception) {
                logger.error("Failed to initialise Firebase Admin SDK: {}", e.message)
            }
        }
    }

    data class PushPayload(val title: String, val body: String)

    /**
     * Send a Web Push notification.
     * - FCM legacy endpoints → Firebase Admin SDK (FCM v1)
     * - All other endpoints (Apple, Mozilla) → RFC 8291/8292 VAPID
     * Returns true on success or transient error, false on 404/410 (subscription gone).
     */
    fun send(sub: PushSubscription, title: String, body: String): Boolean {
        if (vapidKeyPair == null) {
            logger.debug("Web push disabled — skipping for {}", sub.endpoint.take(60))
            return true
        }
        return if (sub.endpoint.contains("fcm.googleapis.com/fcm/send/")) {
            sendViaFcm(sub, title, body)
        } else {
            sendViaVapid(sub, title, body)
        }
    }

    // ── Firebase Admin SDK (FCM v1) ───────────────────────────────────────────

    private fun sendViaFcm(sub: PushSubscription, title: String, body: String): Boolean {
        val messaging = firebaseMessaging
        if (messaging == null) {
            logger.warn("Firebase not configured — falling back to VAPID for {}", sub.endpoint.take(60))
            return sendViaVapid(sub, title, body)
        }
        return try {
            val registrationToken = sub.endpoint.substringAfterLast("/")

            val message = Message.builder()
                .setToken(registrationToken)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .setWebpushConfig(
                    WebpushConfig.builder()
                        .setNotification(
                            WebpushNotification.builder()
                                .setTitle(title)
                                .setBody(body)
                                .setIcon("/logo.png")
                                .build()
                        )
                        .build()
                )
                .build()

            val messageId = messaging.send(message)
            logger.info("FCM v1 sent to {}... — messageId: {}", registrationToken.take(20), messageId)
            true
        } catch (e: com.google.firebase.messaging.FirebaseMessagingException) {
            val code = e.messagingErrorCode
            logger.warn("FCM v1 error for {}...: {} — {}", sub.endpoint.take(40), code, e.message)
            val isUnregistered = code == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED
            if (isUnregistered) logger.info("FCM token unregistered, removing: {}", sub.endpoint)
            !isUnregistered
        } catch (e: Exception) {
            logger.error("Failed to send FCM push: {}", e.message)
            true
        }
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

            logger.info("VAPID push — endpoint: {}", sub.endpoint.take(60))
            val response     = httpClient.newCall(request).execute()
            val statusCode   = response.code
            val responseBody = try { response.body.string() } catch (_: Exception) { "" }
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

        val salt             = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val senderPubBytes   = encodePublicKey(senderPub)
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
