package personalFinance.category

import org.springframework.stereotype.Service
import personalFinance.dataStore.CategoryRepository
import personalFinance.models.TransactionType
import personalFinance.models.internal.Category
import java.util.*

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
) {

    /** Returns global defaults + the caller's own (personal or household) categories. */
    suspend fun getCategories(userId: UUID, householdId: UUID?): List<Category> {
        val globals = categoryRepository.findGlobal()
        val owned   = if (householdId != null) categoryRepository.findByHouseholdId(householdId)
                      else categoryRepository.findByUserId(userId)
        return globals + owned
    }

    /**
     * Move all personal custom categories of [userId] into [householdId].
     * Rewrites ownerKey in-place. Global categories are not touched.
     * Called when a user creates or joins a household.
     */
    suspend fun assignToHousehold(userId: UUID, householdId: UUID) {
        categoryRepository.findByUserId(userId).forEach { cat ->
            categoryRepository.save(cat.copy(householdId = householdId))
        }
    }

    /**
     * Write a personal copy of every household category for [userId].
     * Household records are left untouched (remaining members keep them).
     * Global categories need no restore — they are always visible.
     * Called when a member leaves, is removed, or the household is deleted.
     */
    suspend fun restoreToUser(userId: UUID, householdId: UUID) {
        categoryRepository.findByHouseholdId(householdId).forEach { cat ->
            categoryRepository.copyToUser(cat.categoryId, userId)
        }
    }

    suspend fun createCategory(
        userId: UUID,
        householdId: UUID?,
        name: String,
        emoji: String,
        color: String,
        type: TransactionType,
    ): Category {
        val category = Category(
            categoryId  = UUID.randomUUID(),
            householdId = householdId,
            name        = name,
            emoji       = emoji,
            color       = color,
            type        = type,
            isDefault   = false,
        )
        categoryRepository.save(category, if (householdId == null) userId else null)
        return category
    }

    suspend fun updateCategory(
        categoryId: UUID,
        userId: UUID,
        name: String?,
        emoji: String?,
        color: String?,
    ): Category {
        val existing = categoryRepository.findById(categoryId)
            ?: throw Exception("Category not found")
        if (existing.isDefault) throw Exception("Cannot modify a default category")

        val updated = existing.copy(
            name  = name  ?: existing.name,
            emoji = emoji ?: existing.emoji,
            color = color ?: existing.color,
        )
        categoryRepository.save(updated, if (existing.householdId == null) userId else null)
        return updated
    }

    suspend fun deleteCategory(categoryId: UUID, userId: UUID) {
        val category = categoryRepository.findById(categoryId)
            ?: throw Exception("Category not found")
        if (category.isDefault) throw Exception("Cannot delete a default category")
        categoryRepository.delete(categoryId)
    }
}
