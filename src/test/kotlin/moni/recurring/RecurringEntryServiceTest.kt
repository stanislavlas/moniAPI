package moni.recurring

import io.mockk.*
import kotlinx.coroutines.runBlocking
import moni.config.ForbiddenException
import moni.currency.CurrencyConversionService
import moni.dataStore.IDataStoreClient
import moni.dataStore.RecurringEntryRepository
import moni.entry.EntryService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Necessity
import moni.models.internal.RecurrenceFrequency
import moni.models.internal.RecurringEntry
import moni.models.internal.User
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RecurringEntryServiceTest {

    private val recurringEntryRepository  = mockk<RecurringEntryRepository>()
    private val entryService              = mockk<EntryService>()
    private val dataStoreClient           = mockk<IDataStoreClient>()
    private val currencyConversionService = mockk<CurrencyConversionService>()

    private val service = RecurringEntryService(
        recurringEntryRepository,
        entryService,
        dataStoreClient,
        currencyConversionService,
    )

    private val userId     = UUID.randomUUID()
    private val categoryId = UUID.randomUUID()
    private val eurAmount  = Amount(BigDecimal("1200.00"), "EUR")

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

    private fun makeTemplate(
        nextPostDate: LocalDate = LocalDate.of(2026, 10, 1),
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        dayOfMonth: Int? = 1,
        endDate: LocalDate? = null,
        active: Boolean = true,
    ) = RecurringEntry(
        recurringId  = UUID.randomUUID(),
        userId       = userId,
        amount       = eurAmount,
        categoryId   = categoryId,
        name         = "Rent",
        note         = "",
        type         = TransactionType.EXPENSE,
        necessity    = Necessity.NECESSARY,
        frequency    = frequency,
        dayOfWeek    = null,
        dayOfMonth   = dayOfMonth,
        monthOfYear  = null,
        startDate    = LocalDate.of(2026, 1, 1),
        endDate      = endDate,
        nextPostDate = nextPostDate,
        active       = active,
        createdAt    = Instant.now(),
    )

    // ── createRecurringEntry ─────────────────────────────────────────────────

    @Test
    fun `createRecurringEntry saves template and returns it`() = runBlocking {
        coEvery { currencyConversionService.isValidCurrency("EUR") } returns true
        val saved = slot<RecurringEntry>()
        coEvery { recurringEntryRepository.save(capture(saved)) } returns Unit

        val result = service.createRecurringEntry(
            userId      = userId,
            amount      = eurAmount,
            categoryId  = categoryId,
            name        = "Rent",
            note        = "",
            type        = TransactionType.EXPENSE,
            necessity   = Necessity.NECESSARY,
            frequency   = RecurrenceFrequency.MONTHLY,
            dayOfWeek   = null,
            dayOfMonth  = 1,
            monthOfYear = null,
            startDate   = LocalDate.of(2026, 11, 1),
            endDate     = null,
        )

        assertTrue(result.active)
        assertEquals(LocalDate.of(2026, 11, 1), result.nextPostDate)
        coVerify(exactly = 1) { recurringEntryRepository.save(any()) }
    }

    @Test
    fun `createRecurringEntry rejects unknown currency`() {
        coEvery { currencyConversionService.isValidCurrency("XYZ") } returns false

        assertThrows<IllegalArgumentException> {
            runBlocking {
                service.createRecurringEntry(
                    userId      = userId,
                    amount      = Amount(BigDecimal("100"), "XYZ"),
                    categoryId  = categoryId,
                    name        = "Test",
                    note        = "",
                    type        = TransactionType.EXPENSE,
                    necessity   = Necessity.NECESSARY,
                    frequency   = RecurrenceFrequency.MONTHLY,
                    dayOfWeek   = null,
                    dayOfMonth  = 1,
                    monthOfYear = null,
                    startDate   = LocalDate.of(2026, 11, 1),
                    endDate     = null,
                )
            }
        }
    }

    @Test
    fun `createRecurringEntry rejects WEEKLY without dayOfWeek`() {
        coEvery { currencyConversionService.isValidCurrency("EUR") } returns true

        assertThrows<IllegalArgumentException> {
            runBlocking {
                service.createRecurringEntry(
                    userId      = userId,
                    amount      = eurAmount,
                    categoryId  = categoryId,
                    name        = "Salary",
                    note        = "",
                    type        = TransactionType.INCOME,
                    necessity   = Necessity.NECESSARY,
                    frequency   = RecurrenceFrequency.WEEKLY,
                    dayOfWeek   = null,   // missing!
                    dayOfMonth  = null,
                    monthOfYear = null,
                    startDate   = LocalDate.of(2026, 11, 3),
                    endDate     = null,
                )
            }
        }
    }

    @Test
    fun `createRecurringEntry forces necessity to NECESSARY for non-EXPENSE types`() = runBlocking {
        coEvery { currencyConversionService.isValidCurrency("EUR") } returns true
        val saved = slot<RecurringEntry>()
        coEvery { recurringEntryRepository.save(capture(saved)) } returns Unit

        service.createRecurringEntry(
            userId      = userId,
            amount      = eurAmount,
            categoryId  = categoryId,
            name        = "Salary",
            note        = "",
            type        = TransactionType.INCOME,
            necessity   = Necessity.OPTIONAL,  // should be overridden
            frequency   = RecurrenceFrequency.MONTHLY,
            dayOfWeek   = null,
            dayOfMonth  = 1,
            monthOfYear = null,
            startDate   = LocalDate.of(2026, 11, 1),
            endDate     = null,
        )

        assertEquals(Necessity.NECESSARY, saved.captured.necessity)
    }

    // ── advanceDate ──────────────────────────────────────────────────────────

    @Test
    fun `advanceDate DAILY adds one day`() {
        val template = makeTemplate(
            nextPostDate = LocalDate.of(2026, 10, 15),
            frequency    = RecurrenceFrequency.DAILY,
            dayOfMonth   = null,
        )
        assertEquals(LocalDate.of(2026, 10, 16), service.advanceDate(template))
    }

    @Test
    fun `advanceDate WEEKLY adds seven days`() {
        val template = makeTemplate(
            nextPostDate = LocalDate.of(2026, 10, 6),  // Tuesday
            frequency    = RecurrenceFrequency.WEEKLY,
            dayOfMonth   = null,
        )
        assertEquals(LocalDate.of(2026, 10, 13), service.advanceDate(template))
    }

    @Test
    fun `advanceDate MONTHLY advances by one month preserving dayOfMonth`() {
        val template = makeTemplate(
            nextPostDate = LocalDate.of(2026, 1, 31),
            frequency    = RecurrenceFrequency.MONTHLY,
            dayOfMonth   = 31,
        )
        // February has 28 days — should clamp to 28
        assertEquals(LocalDate.of(2026, 2, 28), service.advanceDate(template))
    }

    @Test
    fun `advanceDate YEARLY advances by one year`() {
        val template = makeTemplate(
            nextPostDate = LocalDate.of(2026, 3, 1),
            frequency    = RecurrenceFrequency.YEARLY,
            dayOfMonth   = 1,
        )
        assertEquals(LocalDate.of(2027, 3, 1), service.advanceDate(template))
    }

    // ── postTemplate ─────────────────────────────────────────────────────────

    @Test
    fun `postTemplate posts entry and advances nextPostDate`() = runBlocking {
        stubUser()
        val template = makeTemplate(nextPostDate = LocalDate.of(2026, 10, 1))
        val today    = LocalDate.of(2026, 10, 1)
        val savedSlots = mutableListOf<RecurringEntry>()

        coEvery { entryService.createEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk()
        coEvery { recurringEntryRepository.save(capture(savedSlots)) } returns Unit

        service.postTemplate(template, today)

        // Should have saved once with nextPostDate advanced to 2026-11-01
        assertEquals(1, savedSlots.size)
        assertEquals(LocalDate.of(2026, 11, 1), savedSlots[0].nextPostDate)
        coVerify(exactly = 1) { entryService.createEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `postTemplate deactivates template when endDate is reached`() = runBlocking {
        stubUser()
        val template = makeTemplate(
            nextPostDate = LocalDate.of(2026, 10, 1),
            endDate      = LocalDate.of(2026, 9, 30),  // already passed
        )
        val savedSlots = mutableListOf<RecurringEntry>()
        coEvery { recurringEntryRepository.save(capture(savedSlots)) } returns Unit
        coEvery { entryService.createEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk()

        service.postTemplate(template, LocalDate.of(2026, 10, 1))

        assertEquals(1, savedSlots.size)
        assertFalse(savedSlots[0].active)
        coVerify(exactly = 1) { entryService.createEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `postTemplate does not advance nextPostDate when entryService throws`() = runBlocking {
        stubUser()
        val template = makeTemplate(nextPostDate = LocalDate.of(2026, 10, 1))
        val today    = LocalDate.of(2026, 10, 1)

        coEvery { entryService.createEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("DynamoDB error")

        // Should not throw — errors are caught internally
        service.postTemplate(template, today)

        // nextPostDate must NOT have been advanced (no save call should happen)
        coVerify(exactly = 0) { recurringEntryRepository.save(any()) }
    }

    // ── deactivateRecurringEntry ─────────────────────────────────────────────

    @Test
    fun `deactivateRecurringEntry sets active=false`() = runBlocking {
        val template = makeTemplate()
        coEvery { recurringEntryRepository.findById(template.recurringId) } returns template
        val saved = slot<RecurringEntry>()
        coEvery { recurringEntryRepository.save(capture(saved)) } returns Unit

        val result = service.deactivateRecurringEntry(template.recurringId, userId)

        assertFalse(result.active)
        assertFalse(saved.captured.active)
    }

    @Test
    fun `deactivateRecurringEntry throws ForbiddenException for wrong user`() {
        val template = makeTemplate()
        coEvery { recurringEntryRepository.findById(template.recurringId) } returns template

        assertThrows<ForbiddenException> {
            runBlocking {
                service.deactivateRecurringEntry(template.recurringId, UUID.randomUUID())
            }
        }
    }
}
