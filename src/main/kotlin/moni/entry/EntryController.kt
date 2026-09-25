package moni.entry

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.common.successResponse
import moni.dataStore.IDataStoreClient
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Entry
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
    @GetMapping
    fun getEntries(
        @RequestHeader("Authorization") authorization: String,
        @RequestParam(required = false) yearMonth: String?,
        @RequestParam(required = false, defaultValue = "false") household: Boolean
    ): List<Entry> {
        val userId = authorization.getUserId(jwtAuth)

        val (fromDate, toDate) = if (yearMonth != null) {
            try {
                val ym = YearMonth.parse(yearMonth)
                val from = ym.atDay(1)
                Pair(from, ym.atEndOfMonth())
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("Invalid yearMonth format. Expected YYYY-MM, got: $yearMonth")
            }
        } else {
            Pair(null, null)
        }

        return runBlocking {
            val user = dataStoreClient.getUserById(userId)
            val householdId = if (household) user.householdId else null
            entryService.getEntries(
                userId         = userId,
                householdId    = householdId,
                fromDate       = fromDate,
                toDate         = toDate,
                targetCurrency = user.currency,
            )
        }
    }

    @PostMapping("/batch")
    fun createEntries(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody requests: List<CreateEntryRequest>
    ): List<Entry> {
        val userId = authorization.getUserId(jwtAuth)

        return runBlocking {
            val user = dataStoreClient.getUserById(userId)
            entryService.createEntries(
                userId = userId,
                requests = requests.map { req ->
                    CreateEntryData(
                        householdId = user.householdId?.toString(),
                        amount      = req.amount,
                        categoryId  = req.categoryId,
                        date        = req.date,
                        name        = req.name,
                        note        = req.note,
                        type        = req.type,
                        necessity   = req.necessity
                    )
                }
            )
        }
    }

    @PostMapping
    fun createEntry(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: CreateEntryRequest
    ): Entry {
        val userId = authorization.getUserId(jwtAuth)

        return runBlocking {
            val user = dataStoreClient.getUserById(userId)
            entryService.createEntry(
                userId      = userId,
                householdId = user.householdId,
                amount      = request.amount,
                categoryId  = UUID.fromString(request.categoryId),
                date        = LocalDate.parse(request.date),
                name        = request.name,
                note        = request.note ?: "",
                type        = request.type,
                necessity   = request.necessity
            )
        }
    }

    @PutMapping("/{id}")
    fun updateEntry(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String,
        @RequestBody request: UpdateEntryRequest
    ): Entry {
        val userId = authorization.getUserId(jwtAuth)

        val categoryUUID = request.categoryId?.let { UUID.fromString(it) }
        val dateLocal = request.date?.let { LocalDate.parse(it) }

        return runBlocking {
            entryService.updateEntry(
                entryId = UUID.fromString(id),
                userId = userId,
                amount = request.amount,
                categoryId = categoryUUID,
                date = dateLocal,
                name = request.name,
                note = request.note,
                necessity = request.necessity
            )
        }
    }

    @DeleteMapping("/{id}")
    fun deleteEntry(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)

        runBlocking {
            entryService.deleteEntry(
                entryId = UUID.fromString(id),
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

/** Shared data carrier used by both single and batch service methods. */
data class CreateEntryData(
    val householdId: String?,
    val amount: Amount,
    val categoryId: String,
    val date: String,
    val name: String,
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
