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
    // Must be set explicitly at persistence time — no default to prevent accidental clock drift
    // between object construction and the DynamoDB write. Always pass Instant.now() at the call site.
    val createdAt: Instant,
)

enum class Necessity {
    NECESSARY,
    OPTIONAL;

    companion object {
        /**
         * Migration-safe deserializer: accepts both the new values (NECESSARY/OPTIONAL)
         * and the legacy values (NEED/WANT) that may still exist in DynamoDB.
         */
        fun fromString(value: String): Necessity = when (value.uppercase()) {
            "NECESSARY", "NEED" -> NECESSARY
            "OPTIONAL",  "WANT" -> OPTIONAL
            else -> throw IllegalArgumentException("Unknown Necessity value: $value")
        }
    }
}
