package moni.category

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.common.successResponse
import moni.dataStore.IDataStoreClient
import moni.models.TransactionType
import moni.models.internal.Category
import java.util.*

@RestController
@RequestMapping("/api/categories")
class CategoryController(
    private val categoryService: CategoryService,
    private val jwtAuth: JwtAuth,
    private val dataStoreClient: IDataStoreClient,
) {
    @GetMapping
    fun getCategories(
        @RequestHeader("Authorization") authorization: String,
    ): List<Category> {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            val user = dataStoreClient.getUserById(userId)
            categoryService.getCategories(userId, user.householdId)
        }
    }

    @PostMapping
    fun createCategory(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: CreateCategoryRequest
    ): Category {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            // Auto-scope to household if the user is currently in one
            val user = dataStoreClient.getUserById(userId)
            categoryService.createCategory(
                userId      = userId,
                householdId = user.householdId,   // null → personal, non-null → household
                name        = request.name,
                emoji       = request.emoji,
                color       = request.color,
                type        = request.type,
            )
        }
    }

    @PutMapping("/{id}")
    fun updateCategory(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String,
        @RequestBody request: UpdateCategoryRequest
    ): Category {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            categoryService.updateCategory(
                categoryId = UUID.fromString(id),
                userId     = userId,
                name       = request.name,
                emoji      = request.emoji,
                color      = request.color,
            )
        }
    }

    @DeleteMapping("/{id}")
    fun deleteCategory(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        runBlocking {
            categoryService.deleteCategory(
                categoryId = UUID.fromString(id),
                userId     = userId,
            )
        }
        return successResponse()
    }
}

data class CreateCategoryRequest(
    val name: String,
    val emoji: String,
    val color: String,
    val type: TransactionType,
)

data class UpdateCategoryRequest(
    val name: String?,
    val emoji: String?,
    val color: String?,
)
