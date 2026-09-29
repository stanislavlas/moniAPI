package moni.dataStore

import moni.models.internal.User
import java.time.Instant
import java.util.*

interface IDataStoreClient {
    suspend fun putUser(user: User)
    suspend fun getUserByEmail(email: String): User?
    suspend fun getUserById(userId: UUID): User
    suspend fun deleteUser(userId: UUID)
    suspend fun updateUser(
        userId: UUID,
        name: String? = null,
        currency: String? = null,
        email: String? = null,
        notificationsEnabled: Boolean? = null,
        notificationFrequency: String? = null,
        notificationCustomDays: Int? = null,
        notificationTime: String? = null,
        nextTriggerAt: Instant? = null,
    ): User
    suspend fun updateUserPassword(userId: UUID, encodedPassword: String): User
    /** Return all users whose nextTriggerAt is non-null and <= [before], with notificationsEnabled = true. */
    suspend fun getUsersDueForNotification(before: Instant): List<User>
    suspend fun updateNextTriggerAt(userId: UUID, next: Instant)
}
