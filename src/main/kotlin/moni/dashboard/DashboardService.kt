package moni.dashboard

import org.springframework.stereotype.Service
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdRepository
import moni.household.HouseholdService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.api.DashboardResponse
import moni.models.api.NecessaryVsOptionalBreakdown
import moni.models.internal.Entry
import moni.models.internal.Necessity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

/** Sums the amount values of a list of entries. */
private fun List<Entry>.sumAmountValues(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

@Service
class DashboardService(
    private val entryRepository: EntryRepository,
    private val householdRepository: HouseholdRepository,
    private val currencyConversionService: CurrencyConversionService,
    private val householdService: HouseholdService,
) {
    suspend fun getDashboard(
        userId: UUID,
        householdId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
        targetCurrency: String,
    ): DashboardResponse {
        // Get raw entries; also capture the household object to reuse for householdName
        var fetchedHousehold: moni.models.internal.Household? = null
        val rawEntries = if (householdId != null) {
            // Fetch household once — reuse for both membership check and householdName (no second DB call)
            fetchedHousehold = householdRepository.findById(householdId)
                ?: throw NoSuchElementException("Household not found")
            householdService.assertMembership(userId, fetchedHousehold)
            entryRepository.findByHouseholdId(householdId, fromDate, toDate)
        } else {
            entryRepository.findByUserId(userId, fromDate, toDate)
        }

        // Convert all entries to the user's preferred currency
        val entries = rawEntries.map { entry ->
            entry.copy(amount = currencyConversionService.convertAmount(entry.amount, targetCurrency))
        }

        // Calculate totals by type
        val income      = entries.filter { it.type == TransactionType.INCOME      }.sumAmountValues()
        val expenses    = entries.filter { it.type == TransactionType.EXPENSE     }.sumAmountValues()
        val investments = entries.filter { it.type == TransactionType.INVESTMENT  }.sumAmountValues()

        // Calculate needs vs wants
        val needs = entries.filter { it.type == TransactionType.EXPENSE && it.necessity == Necessity.NECESSARY }.sumAmountValues()
        val wants = entries.filter { it.type == TransactionType.EXPENSE && it.necessity == Necessity.OPTIONAL  }.sumAmountValues()

        // Group expenses by category
        val expensesByCategory = entries
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId.toString() }
            .mapValues { (_, categoryEntries) ->
                Amount(categoryEntries.sumAmountValues(), targetCurrency)
            }

        // Get recent entries (last 10)
        val recentEntries = entries
            .sortedByDescending { it.createdAt }
            .take(10)

        // Reuse the already-fetched household — no second DynamoDB call
        val householdName = fetchedHousehold?.name

        return DashboardResponse(
            totalIncome = Amount(income, targetCurrency),
            totalExpenses = Amount(expenses, targetCurrency),
            totalInvestments = Amount(investments, targetCurrency),
            savedAmount = Amount(income - expenses - investments, targetCurrency),
            necessaryVsOptional = NecessaryVsOptionalBreakdown(
                necessary = Amount(needs, targetCurrency),
                optional  = Amount(wants, targetCurrency),
            ),
            expensesByCategory = expensesByCategory,
            recentEntries = recentEntries,
            householdName = householdName
        )
    }
}
