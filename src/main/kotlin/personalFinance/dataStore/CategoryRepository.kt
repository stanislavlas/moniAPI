package personalFinance.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import personalFinance.models.TransactionType
import personalFinance.models.internal.Category
import java.time.Instant
import java.util.*

private const val CATEGORY_TABLE         = "personalFinance_categories"
private const val CATEGORY_ID_ATTRIBUTE  = "categoryId"
private const val OWNER_KEY_ATTRIBUTE    = "ownerKey"
private const val HOUSEHOLD_ID_ATTRIBUTE = "householdId"
private const val NAME_ATTRIBUTE         = "name"
private const val EMOJI_ATTRIBUTE        = "emoji"
private const val COLOR_ATTRIBUTE        = "color"
private const val TYPE_ATTRIBUTE         = "type"
private const val IS_DEFAULT_ATTRIBUTE   = "isDefault"
private const val CREATED_AT_ATTRIBUTE   = "createdAt"

@Repository
class CategoryRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Persist a personal or household category.
     * [userId] must be supplied when [category.householdId] is null (personal category).
     */
    suspend fun save(category: Category, userId: UUID? = null) {
        val ownerKey = if (category.householdId != null)
            "household:${category.householdId}"
        else {
            requireNotNull(userId) { "userId required when saving a personal category" }
            "user:$userId"
        }
        putItem(category, ownerKey)
    }

    /** Write a personal copy of [categoryId] for [userId] with a fresh categoryId. */
    suspend fun copyToUser(categoryId: UUID, userId: UUID) {
        val source = findById(categoryId) ?: return
        save(source.copy(categoryId = UUID.randomUUID(), householdId = null), userId)
    }

    suspend fun findGlobal(): List<Category> = queryByOwnerKey("global")

    suspend fun findByUserId(userId: UUID): List<Category> = queryByOwnerKey("user:$userId")

    suspend fun findByHouseholdId(householdId: UUID): List<Category> = queryByOwnerKey("household:$householdId")

    suspend fun findById(categoryId: UUID): Category? {
        val result = dynamoClient.getItem(GetItemRequest {
            tableName = CATEGORY_TABLE
            key = mapOf(CATEGORY_ID_ATTRIBUTE to AttributeValue.S(categoryId.toString()))
        })
        return result.item?.takeIf { it.isNotEmpty() }?.let { mapToCategory(it) }
    }

    suspend fun delete(categoryId: UUID) {
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = CATEGORY_TABLE
            key = mapOf(CATEGORY_ID_ATTRIBUTE to AttributeValue.S(categoryId.toString()))
        })
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun queryByOwnerKey(ownerKey: String): List<Category> {
        val result = dynamoClient.query(QueryRequest {
            tableName = CATEGORY_TABLE
            indexName = "ownerKey-index"
            keyConditionExpression = "$OWNER_KEY_ATTRIBUTE = :key"
            expressionAttributeValues = mapOf(":key" to AttributeValue.S(ownerKey))
        })
        return result.items?.map { mapToCategory(it) } ?: emptyList()
    }

    private suspend fun putItem(category: Category, ownerKey: String) {
        val item = mutableMapOf(
            CATEGORY_ID_ATTRIBUTE to AttributeValue.S(category.categoryId.toString()),
            OWNER_KEY_ATTRIBUTE   to AttributeValue.S(ownerKey),
            NAME_ATTRIBUTE        to AttributeValue.S(category.name),
            EMOJI_ATTRIBUTE       to AttributeValue.S(category.emoji),
            COLOR_ATTRIBUTE       to AttributeValue.S(category.color),
            TYPE_ATTRIBUTE        to AttributeValue.S(category.type.name),
            IS_DEFAULT_ATTRIBUTE  to AttributeValue.Bool(category.isDefault),
            CREATED_AT_ATTRIBUTE  to AttributeValue.N(category.createdAt.epochSecond.toString()),
        )
        category.householdId?.let { item[HOUSEHOLD_ID_ATTRIBUTE] = AttributeValue.S(it.toString()) }
        dynamoClient.putItem(PutItemRequest { tableName = CATEGORY_TABLE; this.item = item })
    }

    private fun mapToCategory(item: Map<String, AttributeValue>): Category {
        return Category(
            categoryId  = UUID.fromString(item[CATEGORY_ID_ATTRIBUTE]?.asS() ?: error("Missing categoryId")),
            householdId = item[HOUSEHOLD_ID_ATTRIBUTE]?.asS()?.let { UUID.fromString(it) },
            name        = item[NAME_ATTRIBUTE]?.asS()  ?: error("Missing name"),
            emoji       = item[EMOJI_ATTRIBUTE]?.asS() ?: error("Missing emoji"),
            color       = item[COLOR_ATTRIBUTE]?.asS() ?: error("Missing color"),
            type        = TransactionType.valueOf(item[TYPE_ATTRIBUTE]?.asS() ?: error("Missing type")),
            isDefault   = item[IS_DEFAULT_ATTRIBUTE]?.asBool() ?: false,
            createdAt   = Instant.ofEpochSecond(item[CREATED_AT_ATTRIBUTE]?.asN()?.toLong() ?: error("Missing createdAt")),
        )
    }
}
