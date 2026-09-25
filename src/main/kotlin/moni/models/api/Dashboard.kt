package moni.models.api

import moni.models.Amount
import moni.models.internal.Entry

data class DashboardResponse(
    val totalIncome: Amount,
    val totalExpenses: Amount,
    val totalInvestments: Amount,
    val savedAmount: Amount,
    val necessaryVsOptional: NecessaryVsOptionalBreakdown,
    val expensesByCategory: Map<String, Amount>,
    val recentEntries: List<Entry>,
    val householdName: String?
)

data class NecessaryVsOptionalBreakdown(
    val necessary: Amount,
    val optional: Amount,
)
