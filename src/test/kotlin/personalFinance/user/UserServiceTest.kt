package personalFinance.user

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import personalFinance.currency.CurrencyConversionService
import personalFinance.dataStore.IDataStoreClient
import personalFinance.models.internal.User
import java.util.*

class UserServiceTest {

    private val dataStoreClient           = mockk<IDataStoreClient>()
    private val currencyConversionService = mockk<CurrencyConversionService>()
    private val passwordEncoder           = mockk<PasswordEncoder>()

    private val service = UserService(dataStoreClient, currencyConversionService, passwordEncoder)

    private val userId = UUID.randomUUID()

    private fun baseUser() = User(
        userId   = userId,
        name     = "Alice",
        email    = "alice@example.com",
        password = "hashed",
        currency = "EUR",
    )

    @Test
    fun `updateUser saves valid notification preferences`() {
        coEvery { dataStoreClient.getUserById(userId) } returns baseUser()
        coEvery {
            dataStoreClient.updateUser(userId, null, null, null, true, "daily", null, "08:30")
        } returns baseUser().copy(notificationsEnabled = true, notificationFrequency = "daily", notificationTime = "08:30")

        val result = service.updateUser(
            userId                = userId,
            notificationsEnabled  = true,
            notificationFrequency = "daily",
            notificationTime      = "08:30",
        )

        assertEquals(true, result.notificationsEnabled)
        assertEquals("daily", result.notificationFrequency)
        assertEquals("08:30", result.notificationTime)
    }

    @Test
    fun `updateUser rejects invalid frequency`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.updateUser(userId = userId, notificationFrequency = "hourly")
        }
        assertTrue(ex.message!!.contains("Invalid notificationFrequency"))
    }

    @Test
    fun `updateUser rejects malformed notificationTime`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.updateUser(userId = userId, notificationTime = "25:00")
        }
        assertTrue(ex.message!!.contains("HH:mm"))
    }

    @Test
    fun `updateUser rejects notificationTime without colon`() {
        val ex = assertThrows<IllegalArgumentException> {
            service.updateUser(userId = userId, notificationTime = "2000")
        }
        assertTrue(ex.message!!.contains("HH:mm"))
    }

    @Test
    fun `updateUser accepts custom frequency with customDays`() {
        coEvery { dataStoreClient.getUserById(userId) } returns baseUser()
        coEvery {
            dataStoreClient.updateUser(userId, null, null, null, true, "custom", 3, "20:00")
        } returns baseUser().copy(notificationsEnabled = true, notificationFrequency = "custom", notificationCustomDays = 3)

        val result = service.updateUser(
            userId                 = userId,
            notificationsEnabled   = true,
            notificationFrequency  = "custom",
            notificationCustomDays = 3,
            notificationTime       = "20:00",
        )

        assertEquals("custom", result.notificationFrequency)
        assertEquals(3, result.notificationCustomDays)
    }
}
