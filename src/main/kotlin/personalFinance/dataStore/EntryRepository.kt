package personalFinance.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import personalFinance.models.Amount
import personalFinance.models.TransactionType
import personalFinance.models.internal.Entry
import personalFinance.models.internal.Necessity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

private const val ENTRY_TABLE = "entries"
private const val ENTRY_ID_ATTRIBUTE = "entryId"
private const val USER_ID_ATTRIBUTE = "userId"
private const val HOUSEHOLD_ID_ATTRIBUTE = "householdId"
private const val AMOUNT_VALUE_ATTRIBUTE = "amountValue"
private const val AMOUNT_CURRENCY_ATTRIBUTE = "amountCurrency"
private const val CATEGORY_ID_ATTRIBUTE = "categoryId"
private const val DATE_ATTRIBUTE = "date"
private const val NAME_ATTRIBUTE = "name"
private const val NOTE_ATTRIBUTE = "note"
private const val TYPE_ATTRIBUTE = "type"
private const val NECESSITY_ATTRIBUTE = "necessity"
private const val AUTHOR_NAME_ATTRIBUTE = "authorName"
private const val CREATED_AT_ATTRIBUTE = "createdAt"

@Repository
class EntryRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {

    suspend fun save(entry: Entry) {
        val item = mutableMapOf(
            ENTRY_ID_ATTRIBUTE to AttributeValue.S(entry.entryId.toString()),
            USER_ID_ATTRIBUTE to AttributeValue.S(entry.userId.toString()),
            AMOUNT_VALUE_ATTRIBUTE to AttributeValue.N(entry.amount.value.toString()),
            AMOUNT_CURRENCY_ATTRIBUTE to AttributeValue.S(entry.amount.currency),
            CATEGORY_ID_ATTRIBUTE to AttributeValue.S(entry.categoryId.toString()),
            DATE_ATTRIBUTE to AttributeValue.S(entry.date.toString()),
            NAME_ATTRIBUTE to AttributeValue.S(entry.name),
            NOTE_ATTRIBUTE to AttributeValue.S(entry.note),
            TYPE_ATTRIBUTE to AttributeValue.S(entry.type.name),
            NECESSITY_ATTRIBUTE to AttributeValue.S(entry.necessity.name),
            AUTHOR_NAME_ATTRIBUTE to AttributeValue.S(entry.authorName),
            CREATED_AT_ATTRIBUTE to AttributeValue.N(entry.createdAt.epochSecond.toString())
        )

        entry.householdId?.let {
            item[HOUSEHOLD_ID_ATTRIBUTE] = AttributeValue.S(it.toString())
        }

        val request = PutItemRequest {
            tableName = ENTRY_TABLE
            this.item = item
        }

        dynamoClient.putItem(request)
    }

    suspend fun findById(entryId: UUID): Entry? {
        val request = GetItemRequest {
            tableName = ENTRY_TABLE
            key = mapOf(ENTRY_ID_ATTRIBUTE to AttributeValue.S(entryId.toString()))
        }

        val item = dynamoClient.getItem(request).item ?: return null
        return mapToEntry(item)
    }

    suspend fun findByUserId(userId: UUID, fromDate: LocalDate?, toDate: LocalDate?): List<Entry> {
        val queryRequest = QueryRequest {
            tableName = ENTRY_TABLE
            indexName = "userId-date-index"

            if (fromDate != null && toDate != null) {
                keyConditionExpression = "$USER_ID_ATTRIBUTE = :userId AND #dateAttr BETWEEN :fromDate AND :toDate"
                expressionAttributeNames = mapOf("#dateAttr" to DATE_ATTRIBUTE)
                expressionAttributeValues = mapOf(
                    ":userId" to AttributeValue.S(userId.toString()),
                    ":fromDate" to AttributeValue.S(fromDate.toString()),
                    ":toDate" to AttributeValue.S(toDate.toString())
                )
            } else {
                keyConditionExpression = "$USER_ID_ATTRIBUTE = :userId"
                expressionAttributeValues = mapOf(
                    ":userId" to AttributeValue.S(userId.toString())
                )
            }
        }

        val items = dynamoClient.query(queryRequest).items ?: return emptyList()
        return items.map { mapToEntry(it) }
    }

