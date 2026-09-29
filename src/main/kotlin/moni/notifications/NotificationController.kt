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
        // Save new subscription first, then delete stale ones.
        // Order matters: if delete ran first, the scheduler could fire between
        // delete and save and find an empty subscription list.
        runBlocking {
            pushSubscriptionRepository.save(sub)
            pushSubscriptionRepository.deleteAllByUserIdExcept(userId, sub.subscriptionId)
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
