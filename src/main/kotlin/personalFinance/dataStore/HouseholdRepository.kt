package personalFinance.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import personalFinance.models.internal.Household
import personalFinance.models.internal.HouseholdMember
import personalFinance.models.internal.MemberRole
import java.time.Instant
import java.util.*

private const val HOUSEHOLD_TABLE      = "personalFinance_households"
private const val HOUSEHOLD_ID_ATTRIBUTE = "householdId"
private const val NAME_ATTRIBUTE       = "name"
private const val OWNER_ID_ATTRIBUTE   = "ownerId"
private const val MEMBERS_ATTRIBUTE    = "members"
private const val CREATED_AT_ATTRIBUTE = "createdAt"
private const val INVITE_CODE_ATTRIBUTE = "inviteCode"

@Repository
class HouseholdRepository(
    private val dynamoClient: DynamoDbClient,
    private val objectMapper: ObjectMapper,
) {

    suspend fun save(household: Household) {
        val item = mutableMapOf(
            HOUSEHOLD_ID_ATTRIBUTE to AttributeValue.S(household.householdId.toString()),
            NAME_ATTRIBUTE         to AttributeValue.S(household.name),
            OWNER_ID_ATTRIBUTE     to AttributeValue.S(household.ownerId.toString()),
            MEMBERS_ATTRIBUTE      to AttributeValue.S(objectMapper.writeValueAsString(household.members)),
            CREATED_AT_ATTRIBUTE   to AttributeValue.N(household.createdAt.epochSecond.toString()),
        )
        household.inviteCode?.let { item[INVITE_CODE_ATTRIBUTE] = AttributeValue.S(it) }

        dynamoClient.putItem(PutItemRequest { tableName = HOUSEHOLD_TABLE; this.item = item })
    }

    suspend fun findById(householdId: UUID): Household? {
        val result = dynamoClient.getItem(GetItemRequest {
            tableName = HOUSEHOLD_TABLE
            key = mapOf(HOUSEHOLD_ID_ATTRIBUTE to AttributeValue.S(householdId.toString()))
        })
        return result.item?.takeIf { it.isNotEmpty() }?.let { mapToHousehold(it) }
    }

    suspend fun findByOwnerId(ownerId: UUID): List<Household> {
        val result = dynamoClient.query(QueryRequest {
            tableName = HOUSEHOLD_TABLE
            indexName = "ownerId-index"
            keyConditionExpression = "$OWNER_ID_ATTRIBUTE = :ownerId"
            expressionAttributeValues = mapOf(":ownerId" to AttributeValue.S(ownerId.toString()))
        })
        return result.items?.map { mapToHousehold(it) } ?: emptyList()
    }

    suspend fun delete(householdId: UUID) {
        dynamoClient.deleteItem(DeleteItemRequest {
            tableName = HOUSEHOLD_TABLE
            key = mapOf(HOUSEHOLD_ID_ATTRIBUTE to AttributeValue.S(householdId.toString()))
        })
    }

    private fun mapToHousehold(item: Map<String, AttributeValue>): Household {
        val membersJson = item[MEMBERS_ATTRIBUTE]?.asS() ?: "[]"
        val members: List<HouseholdMember> = objectMapper.readValue(membersJson)

        return Household(
            householdId = UUID.fromString(item[HOUSEHOLD_ID_ATTRIBUTE]?.asS() ?: error("Missing householdId")),
            name        = item[NAME_ATTRIBUTE]?.asS()  ?: error("Missing name"),
            ownerId     = UUID.fromString(item[OWNER_ID_ATTRIBUTE]?.asS() ?: error("Missing ownerId")),
            members     = members,
            createdAt   = Instant.ofEpochSecond(item[CREATED_AT_ATTRIBUTE]?.asN()?.toLong() ?: error("Missing createdAt")),
            inviteCode  = item[INVITE_CODE_ATTRIBUTE]?.asS(),
        )
    }
}
