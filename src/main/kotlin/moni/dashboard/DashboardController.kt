package moni.dashboard

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.dataStore.IDataStoreClient
import moni.models.api.DashboardResponse
import java.time.LocalDate
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/api/dashboard")
class DashboardController(
    private val dashboardService: DashboardService,
    private val jwtAuth: JwtAuth,
    private val dataStoreClient: IDataStoreClient,
) {
    @GetMapping
    fun getDashboard(
        @RequestHeader("Authorization") authorization: String,
        @RequestParam(required = false, defaultValue = "false") household: Boolean,
        @RequestParam fromDate: String,
        @RequestParam toDate: String
    ): DashboardResponse {
        val userId = authorization.getUserId(jwtAuth)

        return runBlocking {
            val user = dataStoreClient.getUserById(userId)
            val householdId = if (household) user.householdId else null
            val parsedFrom = try { LocalDate.parse(fromDate) }
                catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid fromDate format. Expected YYYY-MM-DD, got: $fromDate") }
            val parsedTo   = try { LocalDate.parse(toDate) }
                catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid toDate format. Expected YYYY-MM-DD, got: $toDate") }
            dashboardService.getDashboard(
                userId         = userId,
                householdId    = householdId,
                fromDate       = parsedFrom,
                toDate         = parsedTo,
                targetCurrency = user.currency,
            )
        }
    }
}
