package personalFinance.models.api

import personalFinance.models.Currency
import java.util.*

data class AuthUserResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: User,
)

data class RegistrationPendingResponse(
    val pendingVerification: Boolean = true,
    val userId: UUID,
    val message: String,
)

data class User(
    val currency: Currency,
    val email: String,
    val name: String,
    val userId: UUID,
    val notificationsEnabled: Boolean = false,
    val notificationFrequency: String = "daily",
    val notificationCustomDays: Int = 1,
    val notificationTime: String = "20:00",
)