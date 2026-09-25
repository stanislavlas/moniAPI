package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository
import moni.models.internal.RefreshToken
import java.time.Instant
import java.util.*

private const val REFRESH_TOKEN_TABLE    = "moni_refresh_tokens"
private const val TOKEN_ID_ATTRIBUTE     = "tokenId"
private const val USER_ID_ATTRIBUTE      = "userId"
private const val TOKEN_HASH_ATTRIBUTE   = "tokenHash"
private const val TOKEN_PREFIX_ATTRIBUTE = "tokenPrefix"   // first 8 chars of plain token (unhashed) — GSI lookup key
private const val EXPIRES_AT_ATTRIBUTE   = "expiresAt"
private const val CREATED_AT_ATTRIBUTE   = "createdAt"
private const val DEVICE_INFO_ATTRIBUTE  = "deviceInfo"

@Repository
class RefreshTokenRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {

    suspend fun save(token: RefreshToken) {
        val item = mutableMapOf(
            TOKEN_ID_ATTRIBUTE     to AttributeValue.S(token.tokenId.toString()),
            USER_ID_ATTRIBUTE      to AttributeValue.S(token.userId.toString()),
            TOKEN_HASH_ATTRIBUTE   to AttributeValue.S(token.tokenHash),
            TOKEN_PREFIX_ATTRIBUTE to AttributeValue.S(token.tokenPrefix),
            EXPIRES_AT_ATTRIBUTE   to AttributeValue.N(token.expiresAt.epochSecond.toString()),
            CREATED_AT_ATTRIBUTE   to AttributeValue.N(token.createdAt.epochSecond.toString())
        )

        token.deviceInfo?.let {
            item[DEVICE_INFO_ATTRIBUTE] = AttributeValue.S(it)
        }

        dynamoClient.putItem(PutItemRequest {
            tableName = REFRESH_TOKEN_TABLE
            this.item = item
        })
    }

    /**
     * Find candidates by prefix (GSI — O(1) read), then BCrypt-verify the match.
     * In the overwhelmingly common case there is exactly one candidate per prefix.
     */
    suspend fun findByPrefix(prefix: String): List<RefreshToken> {
        val queryRequest = QueryRequest {
            tableName = REFRESH_TOKEN_TABLE
            indexName = "tokenPrefix-index"
            keyConditionExpression = "$TOKEN_PREFIX_ATTRIBUTE = :prefix"
            expressionAttributeValues = mapOf(":prefix" to AttributeValue.S(prefix))
        }
        val items = dynamoClient.query(queryRequest).items ?: return emptyList()
        return items.map { mapToRefreshToken(it) }
    }

    suspend fun deleteByUserId(userId: UUID) {
        val queryRequest = QueryRequest {
            tableName = REFRESH_TOKEN_TABLE
            indexName = "userId-index"
            keyConditionExpression = "$USER_ID_ATTRIBUTE = :userId"
            expressionAttributeValues = mapOf(":userId" to AttributeValue.S(userId.toString()))
        }

        dynamoClient.query(queryRequest).items?.forEach { item ->
            item[TOKEN_ID_ATTRIBUTE]?.asS()?.let { deleteTokenById(it) }
        }
    }

    suspend fun deleteExpired() {
        val now = Instant.now().epochSecond
        val scanRequest = ScanRequest {
            tableName = REFRESH_TOKEN_TABLE
            filterExpression = "$EXPIRES_AT_ATTRIBUTE < :now"
            expressionAttributeValues = mapOf(":now" to AttributeValue.N(now.toString()))
        }

        dynamoClient.scan(scanRequest).items?.forEach { item ->
            item[TOKEN_ID_ATTRIBUTE]?.asS()?.let { deleteTokenById(it) }
        }
    }

    suspend fun deleteByTokenHash(tokenHash: String) {
        // findByPrefix is not available here (we only have the hash, not the plain token).
        // This is called from revokeToken which already has the plain token — see RefreshTokenService.
        // As a fallback we scan, but this path is only hit during logout/revoke (low frequency).
        val scanRequest = ScanRequest {
            tableName = REFRESH_TOKEN_TABLE
            filterExpression = "$TOKEN_HASH_ATTRIBUTE = :hash"
            expressionAttributeValues = mapOf(":hash" to AttributeValue.S(tokenHash))
        }
        val items = dynamoClient.scan(scanRequest).items ?: return
        items.firstOrNull()?.let { item ->
            item[TOKEN_ID_ATTRIBUTE]?.asS()?.let { deleteTokenById(it) }
        }
    }

    private suspend fun deleteTokenById(tokenId: String) {
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = REFRESH_TOKEN_TABLE
            key = mapOf(TOKEN_ID_ATTRIBUTE to AttributeValue.S(tokenId))
        })
    }

    private fun mapToRefreshToken(item: Map<String, AttributeValue>): RefreshToken {
        return RefreshToken(
            tokenId     = UUID.fromString(item[TOKEN_ID_ATTRIBUTE]?.asS()     ?: throw Exception("Missing tokenId")),
            userId      = UUID.fromString(item[USER_ID_ATTRIBUTE]?.asS()      ?: throw Exception("Missing userId")),
            tokenHash   = item[TOKEN_HASH_ATTRIBUTE]?.asS()                   ?: throw Exception("Missing tokenHash"),
            tokenPrefix = item[TOKEN_PREFIX_ATTRIBUTE]?.asS()                 ?: "",   // "" for rows written before this change
            expiresAt   = Instant.ofEpochSecond(item[EXPIRES_AT_ATTRIBUTE]?.asN()?.toLong() ?: throw Exception("Missing expiresAt")),
            createdAt   = Instant.ofEpochSecond(item[CREATED_AT_ATTRIBUTE]?.asN()?.toLong() ?: throw Exception("Missing createdAt")),
            deviceInfo  = item[DEVICE_INFO_ATTRIBUTE]?.asS()
        )
    }
}
