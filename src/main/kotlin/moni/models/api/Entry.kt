package moni.models.api

import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Necessity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class EntryResponse(
    val entryId: UUID,
    val userId: UUID,
    val householdId: UUID?,
    val amount: Amount,
    val categoryId: UUID,
    val date: LocalDate,
    val name: String,
    val note: String,
    val type: TransactionType,
    val necessity: Necessity,
    val authorName: String,
    val createdAt: Instant,
)

fun moni.models.internal.Entry.toApi() = EntryResponse(
    entryId     = entryId,
    userId      = userId,
    householdId = householdId,
    amount      = amount,
    categoryId  = categoryId,
    date        = date,
    name        = name,
    note        = note,
    type        = type,
    necessity   = necessity,
    authorName  = authorName,
    createdAt   = createdAt,
)
