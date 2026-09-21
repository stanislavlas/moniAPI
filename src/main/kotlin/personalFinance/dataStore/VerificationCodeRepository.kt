package personalFinance.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import personalFinance.models.internal.VerificationCode

private const val VERIFICATION_TABLE = "personalFinance_verification_codes"
private const val CODE_ATTRIBUTE = "code"
private const val DATA_ATTRIBUTE = "data"

@Repository
class VerificationCodeRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {
    suspend fun save(verificationCode: VerificationCode) {
        val request = PutItemRequest {
            tableName = VERIFICATION_TABLE
            item = mapOf(
                CODE_ATTRIBUTE to AttributeValue.S(verificationCode.code),
                DATA_ATTRIBUTE to AttributeValue.S(objectMapper.writeValueAsString(verificationCode)),
            )
        }
        dynamoClient.putItem(request)
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
        val request = DeleteItemRequest {
            tableName = VERIFICATION_TABLE
            key = mapOf(CODE_ATTRIBUTE to AttributeValue.S(code))
        }
        dynamoClient.deleteItem(request)
    }
}
