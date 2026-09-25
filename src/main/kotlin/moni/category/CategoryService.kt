package moni.category

import org.springframework.stereotype.Service
import moni.config.ForbiddenException
import moni.dataStore.CategoryRepository
import moni.models.TransactionType
import moni.models.internal.Category
import java.util.*

@Service
class CategoryService(
    private val categoryRepository: CategoryRepository,
    private val householdRepository: moni.dataStore.HouseholdRepository,
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
            ?: throw NoSuchElementException("Category not found")
        if (existing.isDefault) throw IllegalArgumentException("Cannot modify a default category")

        assertCategoryOwnership(existing, userId)

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
            ?: throw NoSuchElementException("Category not found")
        if (category.isDefault) throw IllegalArgumentException("Cannot delete a default category")

        assertCategoryOwnership(category, userId)

        categoryRepository.delete(categoryId)
    }

    /**
     * Verify that [userId] is allowed to modify [category].
     * - Personal category: ownerKey must match "user:$userId".
     * - Household category: user must be a member of the category's household.
     * Throws [ForbiddenException] on violation.
     */
    private suspend fun assertCategoryOwnership(category: Category, userId: UUID) {
        val ownerKey = category.ownerKey ?: return // global categories have no ownerKey — skip
        if (!ownerKey.startsWith("household:")) {
            if (ownerKey != "user:$userId") throw ForbiddenException("Not authorized to modify this category")
        } else if (category.householdId != null) {
            val household = householdRepository.findById(category.householdId)
                ?: throw NoSuchElementException("Household not found")
            if (household.members.none { it.userId == userId })
                throw ForbiddenException("Not authorized to modify this category")
        }
    }
}
