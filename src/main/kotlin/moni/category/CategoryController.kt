package moni.category

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import moni.auth.JwtAuth
import moni.common.getUser
import moni.common.getUserId
import moni.common.successResponse
import moni.dataStore.IDataStoreClient
import moni.models.TransactionType
import moni.models.api.CategoryResponse
import moni.models.api.toApi
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
    ): List<CategoryResponse> {
        return runBlocking {
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            categoryService.getCategories(user.userId, user.householdId).map { it.toApi() }
        }
    }

    @PostMapping
    fun createCategory(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: CreateCategoryRequest
    ): CategoryResponse {
        return runBlocking {
            // Auto-scope to household if the user is currently in one
            val user = authorization.getUser(jwtAuth, dataStoreClient)
            categoryService.createCategory(
                userId      = user.userId,
                householdId = user.householdId,   // null → personal, non-null → household
                name        = request.name,
                emoji       = request.resolvedEmoji,
                color       = request.resolvedColor,
                type        = request.type,
            ).toApi()
        }
    }

    @PutMapping("/{id}")
    fun updateCategory(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateCategoryRequest
    ): CategoryResponse {
        val userId = authorization.getUserId(jwtAuth)
        val categoryUUID = try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid category ID format")
        }
        return runBlocking {
            categoryService.updateCategory(
                categoryId = categoryUUID,
                userId     = userId,
                name       = request.name,
                emoji      = request.emoji,
                color      = request.color,
            ).toApi()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteCategory(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable id: String
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        val categoryUUID = try { UUID.fromString(id) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid category ID format")
        }
        runBlocking {
            categoryService.deleteCategory(
                categoryId = categoryUUID,
                userId     = userId,
            )
        }
        return successResponse()
    }
}

data class CreateCategoryRequest(
    @field:NotBlank val name: String,
    val type: TransactionType,
    val emoji: String? = null,
    val icon: String? = null,   // web clients send "icon" — treated as an alias for emoji
    val color: String? = null,
) {
    val resolvedEmoji: String get() = (emoji ?: icon ?: "").trim()
    val resolvedColor: String get() = (color ?: "").trim()
}

data class UpdateCategoryRequest(
    val name: String?,
    val emoji: String?,
    val color: String?,
)