    suspend fun findByHouseholdId(householdId: UUID, fromDate: LocalDate?, toDate: LocalDate?): List<Entry> {
        val queryRequest = QueryRequest {
            tableName = ENTRY_TABLE
            indexName = "householdId-date-index"

            if (fromDate != null && toDate != null) {
                keyConditionExpression = "$HOUSEHOLD_ID_ATTRIBUTE = :householdId AND #dateAttr BETWEEN :fromDate AND :toDate"
                expressionAttributeNames = mapOf("#dateAttr" to DATE_ATTRIBUTE)
                expressionAttributeValues = mapOf(
                    ":householdId" to AttributeValue.S(householdId.toString()),
                    ":fromDate" to AttributeValue.S(fromDate.toString()),
                    ":toDate" to AttributeValue.S(toDate.toString())
                )
            } else {
                keyConditionExpression = "$HOUSEHOLD_ID_ATTRIBUTE = :householdId"
                expressionAttributeValues = mapOf(
                    ":householdId" to AttributeValue.S(householdId.toString())
                )
            }
        }

        val items = dynamoClient.query(queryRequest).items ?: return emptyList()
        return items.map { mapToEntry(it) }
    }

    suspend fun batchSave(entries: List<Entry>) {
        if (entries.isEmpty()) return
        // DynamoDB BatchWriteItem limit is 25 items per request
        entries.chunked(25).forEach { chunk ->
            val writeRequests = chunk.map { entry ->
                val item = mutableMapOf(
                    ENTRY_ID_ATTRIBUTE      to AttributeValue.S(entry.entryId.toString()),
                    USER_ID_ATTRIBUTE       to AttributeValue.S(entry.userId.toString()),
                    AMOUNT_VALUE_ATTRIBUTE  to AttributeValue.N(entry.amount.value.toString()),
                    AMOUNT_CURRENCY_ATTRIBUTE to AttributeValue.S(entry.amount.currency),
                    CATEGORY_ID_ATTRIBUTE   to AttributeValue.S(entry.categoryId.toString()),
                    DATE_ATTRIBUTE          to AttributeValue.S(entry.date.toString()),
                    NAME_ATTRIBUTE          to AttributeValue.S(entry.name),
                    NOTE_ATTRIBUTE          to AttributeValue.S(entry.note),
                    TYPE_ATTRIBUTE          to AttributeValue.S(entry.type.name),
                    NECESSITY_ATTRIBUTE     to AttributeValue.S(entry.necessity.name),
                    AUTHOR_NAME_ATTRIBUTE   to AttributeValue.S(entry.authorName),
                    CREATED_AT_ATTRIBUTE    to AttributeValue.N(entry.createdAt.epochSecond.toString())
                )
                entry.householdId?.let { item[HOUSEHOLD_ID_ATTRIBUTE] = AttributeValue.S(it.toString()) }
                WriteRequest {
                    putRequest = PutRequest { this.item = item }
                }
            }
            val request = BatchWriteItemRequest {
                requestItems = mapOf(ENTRY_TABLE to writeRequests)
            }
            dynamoClient.batchWriteItem(request)
        }
    }

    suspend fun delete(entryId: UUID) {
        val deleteRequest = DeleteItemRequest {
            tableName = ENTRY_TABLE
            key = mapOf(ENTRY_ID_ATTRIBUTE to AttributeValue.S(entryId.toString()))
        }

        dynamoClient.deleteItem(deleteRequest)
    }

    private fun mapToEntry(item: Map<String, AttributeValue>): Entry {
        val householdIdStr = item[HOUSEHOLD_ID_ATTRIBUTE]?.asS()

        return Entry(
            entryId = UUID.fromString(item[ENTRY_ID_ATTRIBUTE]?.asS() ?: throw Exception("Missing entryId")),
            userId = UUID.fromString(item[USER_ID_ATTRIBUTE]?.asS() ?: throw Exception("Missing userId")),
            householdId = householdIdStr?.let { UUID.fromString(it) },
            amount = Amount(
                value = BigDecimal(item[AMOUNT_VALUE_ATTRIBUTE]?.asN() ?: throw Exception("Missing amount value")),
                currency = item[AMOUNT_CURRENCY_ATTRIBUTE]?.asS() ?: throw Exception("Missing currency")
            ),
            categoryId = UUID.fromString(item[CATEGORY_ID_ATTRIBUTE]?.asS() ?: throw Exception("Missing categoryId")),
            date = LocalDate.parse(item[DATE_ATTRIBUTE]?.asS() ?: throw Exception("Missing date")),
            name = item[NAME_ATTRIBUTE]?.asS() ?: throw Exception("Missing name"),
            note = item[NOTE_ATTRIBUTE]?.asS() ?: "",
            type = TransactionType.valueOf(item[TYPE_ATTRIBUTE]?.asS() ?: throw Exception("Missing type")),
            necessity = Necessity.valueOf(item[NECESSITY_ATTRIBUTE]?.asS() ?: throw Exception("Missing necessity")),
            authorName = item[AUTHOR_NAME_ATTRIBUTE]?.asS() ?: throw Exception("Missing authorName"),
            createdAt = Instant.ofEpochSecond(item[CREATED_AT_ATTRIBUTE]?.asN()?.toLong() ?: throw Exception("Missing createdAt"))
        )
    }
}
