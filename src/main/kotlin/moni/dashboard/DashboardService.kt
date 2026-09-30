package moni.dashboard

import org.springframework.stereotype.Service
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdRepository
import moni.household.HouseholdService
import moni.models.Amount
import moni.models.TransactionType
import moni.models.api.DashboardResponse
import moni.models.api.MemberBreakdown
import moni.models.api.Summary
import moni.models.api.NecessaryVsOptionalBreakdown
import moni.models.api.YearDashboardResponse
import moni.models.internal.Entry
import moni.models.internal.Household
import moni.models.internal.Necessity
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

/** Sums the amount values of a list of entries. */
private fun List<Entry>.sumAmountValues(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

/**
 * Aggregates a list of already-converted entries into a MonthSummary.
 * Used by both getDashboard() and getYearDashboard() to avoid duplication.
 */
private fun aggregateEntries(
    entries: List<Entry>,
    targetCurrency: String,
    memberNameMap: Map<UUID, String>,
    isHousehold: Boolean,
): Summary {
    val income      = entries.filter { it.type == TransactionType.INCOME      }.sumAmountValues()
    val expenses    = entries.filter { it.type == TransactionType.EXPENSE     }.sumAmountValues()
    val investments = entries.filter { it.type == TransactionType.INVESTMENT  }.sumAmountValues()

    val needs = entries.filter { it.type == TransactionType.EXPENSE && it.necessity == Necessity.NECESSARY }.sumAmountValues()
    val wants = entries.filter { it.type == TransactionType.EXPENSE && it.necessity == Necessity.OPTIONAL  }.sumAmountValues()

    val expensesByCategory = entries
        .filter { it.type == TransactionType.EXPENSE }
        .groupBy { it.categoryId.toString() }
        .mapValues { (_, catEntries) -> Amount(catEntries.sumAmountValues(), targetCurrency) }

    val memberBreakdown: List<MemberBreakdown>? = if (isHousehold) {
        entries
            .groupBy { it.userId }
            .map { (memberId, memberEntries) ->
                val mIncome  = memberEntries.filter { it.type == TransactionType.INCOME     }.sumAmountValues()
                val mExpense = memberEntries.filter { it.type == TransactionType.EXPENSE    }.sumAmountValues()
                val mInvest  = memberEntries.filter { it.type == TransactionType.INVESTMENT }.sumAmountValues()
                MemberBreakdown(
                    userId           = memberId.toString(),
                    name             = memberNameMap[memberId] ?: memberId.toString(),
                    totalIncome      = Amount(mIncome,                      targetCurrency),
                    totalExpenses    = Amount(mExpense,                     targetCurrency),
                    totalInvestments = Amount(mInvest,                      targetCurrency),
                    savedAmount      = Amount(mIncome - mExpense - mInvest, targetCurrency),
                )
            }
            .sortedByDescending { it.totalExpenses.value }
    } else null

    return Summary(
        totalIncome         = Amount(income, targetCurrency),
        totalExpenses       = Amount(expenses, targetCurrency),
        totalInvestments    = Amount(investments, targetCurrency),
        savedAmount         = Amount(income - expenses - investments, targetCurrency),
        necessaryVsOptional = NecessaryVsOptionalBreakdown(
            necessary = Amount(needs, targetCurrency),
            optional  = Amount(wants, targetCurrency),
        ),
        expensesByCategory  = expensesByCategory,
        memberBreakdown     = memberBreakdown,
    )
}

@Service
class DashboardService(
    private val entryRepository: EntryRepository,
    private val householdRepository: HouseholdRepository,
    private val currencyConversionService: CurrencyConversionService,
    private val householdService: HouseholdService,
) {
    /**
     * Shared helper: fetches entries for [userId] or [householdId] (with membership check),
     * and also returns the fetched [Household] object for reuse (name, member map).
     */
    private suspend fun fetchEntriesAndHousehold(
        userId: UUID,
        householdId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): Pair<List<Entry>, Household?> {
        return if (householdId != null) {
            val household = householdRepository.findById(householdId)
                ?: throw NoSuchElementException("Household not found")
            householdService.assertMembership(userId, household)
            val entries = entryRepository.findByHouseholdId(householdId, fromDate, toDate)
            Pair(entries, household)
        } else {
            val entries = entryRepository.findByUserId(userId, fromDate, toDate)
            Pair(entries, null)
        }
    }

    suspend fun getDashboard(
        userId: UUID,
        householdId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
        targetCurrency: String,
    ): DashboardResponse {
        val (rawEntries, fetchedHousehold) = fetchEntriesAndHousehold(userId, householdId, fromDate, toDate)

        val entries = rawEntries
            .filter { it.type != TransactionType.UNSUPPORTED }
            .map { entry -> entry.copy(amount = currencyConversionService.convertAmount(entry.amount, targetCurrency)) }

        val memberNameMap: Map<UUID, String> = fetchedHousehold
            ?.members?.associate { it.userId to it.name } ?: emptyMap()

        val summary = aggregateEntries(entries, targetCurrency, memberNameMap, householdId != null)

        return DashboardResponse(
            totalIncome          = summary.totalIncome,
            totalExpenses        = summary.totalExpenses,
            totalInvestments     = summary.totalInvestments,
            savedAmount          = summary.savedAmount,
            necessaryVsOptional  = summary.necessaryVsOptional,
            expensesByCategory   = summary.expensesByCategory,
            memberBreakdown      = summary.memberBreakdown,
            householdName        = fetchedHousehold?.name,
        )
    }

    suspend fun getYearDashboard(
        userId: UUID,
        householdId: UUID?,
        year: Int,
        targetCurrency: String,
    ): YearDashboardResponse {
        val fromDate = LocalDate.of(year, 1, 1)
        val toDate   = LocalDate.of(year, 12, 31)

        val (rawEntries, fetchedHousehold) = fetchEntriesAndHousehold(userId, householdId, fromDate, toDate)

        val entries = rawEntries
            .filter { it.type != TransactionType.UNSUPPORTED }
            .map { entry -> entry.copy(amount = currencyConversionService.convertAmount(entry.amount, targetCurrency)) }

        val memberNameMap: Map<UUID, String> = fetchedHousehold
            ?.members?.associate { it.userId to it.name } ?: emptyMap()

        // Group entries by YYYY-MM using explicit formatter — avoids fragile substring(0,7)
        val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        val byMonth: Map<String, List<Entry>> = entries.groupBy { it.date.format(monthFormatter) }

        // Build all 12 months — months with no entries get zero summaries
        val months: Map<String, Summary> = (1..12).associate { mo ->
            val ym          = "${year}-${mo.toString().padStart(2, '0')}"
            val monthEntries = byMonth[ym] ?: emptyList()
            ym to aggregateEntries(monthEntries, targetCurrency, memberNameMap, householdId != null)
        }

        // Year totals — aggregate all entries (same helper, whole-year list)
        val yearTotals = aggregateEntries(entries, targetCurrency, memberNameMap, householdId != null)

        return YearDashboardResponse(
            year          = year,
            months        = months,
            yearTotals    = yearTotals,
            householdName = fetchedHousehold?.name,
        )
    }
}

