package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Repository
import moni.models.Amount
import moni.models.TransactionType
import moni.models.internal.Necessity
import moni.models.internal.RecurrenceFrequency
import moni.models.internal.RecurringEntry
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val TABLE             = "moni_recurring_entries"
private const val RECURRING_ID      = "recurringId"
private const val USER_ID           = "userId"
private const val AMOUNT_VALUE      = "amountValue"
private const val AMOUNT_CURRENCY   = "amountCurrency"
private const val CATEGORY_ID       = "categoryId"
private const val NAME              = "name"
private const val NOTE              = "note"
private const val TYPE              = "type"
private const val NECESSITY         = "necessity"
private const val FREQUENCY         = "frequency"
private const val DAY_OF_WEEK       = "dayOfWeek"
private const val DAY_OF_MONTH      = "dayOfMonth"
private const val MONTH_OF_YEAR     = "monthOfYear"
private const val START_DATE        = "startDate"
private const val END_DATE          = "endDate"
private const val NEXT_POST_DATE    = "nextPostDate"
private const val ACTIVE            = "active"
private const val CREATED_AT        = "createdAt"

@Repository
class RecurringEntryRepository(private val dynamoClient: DynamoDbClient) {

    suspend fun save(entry: RecurringEntry) {
        dynamoClient.putItem(PutItemRequest {
            tableName = TABLE
            item = buildItem(entry)
        })
    }

    suspend fun findById(recurringId: UUID): RecurringEntry? {
        val response = dynamoClient.getItem(GetItemRequest {
            tableName = TABLE
            key = mapOf(RECURRING_ID to AttributeValue.S(recurringId.toString()))
        })
        return response.item?.takeIf { it.isNotEmpty() }?.let { mapToEntry(it) }
    }

    /** Returns all templates belonging to [userId]. */
    suspend fun findByUserId(userId: UUID): List<RecurringEntry> {
        val request = QueryRequest {
            tableName = TABLE
            indexName = "userId-index"
            keyConditionExpression = "$USER_ID = :uid"
            expressionAttributeValues = mapOf(":uid" to AttributeValue.S(userId.toString()))
        }
        return dynamoClient.query(request).items?.map { mapToEntry(it) } ?: emptyList()
    }

    /**
     * Returns ALL active recurring entries whose nextPostDate is on or before [date].
     * Used by the scheduler to find what needs posting today.
     * Performs a full-table scan filtered server-side — acceptable for personal-finance scale.
     */
    suspend fun findDueBy(date: LocalDate): List<RecurringEntry> {
        val request = ScanRequest {
            tableName = TABLE
            filterExpression = "#active = :true AND #npd <= :date"
            expressionAttributeNames = mapOf(
                "#active" to ACTIVE,
                "#npd"    to NEXT_POST_DATE,
            )
            expressionAttributeValues = mapOf(
                ":true" to AttributeValue.Bool(true),
                ":date" to AttributeValue.S(date.toString()),
            )
        }
        return dynamoClient.scan(request).items?.map { mapToEntry(it) } ?: emptyList()
    }

    suspend fun delete(recurringId: UUID) {
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = TABLE
            key = mapOf(RECURRING_ID to AttributeValue.S(recurringId.toString()))
        })
    }

    /** Delete all recurring templates belonging to [userId]. Called on account deletion. */
    suspend fun deleteByUserId(userId: UUID) {
        val entries = findByUserId(userId)
        coroutineScope { entries.map { async { delete(it.recurringId) } }.awaitAll() }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun buildItem(e: RecurringEntry): Map<String, AttributeValue> {
        val item = mutableMapOf(
            RECURRING_ID    to AttributeValue.S(e.recurringId.toString()),
            USER_ID         to AttributeValue.S(e.userId.toString()),
            AMOUNT_VALUE    to AttributeValue.N(e.amount.value.toString()),
            AMOUNT_CURRENCY to AttributeValue.S(e.amount.currency),
            CATEGORY_ID     to AttributeValue.S(e.categoryId.toString()),
            NAME            to AttributeValue.S(e.name),
            NOTE            to AttributeValue.S(e.note),
            TYPE            to AttributeValue.S(e.type.name),
            NECESSITY       to AttributeValue.S(e.necessity.name),
            FREQUENCY       to AttributeValue.S(e.frequency.name),
            START_DATE      to AttributeValue.S(e.startDate.toString()),
            NEXT_POST_DATE  to AttributeValue.S(e.nextPostDate.toString()),
            ACTIVE          to AttributeValue.Bool(e.active),
            CREATED_AT      to AttributeValue.N(e.createdAt.epochSecond.toString()),
        )
        e.endDate?.let    { item[END_DATE]     = AttributeValue.S(it.toString()) }
        e.dayOfWeek?.let  { item[DAY_OF_WEEK]  = AttributeValue.N(it.toString()) }
        e.dayOfMonth?.let { item[DAY_OF_MONTH] = AttributeValue.N(it.toString()) }
        e.monthOfYear?.let{ item[MONTH_OF_YEAR]= AttributeValue.N(it.toString()) }
        return item
    }

    private fun mapToEntry(item: Map<String, AttributeValue>): RecurringEntry = RecurringEntry(
        recurringId  = UUID.fromString(item[RECURRING_ID]?.asS()  ?: throw IllegalStateException("Missing recurringId")),
        userId       = UUID.fromString(item[USER_ID]?.asS()        ?: throw IllegalStateException("Missing userId")),
        amount       = Amount(
            value    = BigDecimal(item[AMOUNT_VALUE]?.asN()        ?: throw IllegalStateException("Missing amountValue")),
            currency = item[AMOUNT_CURRENCY]?.asS()                ?: throw IllegalStateException("Missing amountCurrency"),
        ),
        categoryId   = UUID.fromString(item[CATEGORY_ID]?.asS()   ?: throw IllegalStateException("Missing categoryId")),
        name         = item[NAME]?.asS()                           ?: throw IllegalStateException("Missing name"),
        note         = item[NOTE]?.asS()                           ?: "",
        type         = TransactionType.fromStringOrUnsupported(item[TYPE]?.asS() ?: throw IllegalStateException("Missing type")),
        necessity    = Necessity.fromString(item[NECESSITY]?.asS() ?: throw IllegalStateException("Missing necessity")),
        frequency    = RecurrenceFrequency.valueOf(item[FREQUENCY]?.asS() ?: throw IllegalStateException("Missing frequency")),
        dayOfWeek    = item[DAY_OF_WEEK]?.asN()?.toIntOrNull(),
        dayOfMonth   = item[DAY_OF_MONTH]?.asN()?.toIntOrNull(),
        monthOfYear  = item[MONTH_OF_YEAR]?.asN()?.toIntOrNull(),
        startDate    = LocalDate.parse(item[START_DATE]?.asS()     ?: throw IllegalStateException("Missing startDate")),
        endDate      = item[END_DATE]?.asS()?.let { LocalDate.parse(it) },
        nextPostDate = LocalDate.parse(item[NEXT_POST_DATE]?.asS() ?: throw IllegalStateException("Missing nextPostDate")),
        active       = item[ACTIVE]?.asBool()                      ?: throw IllegalStateException("Missing active"),
        createdAt    = Instant.ofEpochSecond(item[CREATED_AT]?.asN()?.toLongOrNull() ?: throw IllegalStateException("Missing createdAt")),
    )
}
