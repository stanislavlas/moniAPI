package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import moni.models.internal.VerificationCode

private const val VERIFICATION_TABLE = "moni_verification_codes"
private const val CODE_ATTRIBUTE = "code"
private const val DATA_ATTRIBUTE = "data"

@Repository
class VerificationCodeRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {
    suspend fun save(verificationCode: VerificationCode) {
        // Primary record — keyed by code
        val request = PutItemRequest {
            tableName = VERIFICATION_TABLE
            item = mapOf(
                CODE_ATTRIBUTE to AttributeValue.S(verificationCode.code),
                DATA_ATTRIBUTE to AttributeValue.S(objectMapper.writeValueAsString(verificationCode)),
            )
        }
        dynamoClient.putItem(request)

        // Reverse-lookup record — keyed by "user:<userId>" so findByUserId avoids a full scan
        val reverseRequest = PutItemRequest {
            tableName = VERIFICATION_TABLE
            item = mapOf(
                CODE_ATTRIBUTE to AttributeValue.S("user:${verificationCode.userId}"),
                DATA_ATTRIBUTE to AttributeValue.S(objectMapper.writeValueAsString(verificationCode)),
            )
        }
        dynamoClient.putItem(reverseRequest)
    }

    suspend fun findByUserId(userId: java.util.UUID): VerificationCode? {
        val request = GetItemRequest {
            tableName = VERIFICATION_TABLE
            key = mapOf(CODE_ATTRIBUTE to AttributeValue.S("user:$userId"))
        }
        val item = dynamoClient.getItem(request).item
        if (item.isNullOrEmpty()) return null
        val data = item[DATA_ATTRIBUTE]?.asS() ?: return null
        return try { objectMapper.readValue(data) } catch (_: Exception) { null }
    }

    suspend fun findByCode(code: String): VerificationCode? {
        val request = GetItemRequest {
            tableName = VERIFICATION_TABLE
            key = mapOf(CODE_ATTRIBUTE to AttributeValue.S(code))
        }
        val item = dynamoClient.getItem(request).item
        if (item.isNullOrEmpty()) return null
        val data = item[DATA_ATTRIBUTE]?.asS() ?: return null
        return objectMapper.readValue(data)
    }

    suspend fun deleteByCode(code: String) {
        // Find the record first so we can clean up the reverse-lookup key too
        val existing = findByCode(code)

        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = VERIFICATION_TABLE
            key = mapOf(CODE_ATTRIBUTE to AttributeValue.S(code))
        })

        // Clean up the reverse-lookup record keyed by "user:<userId>"
        existing?.let {
            dynamoClient.deleteItem(DeleteItemRequest {
                tableName = VERIFICATION_TABLE
                key = mapOf(CODE_ATTRIBUTE to AttributeValue.S("user:${it.userId}"))
            })
        }
    }

    suspend fun deleteAllForUser(userId: java.util.UUID) {
        // Find the code via the reverse-lookup record (O(1))
        val record = findByUserId(userId)
        if (record != null) {
            deleteByCode(record.code) // also removes the reverse-lookup record
        }
        // Remove the reverse-lookup record itself in case deleteByCode missed it
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = VERIFICATION_TABLE
            key = mapOf(CODE_ATTRIBUTE to AttributeValue.S("user:$userId"))
        })
    }
}
