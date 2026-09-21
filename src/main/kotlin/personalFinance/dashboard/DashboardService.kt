package personalFinance.dashboard

import org.springframework.stereotype.Service
import personalFinance.currency.CurrencyConversionService
import personalFinance.dataStore.EntryRepository
import personalFinance.dataStore.HouseholdRepository
import personalFinance.models.Amount
import personalFinance.models.TransactionType
import personalFinance.models.api.DashboardResponse
import personalFinance.models.api.NeedsVsWantsBreakdown
import personalFinance.models.internal.Entry
import personalFinance.models.internal.Necessity
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

@Service
class DashboardService(
    private val entryRepository: EntryRepository,
    private val householdRepository: HouseholdRepository,
    private val currencyConversionService: CurrencyConversionService,
) {
    suspend fun getDashboard(
        userId: UUID,
        householdId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
        targetCurrency: String,
    ): DashboardResponse {
        // Get raw entries
        val rawEntries = if (householdId != null) {
            // Verify user is member
            val household = householdRepository.findById(householdId)
                ?: throw Exception("Household not found")

            if (!household.members.any { it.userId == userId }) {
                throw Exception("User is not a member of this household")
            }

            entryRepository.findByHouseholdId(householdId, fromDate, toDate)
        } else {
            entryRepository.findByUserId(userId, fromDate, toDate)
        }

        // Convert all entries to the user's preferred currency
        val entries = rawEntries.map { entry ->
            entry.copy(amount = currencyConversionService.convertAmount(entry.amount, targetCurrency))
        }

        val currency = targetCurrency

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
                Amount(total, currency)
            }

        // Get recent entries (last 10)
        val recentEntries = entries
            .sortedByDescending { it.createdAt }
            .take(10)

        // Get household name if applicable
        val householdName = if (householdId != null) {
            householdRepository.findById(householdId)?.name
        } else {
            null
        }

        return DashboardResponse(
            totalIncome = Amount(income, currency),
            totalExpenses = Amount(expenses, currency),
            totalInvestments = Amount(investments, currency),
            savedAmount = Amount(income - expenses - investments, currency),
            needsVsWants = NeedsVsWantsBreakdown(
                needs = Amount(needs, currency),
                wants = Amount(wants, currency)
            ),
            expensesByCategory = expensesByCategory,
            recentEntries = recentEntries,
            householdName = householdName
        )
    }
}
