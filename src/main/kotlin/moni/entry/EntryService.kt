package moni.entry

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Service
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdRepository
import moni.dataStore.IDataStoreClient
import moni.household.HouseholdService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Entry
import moni.models.internal.Necessity
import java.time.LocalDate
import java.util.*

@Service
class EntryService(
    private val entryRepository: EntryRepository,
    private val householdRepository: HouseholdRepository,
    private val dataStoreClient: IDataStoreClient,
    private val currencyConversionService: CurrencyConversionService,
    private val householdService: HouseholdService,
) {
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
        val currentNames = resolveCurrentNames(entries.map { it.userId }.distinct())

        return entries.map { entry ->
            entry.copy(
                amount = currencyConversionService.convertAmount(entry.amount, targetCurrency),
                authorName = currentNames[entry.userId] ?: entry.authorName
            )
        }
    }

    /** Fetches the current name for each unique userId concurrently. */
    private suspend fun resolveCurrentNames(userIds: List<UUID>): Map<UUID, String> = coroutineScope {
        userIds.map { id ->
            async {
                try { id to dataStoreClient.getUserById(id).name }
                catch (_: Exception) { id to null }
            }
        }.awaitAll().mapNotNull { (id, name) -> name?.let { id to it } }.toMap()
    }

    suspend fun createEntries(
        userId: UUID,
        requests: List<CreateEntryData>
    ): List<Entry> {
        val user = dataStoreClient.getUserById(userId)

        val entries = requests.map { req ->
            val householdUUID = req.householdId?.let { UUID.fromString(it) }

            if (householdUUID != null) {
                householdService.assertMembership(userId, householdUUID)
            }

            if (!currencyConversionService.isValidCurrency(req.amount.currency)) {
                throw IllegalArgumentException("Unknown currency code: ${req.amount.currency}")
            }

            Entry(
                entryId = UUID.randomUUID(),
                userId = userId,
                householdId = householdUUID,
                amount = req.amount,
                categoryId = UUID.fromString(req.categoryId),
                date = LocalDate.parse(req.date),
                name = req.name,
                note = req.note ?: "",
                type = req.type,
                necessity = req.necessity,
                authorName = user.name
            )
        }

        entryRepository.batchSave(entries)
        return entries
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
        necessity: Necessity
    ): Entry {
        val user = dataStoreClient.getUserById(userId)

        if (!currencyConversionService.isValidCurrency(amount.currency)) {
            throw IllegalArgumentException("Unknown currency code: ${amount.currency}")
        }

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
            necessity = necessity,
            authorName = user.name
        )

        entryRepository.save(entry)
        return entry
    }

    /** Returns true when [userId] is allowed to edit/delete [entry]. */
    private suspend fun canModifyEntry(entry: Entry, userId: UUID): Boolean =
        if (entry.householdId != null)
            householdRepository.findById(entry.householdId)?.let {
                it.ownerId == userId || entry.userId == userId
            } ?: false
        else entry.userId == userId

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
            throw moni.config.ForbiddenException("Not authorized to update this entry")
        }

        val updated = existing.copy(
            amount = amount ?: existing.amount,
            categoryId = categoryId ?: existing.categoryId,
            date = date ?: existing.date,
            name = name ?: existing.name,
            note = note ?: existing.note,
            necessity = necessity ?: existing.necessity
        )

        entryRepository.save(updated)
        return updated
    }

    suspend fun deleteEntry(entryId: UUID, userId: UUID) {
        val entry = entryRepository.findById(entryId)
            ?: throw NoSuchElementException("Entry not found")

        if (!canModifyEntry(entry, userId)) {
            throw moni.config.ForbiddenException("Not authorized to delete this entry")
        }

        entryRepository.delete(entryId)
    }
}
