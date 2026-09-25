package moni.dataStore

import moni.models.internal.User
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
    ): User
    suspend fun updateUserPassword(userId: UUID, encodedPassword: String): User
}
