package moni.notifications

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import moni.dataStore.IDataStoreClient
import moni.dataStore.PushSubscriptionRepository
import moni.models.internal.User
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class NotificationScheduler(
    private val dataStoreClient: IDataStoreClient,
    private val pushSubscriptionRepository: PushSubscriptionRepository,
    private val webPushService: WebPushService,
) {
    private val logger = LoggerFactory.getLogger(NotificationScheduler::class.java)

    @Scheduled(fixedDelay = 60_000) // runs every minute
    fun dispatchDueNotifications() {
        val now = Instant.now()
        val dueUsers = runBlocking { dataStoreClient.getUsersDueForNotification(now) }
        if (dueUsers.isEmpty()) return

        logger.info("Dispatching push notifications to {} user(s)", dueUsers.size)

        for (user in dueUsers) {
            val subscriptions = runBlocking { pushSubscriptionRepository.getByUserId(user.userId) }

            if (subscriptions.isEmpty()) {
                // User has notifications enabled and a due trigger, but no browser subscription.
                // Advance the trigger anyway to avoid re-selecting this user every minute.
                logger.warn("No push subscriptions for due user {} — skipping delivery, advancing trigger", user.userId)
                val next = computeNext(user, now)
                runBlocking { dataStoreClient.updateNextTriggerAt(user.userId, next) }
                continue
            }

            for (sub in subscriptions) {
                val alive = webPushService.send(
                    sub,
                    "Budget reminder",
                    "Don't forget to log your expenses!"
                )
                if (!alive) {
                    runBlocking { pushSubscriptionRepository.deleteBySubscriptionId(sub.subscriptionId) }
                }
            }

            // Advance nextTriggerAt to the next scheduled interval using the same `now`
            val next = computeNext(user, now)
            runBlocking { dataStoreClient.updateNextTriggerAt(user.userId, next) }
            logger.debug("Next trigger for user {}: {}", user.userId, next)
        }
    }

    /**
     * Compute the next UTC Instant based on the user's frequency and notificationTime.
     * [now] is passed in so all users in a batch use a consistent reference time.
     *
     * Weekly and monthly are anchored to the user's preferred HH:mm, not just offset from now,
     * so the notification time doesn't drift over multiple cycles.
     */
    internal fun computeNext(user: User, now: Instant = Instant.now()): Instant {
        val (hour, minute) = parseTime(user.notificationTime)

        // Next occurrence of HH:mm UTC today, or tomorrow if already past
        fun nextDailyOccurrence(): Instant {
            val todayAtTime = now.truncatedTo(ChronoUnit.DAYS)
                .plus(hour.toLong(), ChronoUnit.HOURS)
                .plus(minute.toLong(), ChronoUnit.MINUTES)
            return if (todayAtTime.isAfter(now)) todayAtTime
            else todayAtTime.plus(1, ChronoUnit.DAYS)
        }

        return when (user.notificationFrequency) {
            "daily"   -> nextDailyOccurrence()
            "weekly"  -> nextDailyOccurrence().plus(6, ChronoUnit.DAYS)  // daily + 6 = 7 days total
            "monthly" -> nextDailyOccurrence().plus(29, ChronoUnit.DAYS) // daily + 29 = 30 days total
            "custom"  -> {
                val days = maxOf(1, user.notificationCustomDays)
                now.plus(days.toLong(), ChronoUnit.DAYS)
            }
            else -> nextDailyOccurrence() // fallback: daily
        }
    }

    /**
     * Parse "HH:mm" safely, returning (20, 0) as a fallback on malformed input.
     */
    private fun parseTime(time: String): Pair<Int, Int> {
        return try {
            val parts = time.split(":")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (_: Exception) {
            logger.warn("Malformed notificationTime '{}' — falling back to 20:00", time)
            Pair(20, 0)
        }
    }
}
