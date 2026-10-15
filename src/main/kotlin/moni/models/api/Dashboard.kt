package moni.models.api

import moni.models.Amount

/**
 * Aggregate summary for a date range. Used as the response for GET /api/dashboard
 * and as yearTotals/monthlyAverages inside YearDashboardResponse.
 */
data class DashboardResponse(
    val totalIncome: Amount,
    val totalExpenses: Amount,
    val totalInvestments: Amount,
    val savedAmount: Amount,
    val necessaryVsOptional: NecessaryVsOptionalBreakdown,
    val expensesByCategory: Map<String, Amount>,
    val memberBreakdown: List<MemberBreakdown>?,   // null when household=false
)

/**
 * Response for GET /api/dashboard/year.
 * Covers the requested date range.
 */
data class YearDashboardResponse(
    val year: Int?,                           // calendar year, or null for multi-year/trailing ranges
    val yearTotals: DashboardResponse,                  // aggregate across all months in range
    val monthlyAverages: DashboardResponse,             // yearTotals divided by activeMonthCount (active months only)
    val activeMonthCount: Int,                // number of months that had any activity
    val householdName: String?,
)

data class NecessaryVsOptionalBreakdown(
    val necessary: Amount,
    val optional: Amount,
)

data class MemberBreakdown(
    val userId: String,
    val name: String,
    val totalIncome: Amount,
    val totalExpenses: Amount,
    val totalInvestments: Amount,
    val savedAmount: Amount,
)
