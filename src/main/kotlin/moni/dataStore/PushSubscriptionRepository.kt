package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import moni.models.internal.PushSubscription
import java.time.Instant
import java.util.UUID

private const val PUSH_SUB_TABLE = "moni_push_subscriptions"
private const val SUB_ID_ATTR    = "subscriptionId"
private const val USER_ID_ATTR   = "userId"
private const val DATA_ATTR      = "data"
private const val ENDPOINT_ATTR  = "endpoint"
private const val USER_ID_INDEX  = "userId-index"

@Component
class PushSubscriptionRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {

    suspend fun save(sub: PushSubscription) {
        dynamoClient.putItem(PutItemRequest {
            tableName = PUSH_SUB_TABLE
            item = mapOf(
                SUB_ID_ATTR   to AttributeValue.S(sub.subscriptionId.toString()),
                USER_ID_ATTR  to AttributeValue.S(sub.userId.toString()),
                ENDPOINT_ATTR to AttributeValue.S(sub.endpoint),
                DATA_ATTR     to AttributeValue.S(objectMapper.writeValueAsString(sub)),
            )
        })
    }

    suspend fun getByUserId(userId: UUID): List<PushSubscription> {
        val result = dynamoClient.query(QueryRequest {
            tableName = PUSH_SUB_TABLE
            indexName = USER_ID_INDEX
            keyConditionExpression = "$USER_ID_ATTR = :uid"
            expressionAttributeValues = mapOf(":uid" to AttributeValue.S(userId.toString()))
        })
        return result.items?.mapNotNull { item ->
            val data = item[DATA_ATTR]?.asS() ?: return@mapNotNull null
            try { objectMapper.readValue(data) } catch (_: Exception) { null }
        } ?: emptyList()
    }

    suspend fun deleteByEndpoint(userId: UUID, endpoint: String) {
        // Query by userId first, then delete matching endpoint
        val subs = getByUserId(userId)
        subs.filter { it.endpoint == endpoint }.forEach { sub ->
            dynamoClient.deleteItem(DeleteItemRequest {
                tableName = PUSH_SUB_TABLE
                key = mapOf(SUB_ID_ATTR to AttributeValue.S(sub.subscriptionId.toString()))
            })
        }
    }

    suspend fun deleteBySubscriptionId(subscriptionId: UUID) {
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = PUSH_SUB_TABLE
            key = mapOf(SUB_ID_ATTR to AttributeValue.S(subscriptionId.toString()))
        })
    }

    suspend fun deleteAllByUserId(userId: UUID) {
        getByUserId(userId).forEach { sub ->
            dynamoClient.deleteItem(DeleteItemRequest {
                tableName = PUSH_SUB_TABLE
                key = mapOf(SUB_ID_ATTR to AttributeValue.S(sub.subscriptionId.toString()))
            })
        }
    }

    /** Delete all subscriptions for [userId] except the one with [exceptId]. */
    suspend fun deleteAllByUserIdExcept(userId: UUID, exceptId: UUID) {
        getByUserId(userId)
            .filter { it.subscriptionId != exceptId }
            .forEach { sub ->
                dynamoClient.deleteItem(DeleteItemRequest {
                    tableName = PUSH_SUB_TABLE
                    key = mapOf(SUB_ID_ATTR to AttributeValue.S(sub.subscriptionId.toString()))
                })
            }
    }
}
