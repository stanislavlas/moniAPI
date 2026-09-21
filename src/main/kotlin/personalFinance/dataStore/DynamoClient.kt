package personalFinance.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import personalFinance.models.internal.User
import java.util.*

private const val USER_TABLE_NAME = "personalFinance_users"
private const val USERS_SECONDARY_INDEX_NAME = "email-index"
private const val DATA_ATTRIBUTE = "data"
private const val USER_ID_ATTRIBUTE = "userId"
private const val EMAIL_ATTRIBUTE = "email"

@Component
class DynamoClient(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
): IDataStoreClient {

    override suspend fun getUserByEmail(email: String): User? {
        val queryRequest = QueryRequest {
            tableName = USER_TABLE_NAME
            indexName = USERS_SECONDARY_INDEX_NAME
            keyConditionExpression = "$EMAIL_ATTRIBUTE = :emailValue"
            this.expressionAttributeValues = mapOf(
                ":emailValue" to AttributeValue.S(email),
            )
        }

        val items = dynamoClient.query(queryRequest).items
        if (items.isNullOrEmpty()) {
            return null
        }

        val data = items.first()[DATA_ATTRIBUTE]?.asS() ?: throw Exception("User with email: $email contains incorrect data")
        return objectMapper.readValue(data)
    }

    override suspend fun getUserById(userId: UUID): User {
        val request = GetItemRequest {
            tableName = USER_TABLE_NAME
            key = mapOf(USER_ID_ATTRIBUTE to AttributeValue.S(userId.toString()))
        }

        val item = dynamoClient.getItem(request).item

        if (item.isNullOrEmpty()) {
            throw Exception("User not found")
        }

        val data = item[DATA_ATTRIBUTE]?.asS() ?: throw Exception("User with id: $userId contains incorrect data")
        return objectMapper.readValue(data)
    }

    override suspend fun putUser(user: User) {
        val request = PutItemRequest {
            tableName = USER_TABLE_NAME
            item = mapOf(
                USER_ID_ATTRIBUTE to AttributeValue.S(user.userId.toString()),
                EMAIL_ATTRIBUTE to AttributeValue.S(user.email),
                DATA_ATTRIBUTE to AttributeValue.S(objectMapper.writeValueAsString(user))
            )
        }

        dynamoClient.putItem(request)
    }

    override suspend fun deleteUser(userId: UUID) {
        val request = DeleteItemRequest {
            tableName = USER_TABLE_NAME
            key = mapOf(USER_ID_ATTRIBUTE to AttributeValue.S(userId.toString()))
        }

        dynamoClient.deleteItem(request)
    }

    override suspend fun updateUser(userId: UUID, name: String?, currency: String?, email: String?, notificationsEnabled: Boolean?, notificationFrequency: String?, notificationCustomDays: Int?, notificationTime: String?): personalFinance.models.internal.User {
        val existing = getUserById(userId)
        val updated = existing.copy(
            name                   = name                   ?: existing.name,
            currency               = currency               ?: existing.currency,
            email                  = email                  ?: existing.email,
            notificationsEnabled   = notificationsEnabled   ?: existing.notificationsEnabled,
            notificationFrequency  = notificationFrequency  ?: existing.notificationFrequency,
            notificationCustomDays = notificationCustomDays ?: existing.notificationCustomDays,
            notificationTime       = notificationTime       ?: existing.notificationTime,
        )
        putUser(updated)
        return updated
    }

    override suspend fun updateUserPassword(userId: UUID, encodedPassword: String): personalFinance.models.internal.User {
        val existing = getUserById(userId)
        val updated = existing.copy(password = encodedPassword)
        putUser(updated)
        return updated
    }
}
