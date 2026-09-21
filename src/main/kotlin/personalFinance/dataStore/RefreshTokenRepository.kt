package personalFinance.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import personalFinance.models.internal.RefreshToken
import java.time.Instant
import java.util.*

private const val REFRESH_TOKEN_TABLE = "refresh_tokens"
private const val TOKEN_ID_ATTRIBUTE = "tokenId"
private const val USER_ID_ATTRIBUTE = "userId"
private const val TOKEN_HASH_ATTRIBUTE = "tokenHash"
private const val EXPIRES_AT_ATTRIBUTE = "expiresAt"
private const val CREATED_AT_ATTRIBUTE = "createdAt"
private const val DEVICE_INFO_ATTRIBUTE = "deviceInfo"

@Repository
class RefreshTokenRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {

    suspend fun save(token: RefreshToken) {
        val item = mutableMapOf(
            TOKEN_ID_ATTRIBUTE to AttributeValue.S(token.tokenId.toString()),
            USER_ID_ATTRIBUTE to AttributeValue.S(token.userId.toString()),
            TOKEN_HASH_ATTRIBUTE to AttributeValue.S(token.tokenHash),
            EXPIRES_AT_ATTRIBUTE to AttributeValue.N(token.expiresAt.epochSecond.toString()),
            CREATED_AT_ATTRIBUTE to AttributeValue.N(token.createdAt.epochSecond.toString())
        )

        token.deviceInfo?.let {
            item[DEVICE_INFO_ATTRIBUTE] = AttributeValue.S(it)
        }

        val request = PutItemRequest {
            tableName = REFRESH_TOKEN_TABLE
            this.item = item
        }

        dynamoClient.putItem(request)
    }

    suspend fun findByTokenHash(tokenHash: String): RefreshToken? {
        val scanRequest = ScanRequest {
            tableName = REFRESH_TOKEN_TABLE
            filterExpression = "$TOKEN_HASH_ATTRIBUTE = :hash"
            expressionAttributeValues = mapOf(
                ":hash" to AttributeValue.S(tokenHash)
            )
        }

        val response = dynamoClient.scan(scanRequest)
        val items = response.items ?: return null

        if (items.isEmpty()) return null

        return mapToRefreshToken(items.first())
    }

    suspend fun deleteByUserId(userId: UUID) {
        val queryRequest = QueryRequest {
            tableName = REFRESH_TOKEN_TABLE
            indexName = "userId-index"
            keyConditionExpression = "$USER_ID_ATTRIBUTE = :userId"
            expressionAttributeValues = mapOf(
                ":userId" to AttributeValue.S(userId.toString())
            )
        }

        val items = dynamoClient.query(queryRequest).items

        items?.forEach { item ->
            val tokenId = item[TOKEN_ID_ATTRIBUTE]?.asS()
            tokenId?.let {
                val deleteRequest = DeleteItemRequest {
                    tableName = REFRESH_TOKEN_TABLE
                    key = mapOf(TOKEN_ID_ATTRIBUTE to AttributeValue.S(it))
                }
                dynamoClient.deleteItem(deleteRequest)
            }
        }
    }

    suspend fun deleteExpired() {
        val now = Instant.now().epochSecond
        val scanRequest = ScanRequest {
            tableName = REFRESH_TOKEN_TABLE
            filterExpression = "$EXPIRES_AT_ATTRIBUTE < :now"
            expressionAttributeValues = mapOf(
                ":now" to AttributeValue.N(now.toString())
            )
        }

        val items = dynamoClient.scan(scanRequest).items

        items?.forEach { item ->
            val tokenId = item[TOKEN_ID_ATTRIBUTE]?.asS()
            tokenId?.let {
                val deleteRequest = DeleteItemRequest {
                    tableName = REFRESH_TOKEN_TABLE
                    key = mapOf(TOKEN_ID_ATTRIBUTE to AttributeValue.S(it))
                }
                dynamoClient.deleteItem(deleteRequest)
            }
        }
    }

    suspend fun deleteByTokenHash(tokenHash: String) {
        val token = findByTokenHash(tokenHash) ?: return

        val deleteRequest = DeleteItemRequest {
            tableName = REFRESH_TOKEN_TABLE
            key = mapOf(TOKEN_ID_ATTRIBUTE to AttributeValue.S(token.tokenId.toString()))
        }

        dynamoClient.deleteItem(deleteRequest)
    }

    private fun mapToRefreshToken(item: Map<String, AttributeValue>): RefreshToken {
        return RefreshToken(
            tokenId = UUID.fromString(item[TOKEN_ID_ATTRIBUTE]?.asS() ?: throw Exception("Missing tokenId")),
            userId = UUID.fromString(item[USER_ID_ATTRIBUTE]?.asS() ?: throw Exception("Missing userId")),
            tokenHash = item[TOKEN_HASH_ATTRIBUTE]?.asS() ?: throw Exception("Missing tokenHash"),
            expiresAt = Instant.ofEpochSecond(item[EXPIRES_AT_ATTRIBUTE]?.asN()?.toLong() ?: throw Exception("Missing expiresAt")),
            createdAt = Instant.ofEpochSecond(item[CREATED_AT_ATTRIBUTE]?.asN()?.toLong() ?: throw Exception("Missing createdAt")),
            deviceInfo = item[DEVICE_INFO_ATTRIBUTE]?.asS()
        )
    }
}
