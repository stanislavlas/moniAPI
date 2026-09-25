package moni.models.internal

import moni.models.TransactionType
import java.time.Instant
import java.util.*

data class Category(
    val categoryId: UUID,
    val householdId: UUID?,          // null = personal or global; non-null = household-owned
    val name: String,
    val emoji: String,
    val color: String,
    val type: TransactionType,
    val isDefault: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val ownerKey: String? = null,    // "user:UUID" | "household:UUID" | "global" — populated by repo
)
