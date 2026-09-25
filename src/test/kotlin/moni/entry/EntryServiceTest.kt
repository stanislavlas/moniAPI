package moni.entry

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdRepository
import moni.dataStore.IDataStoreClient
import moni.household.HouseholdService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Entry
import moni.models.internal.Necessity
import moni.models.internal.User
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

class EntryServiceTest {

    private val entryRepository           = mockk<EntryRepository>()
    private val householdRepository       = mockk<HouseholdRepository>()
    private val dataStoreClient           = mockk<IDataStoreClient>()
    private val currencyConversionService = mockk<CurrencyConversionService>()
    private val householdService          = mockk<HouseholdService>()

    private val service = EntryService(
        entryRepository,
        householdRepository,
        dataStoreClient,
        currencyConversionService,
        householdService,
    )

    private val userId = UUID.randomUUID()

    private fun stubUser(name: String = "Alice") {
        coEvery { dataStoreClient.getUserById(userId) } returns User(
            userId      = userId,
            name        = name,
            email       = "alice@example.com",
            password    = "hashed",
            currency    = "EUR",
            householdId = null,
        )
    }

    private fun investmentAmount() = Amount(BigDecimal("500.00"), "EUR")

    @Test
    fun `createEntry persists an INVESTMENT entry and returns it`() = runBlocking {
        stubUser()
        coEvery { currencyConversionService.isValidCurrency("EUR") } returns true
        val saved = slot<Entry>()
        coEvery { entryRepository.save(capture(saved)) } returns Unit

        val result = service.createEntry(
            userId      = userId,
            householdId = null,
            amount      = investmentAmount(),
            categoryId  = UUID.randomUUID(),
            date        = LocalDate.of(2026, 9, 1),
            name        = "ETF purchase",
            note        = "",
            type        = TransactionType.INVESTMENT,
            necessity   = Necessity.NEED,
        )

        assertEquals(TransactionType.INVESTMENT, result.type)
        assertEquals(TransactionType.INVESTMENT, saved.captured.type)
        coVerify(exactly = 1) { entryRepository.save(any()) }
    }

    @Test
    fun `getEntries returns investment entries alongside income and expense`() = runBlocking {
        stubUser()
        val investEntry = Entry(
            entryId     = UUID.randomUUID(),
            userId      = userId,
            householdId = null,
            amount      = investmentAmount(),
            categoryId  = UUID.randomUUID(),
            date        = LocalDate.of(2026, 9, 1),
            name        = "ETF",
            note        = "",
            type        = TransactionType.INVESTMENT,
            necessity   = Necessity.NEED,
            authorName  = "Alice",
        )

        coEvery { entryRepository.findByUserId(userId, any(), any()) } returns listOf(investEntry)
        coEvery { currencyConversionService.convertAmount(any(), "EUR") } answers { firstArg() }

        val result = service.getEntries(
            userId         = userId,
            householdId    = null,
            fromDate       = null,
            toDate         = null,
            targetCurrency = "EUR",
        )

        assertEquals(1, result.size)
        assertEquals(TransactionType.INVESTMENT, result[0].type)
    }
}
