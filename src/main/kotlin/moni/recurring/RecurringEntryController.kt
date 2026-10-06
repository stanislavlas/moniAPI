package moni.recurring

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.runBlocking
import moni.auth.JwtAuth
import moni.common.getUser
import moni.common.getUserId
import moni.common.successResponse
import moni.dataStore.IDataStoreClient
import moni.models.Amount
import moni.models.TransactionType
import moni.models.api.RecurringEntryResponse
import moni.models.api.toApi
import moni.models.internal.Necessity
import moni.models.internal.RecurrenceFrequency
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@RestController
@RequestMapping("/api/recurring")
class RecurringEntryController(
    private val recurringEntryService: RecurringEntryService,
    private val jwtAuth: JwtAuth,
    private val dataStoreClient: IDataStoreClient,
) {

    @PostMapping
    fun createRecurringEntry(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: CreateRecurringEntryRequest,
    ): RecurringEntryResponse {
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)

            val categoryUUID = try {
                UUID.fromString(request.categoryId)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid category ID format")
            }

            val startDate = try {
                LocalDate.parse(request.startDate)
            } catch (_: DateTimeParseException) {
                throw IllegalArgumentException("Invalid startDate format. Expected YYYY-MM-DD")
            }

            val endDate = request.endDate?.let {
                try { LocalDate.parse(it) } catch (_: DateTimeParseException) {
                    throw IllegalArgumentException("Invalid endDate format. Expected YYYY-MM-DD")
                }
            }

            recurringEntryService.createRecurringEntry(
                userId      = user.userId,
                amount      = request.amount,
                categoryId  = categoryUUID,
                name        = request.name,
                note        = request.note ?: "",
                type        = request.type,
                necessity   = request.necessity,
                frequency   = request.frequency,
                dayOfWeek   = request.dayOfWeek,
                dayOfMonth  = request.dayOfMonth,
                monthOfYear = request.monthOfYear,
                startDate   = startDate,
                endDate     = endDate,
            ).toApi()
        }
    }

    @GetMapping
    fun getRecurringEntries(
        @RequestHeader("Authorization") authorization: String,
    ): List<RecurringEntryResponse> {
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            recurringEntryService.getRecurringEntries(user.userId).map { it.toApi() }
        }
    }

    @DeleteMapping("/{id}/deactivate")
    fun deactivateRecurringEntry(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String,
    ): RecurringEntryResponse {
        val userId = authorization.getUserId(jwtAuth)
        val recurringId = try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid recurring entry ID format")
        }
        return runBlocking {
            recurringEntryService.deactivateRecurringEntry(recurringId, userId).toApi()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteRecurringEntry(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String,
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        val recurringId = try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid recurring entry ID format")
        }
        runBlocking {
            recurringEntryService.deleteRecurringEntry(recurringId, userId)
        }
        return successResponse()
    }

    /**
     * Manually triggers the scheduler for today — useful for local testing without
     * waiting for the nightly cron. Authenticated so it can't be called anonymously.
     */
    @PostMapping("/trigger")
    fun triggerScheduler(
        @RequestHeader("Authorization") authorization: String,
    ): Map<String, Boolean> {
        authorization.getUserId(jwtAuth) // auth check only — anyone logged in can trigger
        recurringEntryService.postDueRecurringEntries()
        return successResponse()
    }
}

data class CreateRecurringEntryRequest(
    val amount: Amount,
    @field:NotBlank val categoryId: String,
    @field:NotBlank val name: String,
    val note: String?,
    val type: TransactionType,
    val necessity: Necessity,
    val frequency: RecurrenceFrequency,
    /** ISO day of week: 1=Monday … 7=Sunday. Required for WEEKLY. */
    val dayOfWeek: Int?,
    /** Day of month 1–28. Required for MONTHLY and YEARLY. */
    val dayOfMonth: Int?,
    /** Month of year 1–12. Required for YEARLY. */
    val monthOfYear: Int?,
    @field:NotBlank val startDate: String,
    val endDate: String?,
)
