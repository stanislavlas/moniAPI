package moni.dashboard

import org.springframework.stereotype.Service
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdRepository
import moni.household.HouseholdService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.api.DashboardResponse
import moni.models.api.NeedsVsWantsBreakdown
import moni.models.internal.Entry
import moni.models.internal.Necessity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

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
        val income = entries.filter { it.type == TransactionType.INCOME }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

        val expenses = entries.filter { it.type == TransactionType.EXPENSE }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

        val investments = entries.filter { it.type == TransactionType.INVESTMENT }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

        // Calculate needs vs wants
        val needs = entries.filter { it.type == TransactionType.EXPENSE && it.necessity == Necessity.NEED }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

        val wants = entries.filter { it.type == TransactionType.EXPENSE && it.necessity == Necessity.WANT }
            .fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

        // Group expenses by category
        val expensesByCategory = entries
            .filter { it.type == TransactionType.EXPENSE }
            .groupBy { it.categoryId.toString() }
            .mapValues { (_, categoryEntries) ->
                val total = categoryEntries.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }
                Amount(total, targetCurrency)
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
            needsVsWants = NeedsVsWantsBreakdown(
                needs = Amount(needs, targetCurrency),
                wants = Amount(wants, targetCurrency)
            ),
            expensesByCategory = expensesByCategory,
            recentEntries = recentEntries,
            householdName = householdName
        )
    }
}
