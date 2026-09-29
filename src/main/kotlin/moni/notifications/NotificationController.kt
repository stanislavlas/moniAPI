package moni.notifications

import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.*
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.dataStore.PushSubscriptionRepository
import moni.models.internal.PushSubscription
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val jwtAuth: JwtAuth,
    private val pushSubscriptionRepository: PushSubscriptionRepository,
    @Value("\${vapid.publicKey}") private val vapidPublicKey: String,
) {

    /** Public — browser fetches this to build the PushSubscription. */
    @GetMapping("/vapid-public-key")
    fun getVapidPublicKey(): Map<String, String> = mapOf("publicKey" to vapidPublicKey)

    /** Save (or replace) a push subscription for the authenticated user. */
    @PostMapping("/subscribe")
    fun subscribe(
        @RequestHeader("Authorization") authHeader: String,
        @RequestBody request: SubscribeRequest,
    ): Map<String, String> {
        val userId = authHeader.getUserId(jwtAuth)
        val sub = PushSubscription(
            subscriptionId = UUID.randomUUID(),
            userId         = userId,
            endpoint       = request.endpoint,
            p256dh         = request.p256dh,
            auth           = request.auth,
            createdAt      = Instant.now(),
        )
        // Query existing subscriptions BEFORE saving the new one, so we don't
        // rely on the GSI having propagated the new item (GSI is eventually consistent).
        // Then save the new subscription, then delete the stale ones by their known IDs.
        runBlocking {
            val oldIds = pushSubscriptionRepository.getByUserId(userId)
                .map { it.subscriptionId }
            pushSubscriptionRepository.save(sub)
            oldIds.forEach { pushSubscriptionRepository.deleteBySubscriptionId(it) }
        }
        return mapOf("status" to "subscribed")
    }

    /** Unsubscribe a push endpoint for the authenticated user. */
    @DeleteMapping("/subscribe")
    fun unsubscribe(
        @RequestHeader("Authorization") authHeader: String,
        @RequestBody request: UnsubscribeRequest,
    ): Map<String, String> {
        val userId = authHeader.getUserId(jwtAuth)
        runBlocking { pushSubscriptionRepository.deleteByEndpoint(userId, request.endpoint) }
        return mapOf("status" to "unsubscribed")
    }
}

data class SubscribeRequest(
    val endpoint: String,
    val p256dh: String,
    val auth: String,
)

data class UnsubscribeRequest(
    val endpoint: String,
)
