package moni.models.api

import moni.models.Amount

/**
 * Per-month slice returned inside YearDashboardResponse.
 * Identical shape to DashboardResponse minus householdName (hoisted to year level).
 */
data class Summary(
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
 * Covers the requested date range; one bucket per calendar month in the range.
 */
data class YearDashboardResponse(
    val year: Int?,                           // calendar year, or null for multi-year/trailing ranges
    val months: Map<String, Summary>,         // key = "YYYY-MM", one entry per month in range
    val yearTotals: Summary,                  // aggregate across all months in range
    val monthlyAverages: Summary,             // yearTotals divided by activeMonthCount (active months only)
    val activeMonthCount: Int,                // number of months that had any activity
    val householdName: String?,
)

data class DashboardResponse(
    val totalIncome: Amount,
    val totalExpenses: Amount,
    val totalInvestments: Amount,
    val savedAmount: Amount,
    val necessaryVsOptional: NecessaryVsOptionalBreakdown,
    val expensesByCategory: Map<String, Amount>,
    val memberBreakdown: List<MemberBreakdown>?,   // null when household=false
    val householdName: String?
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
