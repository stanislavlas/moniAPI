package moni.recurring

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
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class RecurringEntryService(
    private val recurringEntryRepository: RecurringEntryRepository,
    private val entryService: EntryService,
    private val dataStoreClient: IDataStoreClient,
    private val currencyConversionService: CurrencyConversionService,
) {
    private val logger = LoggerFactory.getLogger(RecurringEntryService::class.java)

    // ── CRUD ─────────────────────────────────────────────────────────────────

    suspend fun createRecurringEntry(
        userId: UUID,
        amount: Amount,
        categoryId: UUID,
        name: String,
        note: String,
        type: TransactionType,
        necessity: Necessity,
        frequency: RecurrenceFrequency,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
        monthOfYear: Int?,
        startDate: LocalDate,
        endDate: LocalDate?,
    ): RecurringEntry {
        if (!currencyConversionService.isValidCurrency(amount.currency)) {
            throw IllegalArgumentException("Unknown currency code")
        }
        validateFrequencyFields(frequency, dayOfWeek, dayOfMonth, monthOfYear)
        if (endDate != null && endDate.isBefore(startDate)) {
            throw IllegalArgumentException("endDate must not be before startDate")
        }

        val resolvedNecessity = if (type == TransactionType.EXPENSE) necessity else Necessity.NECESSARY

        val entry = RecurringEntry(
            recurringId  = UUID.randomUUID(),
            userId       = userId,
            amount       = amount,
            categoryId   = categoryId,
            name         = name,
            note         = note,
            type         = type,
            necessity    = resolvedNecessity,
            frequency    = frequency,
            dayOfWeek    = dayOfWeek,
            dayOfMonth   = dayOfMonth,
            monthOfYear  = monthOfYear,
            startDate    = startDate,
            endDate      = endDate,
            nextPostDate = startDate,
            active       = true,
            createdAt    = Instant.now(),
        )
        recurringEntryRepository.save(entry)
        return entry
    }

    suspend fun getRecurringEntries(userId: UUID): List<RecurringEntry> =
        recurringEntryRepository.findByUserId(userId)

    suspend fun deactivateRecurringEntry(recurringId: UUID, userId: UUID): RecurringEntry {
        val existing = recurringEntryRepository.findById(recurringId)
            ?: throw NoSuchElementException("Recurring entry not found")
        if (existing.userId != userId) throw ForbiddenException("Not authorized to modify this recurring entry")

        val updated = existing.copy(active = false)
        recurringEntryRepository.save(updated)
        return updated
    }

    suspend fun deleteRecurringEntry(recurringId: UUID, userId: UUID) {
        val existing = recurringEntryRepository.findById(recurringId)
            ?: throw NoSuchElementException("Recurring entry not found")
        if (existing.userId != userId) throw ForbiddenException("Not authorized to delete this recurring entry")
        recurringEntryRepository.delete(recurringId)
    }

    // ── Scheduler ────────────────────────────────────────────────────────────

    /**
     * Runs daily at 00:05 UTC. Finds all active recurring entries whose
     * nextPostDate is today or in the past and posts them as real entries.
     */
    @Scheduled(cron = "0 5 0 * * *")
    fun postDueRecurringEntries() {
        runBlocking {
            val today = LocalDate.now()
            logger.info("Recurring entry scheduler running for date: $today")

            val due = recurringEntryRepository.findDueBy(today)
            logger.info("Found ${due.size} recurring entries due for posting")

            for (template in due) {
                postTemplate(template, today)
            }
        }
    }

    /**
     * Posts all pending occurrences of [template] up to and including [today],
     * then advances nextPostDate past today.
     *
     * If the template has an endDate that has passed, it is deactivated.
     */
    internal suspend fun postTemplate(template: RecurringEntry, today: LocalDate) {
        var current = template
        while (current.nextPostDate <= today) {
            // Stop if endDate has been reached
            if (current.endDate != null && current.nextPostDate > current.endDate) {
                val deactivated = current.copy(active = false)
                recurringEntryRepository.save(deactivated)
                logger.info("Deactivated expired recurring entry ${current.recurringId}")
                return
            }

            try {
                val user = dataStoreClient.getUserById(current.userId)
                entryService.createEntry(
                    userId      = current.userId,
                    householdId = user.householdId,
                    amount      = current.amount,
                    categoryId  = current.categoryId,
                    date        = current.nextPostDate,
                    name        = current.name,
                    note        = current.note,
                    type        = current.type,
                    necessity   = current.necessity,
                    authorName  = user.name,
                )
                logger.info("Posted recurring entry ${current.recurringId} for date ${current.nextPostDate} (userId=${current.userId}, householdId=${user.householdId})")
            } catch (e: Exception) {
                logger.error("Failed to post recurring entry ${current.recurringId} for date ${current.nextPostDate}", e)
                return  // Do not advance nextPostDate on failure — retry next run
            }

            val next = advanceDate(current)
            current = current.copy(nextPostDate = next)
            recurringEntryRepository.save(current)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Computes the next post date after the current one based on frequency.
     * For MONTHLY/YEARLY, clamps to the last day of the month if dayOfMonth > month length.
     */
    internal fun advanceDate(entry: RecurringEntry): LocalDate {
        val from = entry.nextPostDate
        return when (entry.frequency) {
            RecurrenceFrequency.DAILY   -> from.plusDays(1)
            RecurrenceFrequency.WEEKLY  -> from.plusWeeks(1)
            RecurrenceFrequency.MONTHLY -> {
                val next = from.plusMonths(1)
                val dom  = entry.dayOfMonth ?: from.dayOfMonth
                next.withDayOfMonth(minOf(dom, next.lengthOfMonth()))
            }
            RecurrenceFrequency.YEARLY  -> {
                val next = from.plusYears(1)
                val dom  = entry.dayOfMonth ?: from.dayOfMonth
                next.withDayOfMonth(minOf(dom, next.lengthOfMonth()))
            }
        }
    }

    private fun validateFrequencyFields(
        frequency: RecurrenceFrequency,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
        monthOfYear: Int?,
    ) {
        when (frequency) {
            RecurrenceFrequency.WEEKLY -> {
                if (dayOfWeek == null || dayOfWeek !in 1..7) {
                    throw IllegalArgumentException("dayOfWeek (1–7) is required for WEEKLY frequency")
                }
            }
            RecurrenceFrequency.MONTHLY -> {
                if (dayOfMonth == null || dayOfMonth !in 1..28) {
                    throw IllegalArgumentException("dayOfMonth (1–28) is required for MONTHLY frequency")
                }
            }
            RecurrenceFrequency.YEARLY -> {
                if (dayOfMonth == null || dayOfMonth !in 1..28) {
                    throw IllegalArgumentException("dayOfMonth (1–28) is required for YEARLY frequency")
                }
                if (monthOfYear == null || monthOfYear !in 1..12) {
                    throw IllegalArgumentException("monthOfYear (1–12) is required for YEARLY frequency")
                }
            }
            RecurrenceFrequency.DAILY -> { /* no extra fields needed */ }
        }
    }
}
