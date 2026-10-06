package moni.models.api

import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Necessity
import moni.models.internal.RecurrenceFrequency
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class RecurringEntryResponse(
    val recurringId: UUID,
    val userId: UUID,
    val amount: Amount,
    val categoryId: UUID,
    val name: String,
    val note: String,
    val type: TransactionType,
    val necessity: Necessity,
    val frequency: RecurrenceFrequency,
    val dayOfWeek: Int?,
    val dayOfMonth: Int?,
    val monthOfYear: Int?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val nextPostDate: LocalDate,
    val active: Boolean,
    val createdAt: Instant,
)

fun moni.models.internal.RecurringEntry.toApi() = RecurringEntryResponse(
    recurringId  = recurringId,
    userId       = userId,
    amount       = amount,
    categoryId   = categoryId,
    name         = name,
    note         = note,
    type         = type,
    necessity    = necessity,
    frequency    = frequency,
    dayOfWeek    = dayOfWeek,
    dayOfMonth   = dayOfMonth,
    monthOfYear  = monthOfYear,
    startDate    = startDate,
    endDate      = endDate,
    nextPostDate = nextPostDate,
    active       = active,
    createdAt    = createdAt,
)
