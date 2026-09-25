package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import org.springframework.stereotype.Repository
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Entry
import moni.models.internal.Necessity
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.*

private const val ENTRY_TABLE = "moni_entries"
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
) {

    suspend fun save(entry: Entry) {
        val request = PutItemRequest {
            tableName = ENTRY_TABLE
            this.item = buildItem(entry)
        }
        dynamoClient.putItem(request)
    }

    suspend fun findById(entryId: UUID): Entry? {
        val request = GetItemRequest {
            tableName = ENTRY_TABLE
            key = mapOf(ENTRY_ID_ATTRIBUTE to AttributeValue.S(entryId.toString()))
        }

        val item = dynamoClient.getItem(request).item?.takeIf { it.isNotEmpty() } ?: return null
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

    /**
     * Returns distinct year integers that have at least one entry for [userId].
     * Uses a ProjectionExpression to fetch only the date attribute — minimal data transfer.
     */
    suspend fun findYearsByUserId(userId: UUID): List<Int> =
        queryDistinctDatePrefixes(
            indexName = "userId-date-index",
            keyAttr   = USER_ID_ATTRIBUTE,
            keyValue  = userId.toString(),
            take      = 4,
        ).mapNotNull { it.toIntOrNull() }.distinct().sorted()

    /**
     * Returns distinct year integers that have at least one entry for [householdId].
     * Uses a ProjectionExpression to fetch only the date attribute — minimal data transfer.
     */
    suspend fun findYearsByHouseholdId(householdId: UUID): List<Int> =
        queryDistinctDatePrefixes(
            indexName = "householdId-date-index",
            keyAttr   = HOUSEHOLD_ID_ATTRIBUTE,
            keyValue  = householdId.toString(),
            take      = 4,
        ).mapNotNull { it.toIntOrNull() }.distinct().sorted()

    /**
     * Returns distinct YYYY-MM month keys that have at least one entry for [userId].
     * Uses a ProjectionExpression to fetch only the date attribute — minimal data transfer.
     */
    suspend fun findMonthsByUserId(userId: UUID): List<String> =
        queryDistinctDatePrefixes(
            indexName = "userId-date-index",
            keyAttr   = USER_ID_ATTRIBUTE,
            keyValue  = userId.toString(),
            take      = 7,
        ).distinct().sorted()

    /**
     * Returns distinct YYYY-MM month keys that have at least one entry for [householdId].
     * Uses a ProjectionExpression to fetch only the date attribute — minimal data transfer.
     */
    suspend fun findMonthsByHouseholdId(householdId: UUID): List<String> =
        queryDistinctDatePrefixes(
            indexName = "householdId-date-index",
            keyAttr   = HOUSEHOLD_ID_ATTRIBUTE,
            keyValue  = householdId.toString(),
            take      = 7,
        ).distinct().sorted()

    /**
     * Shared helper: queries [indexName] for all items matching [keyAttr] = [keyValue],
     * fetches only the DATE_ATTRIBUTE, and returns the leading [take] characters of each
     * date string (e.g. take=4 → year "2024", take=7 → month "2024-03").
     */
    private suspend fun queryDistinctDatePrefixes(
        indexName: String,
        keyAttr: String,
        keyValue: String,
        take: Int,
    ): List<String> {
        val queryRequest = QueryRequest {
            tableName = ENTRY_TABLE
            this.indexName = indexName
            keyConditionExpression = "$keyAttr = :keyValue"
            expressionAttributeValues = mapOf(":keyValue" to AttributeValue.S(keyValue))
            projectionExpression = "#dateAttr"
            expressionAttributeNames = mapOf("#dateAttr" to DATE_ATTRIBUTE)
        }
        return dynamoClient.query(queryRequest).items
            ?.mapNotNull { it[DATE_ATTRIBUTE]?.asS()?.take(take) }
            ?: emptyList()
    }

    suspend fun delete(entryId: UUID) {
        val deleteRequest = DeleteItemRequest {
            tableName = ENTRY_TABLE
            key = mapOf(ENTRY_ID_ATTRIBUTE to AttributeValue.S(entryId.toString()))
        }

        dynamoClient.deleteItem(deleteRequest)
    }

    private fun buildItem(entry: Entry): MutableMap<String, AttributeValue> {
        val item = mutableMapOf(
            ENTRY_ID_ATTRIBUTE        to AttributeValue.S(entry.entryId.toString()),
            USER_ID_ATTRIBUTE         to AttributeValue.S(entry.userId.toString()),
            AMOUNT_VALUE_ATTRIBUTE    to AttributeValue.N(entry.amount.value.toString()),
            AMOUNT_CURRENCY_ATTRIBUTE to AttributeValue.S(entry.amount.currency),
            CATEGORY_ID_ATTRIBUTE     to AttributeValue.S(entry.categoryId.toString()),
            DATE_ATTRIBUTE            to AttributeValue.S(entry.date.toString()),
            NAME_ATTRIBUTE            to AttributeValue.S(entry.name),
            NOTE_ATTRIBUTE            to AttributeValue.S(entry.note),
            TYPE_ATTRIBUTE            to AttributeValue.S(entry.type.name),
            NECESSITY_ATTRIBUTE       to AttributeValue.S(entry.necessity.name),
            AUTHOR_NAME_ATTRIBUTE     to AttributeValue.S(entry.authorName),
            CREATED_AT_ATTRIBUTE      to AttributeValue.N(entry.createdAt.epochSecond.toString()),
        )
        entry.householdId?.let { item[HOUSEHOLD_ID_ATTRIBUTE] = AttributeValue.S(it.toString()) }
        return item
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
            necessity = Necessity.fromString(item[NECESSITY_ATTRIBUTE]?.asS() ?: throw Exception("Missing necessity")),
            authorName = item[AUTHOR_NAME_ATTRIBUTE]?.asS() ?: throw Exception("Missing authorName"),
            createdAt = Instant.ofEpochSecond(item[CREATED_AT_ATTRIBUTE]?.asN()?.toLong() ?: throw Exception("Missing createdAt"))
        )
    }
}
