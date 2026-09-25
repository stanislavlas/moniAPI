package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import moni.models.internal.HouseholdInvitation
import java.util.UUID

private const val INVITATIONS_TABLE  = "moni_household_invitations"
private const val INVITATION_ID_ATTR = "invitationId"
private const val HOUSEHOLD_ID_ATTR  = "householdId"
private const val INVITED_EMAIL_ATTR = "invitedEmail"
private const val DATA_ATTR          = "data"

@Repository
class HouseholdInvitationRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {
    suspend fun save(invitation: HouseholdInvitation) {
        val item = mapOf(
            INVITATION_ID_ATTR to AttributeValue.S(invitation.invitationId.toString()),
            HOUSEHOLD_ID_ATTR  to AttributeValue.S(invitation.householdId.toString()),
            INVITED_EMAIL_ATTR to AttributeValue.S(invitation.invitedEmail),
            DATA_ATTR          to AttributeValue.S(objectMapper.writeValueAsString(invitation)),
        )
        dynamoClient.putItem(PutItemRequest { tableName = INVITATIONS_TABLE; this.item = item })
    }

    suspend fun findById(invitationId: UUID): HouseholdInvitation? {
        val result = dynamoClient.getItem(GetItemRequest {
            tableName = INVITATIONS_TABLE
            key = mapOf(INVITATION_ID_ATTR to AttributeValue.S(invitationId.toString()))
        })
        return result.item?.takeIf { it.isNotEmpty() }?.let { deserialize(it) }
    }

    /** All invitations sent TO this email address (any status). */
    suspend fun findByInvitedEmail(email: String): List<HouseholdInvitation> {
        val result = dynamoClient.query(QueryRequest {
            tableName = INVITATIONS_TABLE
            indexName = "invitedEmail-index"
            keyConditionExpression = "$INVITED_EMAIL_ATTR = :email"
            expressionAttributeValues = mapOf(":email" to AttributeValue.S(email))
        })
        return result.items?.map { deserialize(it) } ?: emptyList()
    }

    /** All invitations sent FROM a household (any status). */
    suspend fun findByHouseholdId(householdId: UUID): List<HouseholdInvitation> {
        val result = dynamoClient.query(QueryRequest {
            tableName = INVITATIONS_TABLE
            indexName = "householdId-index"
            keyConditionExpression = "$HOUSEHOLD_ID_ATTR = :hid"
            expressionAttributeValues = mapOf(":hid" to AttributeValue.S(householdId.toString()))
        })
        return result.items?.map { deserialize(it) } ?: emptyList()
    }

    suspend fun delete(invitationId: UUID) {
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = INVITATIONS_TABLE
            key = mapOf(INVITATION_ID_ATTR to AttributeValue.S(invitationId.toString()))
        })
    }

    private fun deserialize(item: Map<String, AttributeValue>): HouseholdInvitation {
        val data = item[DATA_ATTR]?.asS() ?: error("Missing data attribute in invitation item")
        return objectMapper.readValue(data)
    }
}
