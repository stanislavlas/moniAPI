package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import moni.models.internal.VerificationCode
import java.util.UUID

private const val VERIFICATION_TABLE = "moni_verification_codes"
private const val CODE_ATTRIBUTE = "code"
private const val DATA_ATTRIBUTE = "data"

@Repository
class VerificationCodeRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {
    suspend fun save(verificationCode: VerificationCode) {
        val data = objectMapper.writeValueAsString(verificationCode)
        // Write both the primary (code) and reverse-lookup (user:<userId>) records atomically.
        dynamoClient.transactWriteItems(TransactWriteItemsRequest {
            transactItems = listOf(
                TransactWriteItem {
                    put = Put {
                        tableName = VERIFICATION_TABLE
                        item = mapOf(
                            CODE_ATTRIBUTE to AttributeValue.S(verificationCode.code),
                            DATA_ATTRIBUTE to AttributeValue.S(data),
                        )
                    }
                },
                TransactWriteItem {
                    put = Put {
                        tableName = VERIFICATION_TABLE
                        item = mapOf(
                            CODE_ATTRIBUTE to AttributeValue.S("user:${verificationCode.userId}"),
                            DATA_ATTRIBUTE to AttributeValue.S(data),
                        )
                    }
                },
            )
        })
    }

    suspend fun findByUserId(userId: UUID): VerificationCode? {
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
        // Find the primary record to get the userId for the reverse-lookup key.
        val existing = findByCode(code) ?: return

        // Delete both records atomically so neither can be left as a stale orphan.
        dynamoClient.transactWriteItems(TransactWriteItemsRequest {
            transactItems = listOf(
                TransactWriteItem {
                    delete = Delete {
                        tableName = VERIFICATION_TABLE
                        key = mapOf(CODE_ATTRIBUTE to AttributeValue.S(code))
                    }
                },
                TransactWriteItem {
                    delete = Delete {
                        tableName = VERIFICATION_TABLE
                        key = mapOf(CODE_ATTRIBUTE to AttributeValue.S("user:${existing.userId}"))
                    }
                },
            )
        })
    }

    suspend fun deleteAllForUser(userId: UUID) {
        // Find the code via the reverse-lookup record (O(1)) then delete both records atomically.
        val record = findByUserId(userId)
        if (record != null) {
            deleteByCode(record.code)
        } else {
            // Best-effort cleanup of a stale reverse-lookup record that could exist if a
            // previous transactWriteItems partially succeeded and left only the lookup record.
            try {
                dynamoClient.deleteItem(DeleteItemRequest {
                    tableName = VERIFICATION_TABLE
                    key = mapOf(CODE_ATTRIBUTE to AttributeValue.S("user:$userId"))
                })
            } catch (_: Exception) { /* best effort — not critical */ }
        }
    }
}
