package personalFinance.dashboard

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import personalFinance.auth.JwtAuth
import personalFinance.common.getUserId
import personalFinance.dataStore.IDataStoreClient
import personalFinance.models.api.DashboardResponse
import java.time.LocalDate

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
            dashboardService.getDashboard(
                userId         = userId,
                householdId    = householdId,
                fromDate       = LocalDate.parse(fromDate),
                toDate         = LocalDate.parse(toDate),
                targetCurrency = user.currency,
            )
        }
    }
}
