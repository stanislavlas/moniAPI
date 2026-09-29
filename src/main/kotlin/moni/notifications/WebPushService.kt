package moni.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService
import nl.martijndwars.webpush.Subscription
import org.apache.http.util.EntityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import moni.models.internal.PushSubscription
import java.security.Security

@Service
class WebPushService(
    @Value("\${vapid.publicKey}") private val publicKey: String,
    @Value("\${vapid.privateKey}") private val privateKey: String,
    @Value("\${vapid.subject:mailto:moni@localhost}") private val subject: String,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(WebPushService::class.java)

    private var pushService: PushService? = null

    /** True only when VAPID keys are valid and push is operational. */
    val isEnabled: Boolean get() = pushService != null

    private val PLACEHOLDER = "change-me-default"

    @PostConstruct
    fun init() {
        if (publicKey == PLACEHOLDER || privateKey == PLACEHOLDER ||
            publicKey.isBlank() || privateKey.isBlank()
        ) {
            logger.warn(
                "VAPID keys are not configured (vapid.publicKey / vapid.privateKey). " +
                "Web push notifications are disabled. " +
                "Set VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY to enable them."
            )
            return
        }
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            pushService = PushService(publicKey, privateKey, subject)
            logger.info("Web push service initialised (subject={})", subject)
        } catch (e: Exception) {
            logger.error(
                "Failed to initialise web push service — push notifications disabled. " +
                "Check that VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are valid ECDH P-256 keys. " +
                "Cause: {}", e.message
            )
        }
    }

    data class PushPayload(val title: String, val body: String)

    /**
     * Send a web push notification to a single subscription.
     * Returns true on success, false if the subscription is gone (410/404) — caller should delete it.
     * Returns true (no-op) when push is disabled so callers never delete subscriptions unnecessarily.
     */
    fun send(sub: PushSubscription, title: String, body: String): Boolean {
        val service = pushService ?: run {
            logger.debug("Web push disabled — skipping notification for {}", sub.endpoint.take(60))
            return true
        }
        val payload = objectMapper.writeValueAsString(PushPayload(title, body))
        return try {
            val subscription = Subscription(
                sub.endpoint,
                Subscription.Keys(sub.p256dh, sub.auth)
            )
            val notification = Notification(subscription, payload)
            val response = service.send(notification)
            val statusCode = response.statusLine.statusCode
            logger.info("Push sent to {} — HTTP {}", sub.endpoint.take(60), statusCode)
            if (statusCode == 410 || statusCode == 404) {
                logger.info("Push subscription gone ({}): {}", statusCode, sub.endpoint)
                return false
            }
            if (statusCode !in 200..299) {
                val body = try { EntityUtils.toString(response.entity) } catch (_: Exception) { "" }
                logger.warn("Push send non-2xx {} for endpoint {} — body: {}", statusCode, sub.endpoint.take(60), body)
            }
            true
        } catch (e: Exception) {
            logger.error("Failed to send push to {}: {}", sub.endpoint, e.message)
            true // Don't delete on transient errors — only delete on 410/404
        }
    }
}
