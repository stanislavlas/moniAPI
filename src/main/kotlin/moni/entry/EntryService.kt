package moni.entry

import org.springframework.stereotype.Service
import moni.common.UserResolver
import moni.config.ForbiddenException
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.IDataStoreClient
import moni.household.HouseholdService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Entry
import moni.models.internal.Necessity
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Service
class EntryService(
    private val entryRepository: EntryRepository,
    private val dataStoreClient: IDataStoreClient,
    private val currencyConversionService: CurrencyConversionService,
    private val householdService: HouseholdService,
    private val userResolver: UserResolver,
) {
    /**
     * Returns distinct year integers for all years that contain at least one entry.
     * Sorted ascending. Used to build the year scroller on clients.
     */
    suspend fun getActiveYears(userId: UUID, householdId: UUID?): List<Int> {
        return if (householdId != null) {
            householdService.assertMembership(userId, householdId)
            entryRepository.findYearsByHouseholdId(householdId)
        } else {
            entryRepository.findYearsByUserId(userId)
        }
    }

    /**
     * Returns deduplicated YYYY-MM strings for all months that contain at least one entry.
     * Sorted ascending. Used to build the month scroller on clients.
     * When [limit] > 0, only the [limit] most-recent months are returned.
     */
    suspend fun getActiveMonths(userId: UUID, householdId: UUID?, limit: Int = 0): List<String> {
        return if (householdId != null) {
            householdService.assertMembership(userId, householdId)
            entryRepository.findMonthsByHouseholdId(householdId, limit)
        } else {
            entryRepository.findMonthsByUserId(userId, limit)
        }
    }

    suspend fun getEntries(
        userId: UUID,
        householdId: UUID?,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        targetCurrency: String,
    ): List<Entry> {
        val entries = if (householdId != null) {
            householdService.assertMembership(userId, householdId)
            entryRepository.findByHouseholdId(householdId, fromDate, toDate)
        } else {
            entryRepository.findByUserId(userId, fromDate, toDate)
        }

        // Resolve current user names — authorName is snapshotted at write time and may be stale
        val currentNames = userResolver.resolveNames(entries.map { it.userId }.distinct())

        return entries.map { entry ->
            entry.copy(
                amount = currencyConversionService.convertAmount(entry.amount, targetCurrency),
                authorName = currentNames[entry.userId] ?: entry.authorName
            )
        }
    }

    suspend fun createEntry(
        userId: UUID,
        householdId: UUID?,
        amount: Amount,
        categoryId: UUID,
        date: LocalDate,
        name: String,
        note: String,
        type: TransactionType,
        necessity: Necessity,
        authorName: String,
    ): Entry {

        if (!currencyConversionService.isValidCurrency(amount.currency)) {
            throw IllegalArgumentException("Unknown currency code")
        }

        // Necessity is only meaningful for EXPENSE entries.
        // For INCOME and INVESTMENT entries it is stored as NECESSARY so the field is never
        // null at the DB layer, but callers must filter on type == EXPENSE before using it.
        // The dashboard aggregation already does this — see DashboardService.aggregateEntries.
        val resolvedNecessity = if (type == TransactionType.EXPENSE) necessity else Necessity.NECESSARY

        // If householdId provided, verify user is member
        if (householdId != null) {
            householdService.assertMembership(userId, householdId)
        }

        val entry = Entry(
            entryId = UUID.randomUUID(),
            userId = userId,
            householdId = householdId,
            amount = amount,
            categoryId = categoryId,
            date = date,
            name = name,
            note = note,
            type = type,
            necessity = resolvedNecessity,
            authorName = authorName,
            createdAt = Instant.now(),  // captured here, immediately before save
        )

        entryRepository.save(entry)
        return entry
    }

    /** Returns true when [userId] is allowed to edit/delete [entry]. Only the entry author can modify their own entries. */
    private fun canModifyEntry(entry: Entry, userId: UUID): Boolean = entry.userId == userId

    suspend fun updateEntry(
        entryId: UUID,
        userId: UUID,
        amount: Amount?,
        categoryId: UUID?,
        date: LocalDate?,
        name: String?,
        note: String?,
        necessity: Necessity?
    ): Entry {
        val existing = entryRepository.findById(entryId)
            ?: throw NoSuchElementException("Entry not found")

        if (!canModifyEntry(existing, userId)) {
            throw ForbiddenException("Not authorized to update this entry")
        }

        if (amount != null && !currencyConversionService.isValidCurrency(amount.currency)) {
            throw IllegalArgumentException("Unknown currency code")
        }

        if (name != null && name.isBlank()) {
            throw IllegalArgumentException("name must not be blank")
        }

        val updated = existing.copy(
            amount = amount ?: existing.amount,
            categoryId = categoryId ?: existing.categoryId,
            date = date ?: existing.date,
            name = name ?: existing.name,
            note = note ?: existing.note,
            // Only apply provided necessity when entry is (or stays) an EXPENSE.
            necessity = if (existing.type == TransactionType.EXPENSE) necessity ?: existing.necessity else Necessity.NECESSARY
        )

        entryRepository.save(updated)
        return updated
    }

    suspend fun deleteEntry(entryId: UUID, userId: UUID) {
        val entry = entryRepository.findById(entryId)
            ?: throw NoSuchElementException("Entry not found")

        if (!canModifyEntry(entry, userId)) {
            throw ForbiddenException("Not authorized to delete this entry")
        }

        entryRepository.delete(entryId)
    }
}
