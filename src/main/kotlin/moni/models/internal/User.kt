package moni.models.internal

import moni.models.Currency
import moni.models.api.User
import java.time.Instant
import java.util.UUID

data class User(
    val currency: Currency,
    val email: String,
    val name: String,
    val password: String,
    val userId: UUID,
    val householdId: UUID? = null,
    val householdRole: MemberRole? = null,
    val emailVerified: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val notificationFrequency: String = "daily",   // "daily" | "weekly" | "monthly" | "custom"
    val notificationCustomDays: Int = 1,            // used when frequency == "custom"
    val notificationTime: String = "20:00",         // "HH:mm" in UTC — frontend converts from local time before storing
    val nextTriggerAt: Instant? = null,             // UTC instant when the next push should fire
) {
    fun toApi() = User(
        currency = this.currency,
        email = this.email,
        name = this.name,
        userId = this.userId,
        householdId = this.householdId,
        householdRole = this.householdRole,
        notificationsEnabled = this.notificationsEnabled,
        notificationFrequency = this.notificationFrequency,
        notificationCustomDays = this.notificationCustomDays,
        notificationTime = this.notificationTime,
    )
}
