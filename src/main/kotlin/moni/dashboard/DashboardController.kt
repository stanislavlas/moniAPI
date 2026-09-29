package moni.dashboard

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import moni.auth.JwtAuth
import moni.common.getUser
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
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            val householdId = if (household) user.householdId else null
            val parsedFrom = try { LocalDate.parse(fromDate) }
                catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid fromDate format. Expected YYYY-MM-DD, got: $fromDate") }
            val parsedTo   = try { LocalDate.parse(toDate) }
                catch (_: DateTimeParseException) { throw IllegalArgumentException("Invalid toDate format. Expected YYYY-MM-DD, got: $toDate") }
            if (parsedFrom.isAfter(parsedTo)) throw IllegalArgumentException("fromDate ($fromDate) must not be after toDate ($toDate)")
            dashboardService.getDashboard(
                userId         = user.userId,
                householdId    = householdId,
                fromDate       = parsedFrom,
                toDate         = parsedTo,
                targetCurrency = user.currency,
            )
        }
    }
}
