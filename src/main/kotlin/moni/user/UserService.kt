package moni.user

import kotlinx.coroutines.runBlocking
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import moni.currency.CurrencyConversionService
import moni.dataStore.IDataStoreClient
import moni.models.internal.User
import java.util.*

@Service
class UserService(
    private val dataStoreClient: IDataStoreClient,
    private val currencyConversionService: CurrencyConversionService,
    private val passwordEncoder: PasswordEncoder,
) {

    fun getUser(userId: UUID): User? {
        return try {
            runBlocking { dataStoreClient.getUserById(userId = userId) }
        } catch (_: NoSuchElementException) {
            null
        }
    }

    fun updateUser(
        userId: UUID,
        name: String? = null,
        currency: String? = null,
        email: String? = null,
        currentPassword: String? = null,
        notificationsEnabled: Boolean? = null,
        notificationFrequency: String? = null,
        notificationCustomDays: Int? = null,
        notificationTime: String? = null,
    ): User {
        if (currency != null && !currencyConversionService.isValidCurrency(currency)) {
            throw IllegalArgumentException("Unknown currency code: $currency")
        }

        if (notificationFrequency != null) {
            val valid = setOf("daily", "weekly", "monthly", "custom")
            if (notificationFrequency !in valid) {
                throw IllegalArgumentException("Invalid notificationFrequency: $notificationFrequency")
            }
        }

        if (notificationTime != null) {
            val timeRegex = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            if (!timeRegex.matches(notificationTime)) {
                throw IllegalArgumentException("notificationTime must be HH:mm format")
            }
        }

        if (email != null) {
            if (currentPassword == null) {
                throw IllegalArgumentException("Current password is required to change email")
            }
            val user = runBlocking { dataStoreClient.getUserById(userId) }
            if (!passwordEncoder.matches(currentPassword, user.password)) {
                throw IllegalArgumentException("Incorrect password")
            }
            val normalizedEmail = email.trim().lowercase()
            val existing = runBlocking { dataStoreClient.getUserByEmail(normalizedEmail) }
            if (existing != null && existing.userId != userId) {
                throw IllegalArgumentException("Email address is already in use")
            }
            return runBlocking {
                dataStoreClient.updateUser(
                    userId, name, currency, normalizedEmail,
                    notificationsEnabled, notificationFrequency, notificationCustomDays, notificationTime,
                )
            }
        }

        return runBlocking {
            dataStoreClient.updateUser(
                userId, name, currency, null,
                notificationsEnabled, notificationFrequency, notificationCustomDays, notificationTime,
            )
        }
    }
}
