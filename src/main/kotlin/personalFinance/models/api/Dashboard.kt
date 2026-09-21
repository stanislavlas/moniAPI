package personalFinance.models.api

import personalFinance.models.Amount
import personalFinance.models.internal.Entry

data class DashboardResponse(
    val totalIncome: Amount,
    val totalExpenses: Amount,
    val totalInvestments: Amount,
    val savedAmount: Amount,
    val needsVsWants: NeedsVsWantsBreakdown,
    val expensesByCategory: Map<String, Amount>,
    val recentEntries: List<Entry>,
    val householdName: String?
)

data class NeedsVsWantsBreakdown(
    val needs: Amount,
    val wants: Amount
)
