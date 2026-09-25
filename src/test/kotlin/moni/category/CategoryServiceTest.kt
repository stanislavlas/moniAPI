package moni.category

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import moni.dataStore.CategoryRepository
import moni.dataStore.HouseholdRepository
import moni.models.TransactionType
import moni.models.internal.Category
import java.util.*

class CategoryServiceTest {

    private val repo              = mockk<CategoryRepository>()
    private val householdRepo     = mockk<HouseholdRepository>()
    private val service           = CategoryService(repo, householdRepo)

    private fun cat(householdId: UUID? = null) = Category(
        categoryId  = UUID.randomUUID(),
        householdId = householdId,
        name        = "Rent",
        emoji       = "🏠",
        color       = "#D85A30",
        type        = TransactionType.EXPENSE,
        isDefault   = false,
    )

    private fun globalCat() = Category(
        categoryId  = UUID.randomUUID(),
        householdId = null,
        name        = "Groceries",
        emoji       = "🛒",
        color       = "#D4537E",
        type        = TransactionType.EXPENSE,
        isDefault   = true,
    )

    @Test
    fun `getCategories returns globals plus personal categories merged`() = runBlocking {
        val userId   = UUID.randomUUID()
        val global   = globalCat()
        val personal = cat()

        coEvery { repo.findGlobal() } returns listOf(global)
        coEvery { repo.findByUserId(userId) } returns listOf(personal)

        val result = service.getCategories(userId, null)

        assertEquals(2, result.size)
        assertTrue(result.any { it.categoryId == global.categoryId })
        assertTrue(result.any { it.categoryId == personal.categoryId })
    }

    @Test
    fun `getCategories returns globals plus household categories when householdId given`() = runBlocking {
        val userId      = UUID.randomUUID()
        val householdId = UUID.randomUUID()
        val global      = globalCat()
        val hhCat       = cat(householdId)

        coEvery { repo.findGlobal() } returns listOf(global)
        coEvery { repo.findByHouseholdId(householdId) } returns listOf(hhCat)

        val result = service.getCategories(userId, householdId)

        assertEquals(2, result.size)
        assertTrue(result.any { it.categoryId == global.categoryId })
        assertTrue(result.any { it.categoryId == hhCat.categoryId })
    }

    @Test
    fun `assignToHousehold rewrites personal categories with householdId set`() = runBlocking {
        val userId      = UUID.randomUUID()
        val householdId = UUID.randomUUID()
        val original    = cat()

        coEvery { repo.findByUserId(userId) } returns listOf(original)
        val saved = slot<Category>()
        coEvery { repo.save(capture(saved), null) } returns Unit

        service.assignToHousehold(userId, householdId)

        coVerify(exactly = 1) { repo.save(any(), null) }
        assertEquals(householdId, saved.captured.householdId)
        assertEquals(original.categoryId, saved.captured.categoryId)
    }

    @Test
    fun `assignToHousehold does nothing when user has no personal categories`() = runBlocking {
        val userId      = UUID.randomUUID()
        val householdId = UUID.randomUUID()

        coEvery { repo.findByUserId(userId) } returns emptyList()

        service.assignToHousehold(userId, householdId)

        coVerify(exactly = 0) { repo.save(any(), any()) }
    }

    @Test
    fun `restoreToUser calls copyToUser for every household category`() = runBlocking {
        val userId      = UUID.randomUUID()
        val householdId = UUID.randomUUID()
        val c1          = cat(householdId)
        val c2          = cat(householdId)

        coEvery { repo.findByHouseholdId(householdId) } returns listOf(c1, c2)
        coEvery { repo.copyToUser(any(), userId) } returns Unit

        service.restoreToUser(userId, householdId)

        coVerify(exactly = 1) { repo.copyToUser(c1.categoryId, userId) }
        coVerify(exactly = 1) { repo.copyToUser(c2.categoryId, userId) }
    }

    @Test
    fun `restoreToUser does nothing when household has no custom categories`() = runBlocking {
        val userId      = UUID.randomUUID()
        val householdId = UUID.randomUUID()

        coEvery { repo.findByHouseholdId(householdId) } returns emptyList()

        service.restoreToUser(userId, householdId)

        coVerify(exactly = 0) { repo.copyToUser(any(), any()) }
    }
}
