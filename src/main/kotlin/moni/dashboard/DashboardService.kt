package moni.dashboard

import org.springframework.stereotype.Service
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdRepository
import moni.dataStore.IDataStoreClient
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.*

/** Sums the amount values of a list of entries. */
private fun List<Entry>.sumAmountValues(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, entry -> acc + entry.amount.value }

/**
 * Divides every Amount in a Summary by [divisor] to produce a per-month average Summary.
 * A divisor of zero is guarded against by returning the summary unchanged (shouldn't happen in practice).
 */
private fun Summary.divideBy(divisor: Int): Summary {
    if (divisor <= 0) return this
    val d = divisor.toBigDecimal()
    fun Amount.avg() = Amount(value.divide(d, 2, RoundingMode.HALF_UP), currency)
    return Summary(
        totalIncome         = totalIncome.avg(),
        totalExpenses       = totalExpenses.avg(),
        totalInvestments    = totalInvestments.avg(),
        savedAmount         = savedAmount.avg(),
        necessaryVsOptional = NecessaryVsOptionalBreakdown(
            necessary = necessaryVsOptional.necessary.avg(),
            optional  = necessaryVsOptional.optional.avg(),
        ),
        expensesByCategory  = expensesByCategory.mapValues { (_, amt) -> amt.avg() },
        memberBreakdown     = memberBreakdown?.map { m ->
            m.copy(
                totalIncome      = m.totalIncome.avg(),
                totalExpenses    = m.totalExpenses.avg(),
                totalInvestments = m.totalInvestments.avg(),
                savedAmount      = m.savedAmount.avg(),
            )
        },
    )
}

/**
 * Aggregates a list of already-converted entries into a Summary.
 * Used by getDashboard(), getYearDashboard(), and getRecentDashboard().
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
        expensesByCategory = expensesByCategory,
        memberBreakdown    = memberBreakdown,
    )
}

@Service
class DashboardService(
    private val entryRepository: EntryRepository,
    private val householdRepository: HouseholdRepository,
    private val currencyConversionService: CurrencyConversionService,
    private val householdService: HouseholdService,
    private val dataStoreClient: IDataStoreClient,
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

    /** Fetches current names for all household members in parallel by userId. */
    private suspend fun buildMemberNameMap(household: Household?): Map<UUID, String> {
        household ?: return emptyMap()
        return coroutineScope {
            household.members
                .map { m -> async { m.userId to dataStoreClient.getUserById(m.userId).name } }
                .awaitAll()
                .toMap()
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

        val memberNameMap = buildMemberNameMap(fetchedHousehold)

        val summary = aggregateEntries(entries, targetCurrency, memberNameMap, householdId != null)

        return DashboardResponse(
            totalIncome         = summary.totalIncome,
            totalExpenses       = summary.totalExpenses,
            totalInvestments    = summary.totalInvestments,
            savedAmount         = summary.savedAmount,
            necessaryVsOptional = summary.necessaryVsOptional,
            expensesByCategory  = summary.expensesByCategory,
            memberBreakdown     = summary.memberBreakdown,
            householdName       = fetchedHousehold?.name,
        )
    }

    suspend fun getYearDashboard(
        userId: UUID,
        householdId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
        targetCurrency: String,
    ): YearDashboardResponse {
        val (rawEntries, fetchedHousehold) = fetchEntriesAndHousehold(userId, householdId, fromDate, toDate)

        val entries = rawEntries
            .filter { it.type != TransactionType.UNSUPPORTED }
            .map { entry -> entry.copy(amount = currencyConversionService.convertAmount(entry.amount, targetCurrency)) }

        val memberNameMap = buildMemberNameMap(fetchedHousehold)

        // Group entries by YYYY-MM using explicit formatter — avoids fragile substring(0,7)
        val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        val byMonth: Map<String, List<Entry>> = entries.groupBy { it.date.format(monthFormatter) }

        // Build one bucket per month in the requested range — guaranteed contiguous coverage
        val monthCount = ChronoUnit.MONTHS.between(fromDate, toDate).toInt() + 1
        val months: Map<String, Summary> = (0 until monthCount).associate { offset ->
            val ym           = fromDate.plusMonths(offset.toLong()).format(monthFormatter)
            val monthEntries = byMonth[ym] ?: emptyList()
            ym to aggregateEntries(monthEntries, targetCurrency, memberNameMap, householdId != null)
        }

        // Totals — aggregate all entries in the range
        val yearTotals = aggregateEntries(entries, targetCurrency, memberNameMap, householdId != null)

        // Monthly averages — divide totals by the number of months that had any activity
        val activeMonthCount = months.values.count { m ->
            m.totalIncome.value > BigDecimal.ZERO ||
            m.totalExpenses.value > BigDecimal.ZERO ||
            m.totalInvestments.value > BigDecimal.ZERO
        }
        val monthlyAverages = yearTotals.divideBy(activeMonthCount.coerceAtLeast(1))

        return YearDashboardResponse(
            year             = if (fromDate.year == toDate.year) fromDate.year else null,
            months           = months,
            yearTotals       = yearTotals,
            monthlyAverages  = monthlyAverages,
            activeMonthCount = activeMonthCount,
            householdName    = fetchedHousehold?.name,
        )
    }
}

