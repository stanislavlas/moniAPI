package moni.models.internal

import moni.models.Amount
import moni.models.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class RecurrenceFrequency { DAILY, WEEKLY, MONTHLY, YEARLY }

data class RecurringEntry(
    val recurringId: UUID,
    val userId: UUID,
    val amount: Amount,
    val categoryId: UUID,
    val name: String,
    val note: String,
    val type: TransactionType,
    val necessity: Necessity,
    val frequency: RecurrenceFrequency,
    /** For WEEKLY: which day of the week to post (1=Mon … 7=Sun, ISO-8601). */
    val dayOfWeek: Int?,
    /** For MONTHLY/YEARLY: which day of the month to post (1–28). */
    val dayOfMonth: Int?,
    /** For YEARLY: which month of the year to post (1–12). */
    val monthOfYear: Int?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    /** Mutable — updated after each successful posting. Persisted back to DynamoDB. */
    val nextPostDate: LocalDate,
    val active: Boolean,
    val createdAt: Instant,
)
