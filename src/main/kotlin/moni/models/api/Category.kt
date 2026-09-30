package moni.models.api

import moni.models.TransactionType
import java.util.UUID

data class CategoryResponse(
    val categoryId: UUID,
    val householdId: UUID?,
    val name: String,
    val emoji: String,
    val color: String,
    val type: TransactionType,
    val isDefault: Boolean,
)

fun moni.models.internal.Category.toApi() = CategoryResponse(
    categoryId  = categoryId,
    householdId = householdId,
    name        = name,
    emoji       = emoji,
    color       = color,
    type        = type,
    isDefault   = isDefault,
)
