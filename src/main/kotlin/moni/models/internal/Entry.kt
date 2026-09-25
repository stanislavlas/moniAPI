package moni.models.internal

import moni.models.Amount
import moni.models.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.util.*

data class Entry(
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
    val createdAt: Instant = Instant.now()
)

enum class Necessity {
    NEED,
    WANT
}
