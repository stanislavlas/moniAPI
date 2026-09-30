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
 * One DynamoDB query covers the whole year; months without data have zero-value summaries.
 */
data class YearDashboardResponse(
    val year: Int,
    val months: Map<String, Summary>,         // key = "YYYY-MM", always 12 entries
    val yearTotals: Summary,                  // aggregate across all 12 months
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
