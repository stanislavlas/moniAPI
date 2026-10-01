package moni.entry

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import moni.auth.JwtAuth
import moni.common.getUser
import moni.common.getUserId
import moni.common.successResponse
import moni.dataStore.IDataStoreClient
import moni.models.Amount
import moni.models.TransactionType
import moni.models.api.EntryResponse
import moni.models.api.toApi
import moni.models.internal.Necessity
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.*

@RestController
@RequestMapping("/api/entries")
class EntryController(
    private val entryService: EntryService,
    private val jwtAuth: JwtAuth,
    private val dataStoreClient: IDataStoreClient,
) {
    /**
     * Returns the distinct year integers that contain at least one entry.
     * Clients use this to populate the year scroller without fetching full entry data.
     */
    @GetMapping("/years")
    fun getActiveYears(
        @RequestHeader("Authorization") authorization: String,
        @RequestParam(required = false, defaultValue = "false") household: Boolean
    ): List<Int> {
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            val householdId = if (household) user.householdId else null
            entryService.getActiveYears(userId = user.userId, householdId = householdId)
        }
    }

    /**
     * Returns the distinct YYYY-MM month keys that contain at least one entry.
     * Clients use this to populate the month scroller without fetching full entry data.
     * Optional [limit] restricts to the [limit] most-recent months (0 = no limit).
     */
    @GetMapping("/months")
    fun getActiveMonths(
        @RequestHeader("Authorization") authorization: String,
        @RequestParam(required = false, defaultValue = "false") household: Boolean,
        @RequestParam(required = false, defaultValue = "0") limit: Int
    ): List<String> {
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            val householdId = if (household) user.householdId else null
            entryService.getActiveMonths(userId = user.userId, householdId = householdId, limit = limit)
        }
    }

    @GetMapping
    fun getEntries(
        @RequestHeader("Authorization") authorization: String,
        @RequestParam(required = false) yearMonth: String?,
        @RequestParam(required = false) year: Int?,
        @RequestParam(required = false, defaultValue = "false") household: Boolean
    ): List<EntryResponse> {
        val (fromDate, toDate) = when {
            yearMonth != null -> {
                try {
                    val ym = YearMonth.parse(yearMonth)
                    Pair(ym.atDay(1), ym.atEndOfMonth())
                } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("Invalid yearMonth format. Expected YYYY-MM, got: $yearMonth")
                }
            }
            year != null -> {
                if (year < 1900 || year > 9999) throw IllegalArgumentException("Invalid year: $year")
                Pair(java.time.LocalDate.of(year, 1, 1), java.time.LocalDate.of(year, 12, 31))
            }
            else -> Pair(null, null)
        }

        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            val householdId = if (household) user.householdId else null
            entryService.getEntries(
                userId         = user.userId,
                householdId    = householdId,
                fromDate       = fromDate,
                toDate         = toDate,
                targetCurrency = user.currency,
            ).map { it.toApi() }
        }
    }

    @PostMapping
    fun createEntry(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: CreateEntryRequest,
    ): EntryResponse {
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            val categoryUUID = try {
                UUID.fromString(request.categoryId)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid category ID format")
            }
            entryService.createEntry(
                userId      = user.userId,
                householdId = user.householdId,
                amount      = request.amount,
                categoryId  = categoryUUID,
                date        = try { LocalDate.parse(request.date) } catch (_: java.time.format.DateTimeParseException) {
                    throw IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD")
                },
                name        = request.name,
                note        = request.note ?: "",
                type        = request.type,
                necessity   = request.necessity,
                authorName  = user.name,
            ).toApi()
        }
    }

    @PutMapping("/{id}")
    fun updateEntry(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateEntryRequest
    ): EntryResponse {
        val userId = authorization.getUserId(jwtAuth)

        val entryUUID = try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid entry ID format")
        }
        val categoryUUID = request.categoryId?.let {
            try { UUID.fromString(it) } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid category ID format")
            }
        }
        val dateLocal = request.date?.let {
            try { LocalDate.parse(it) } catch (_: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD")
            }
        }

        return runBlocking {
            entryService.updateEntry(
                entryId = entryUUID,
                userId = userId,
                amount = request.amount,
                categoryId = categoryUUID,
                date = dateLocal,
                name = request.name,
                note = request.note,
                necessity = request.necessity
            ).toApi()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteEntry(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        val entryUUID = try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid entry ID format")
        }

        runBlocking {
            entryService.deleteEntry(
                entryId = entryUUID,
                userId = userId
            )
        }

        return successResponse()
    }
}

data class CreateEntryRequest(
    val amount: Amount,
    @field:NotBlank val categoryId: String,
    @field:NotBlank val date: String,
    @field:NotBlank val name: String,
    val note: String?,
    val type: TransactionType,
    val necessity: Necessity
)

data class UpdateEntryRequest(
    val amount: Amount?,
    val categoryId: String?,
    val date: String?,
    val name: String?,
    val note: String?,
    val necessity: Necessity?
)
