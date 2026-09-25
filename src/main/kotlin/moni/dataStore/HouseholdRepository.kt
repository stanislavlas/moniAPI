package moni.dataStore

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import moni.models.internal.Household
import moni.models.internal.HouseholdMember
import moni.models.internal.MemberRole
import java.time.Instant
import java.util.*

private const val HOUSEHOLD_TABLE      = "moni_households"
private const val HOUSEHOLD_ID_ATTRIBUTE = "householdId"
private const val NAME_ATTRIBUTE       = "name"
private const val OWNER_ID_ATTRIBUTE   = "ownerId"
private const val MEMBERS_ATTRIBUTE    = "members"
private const val CREATED_AT_ATTRIBUTE = "createdAt"

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

        dynamoClient.putItem(PutItemRequest { tableName = HOUSEHOLD_TABLE; this.item = item })
    }

    suspend fun findById(householdId: UUID): Household? {
        val result = dynamoClient.getItem(GetItemRequest {
            tableName = HOUSEHOLD_TABLE
            key = mapOf(HOUSEHOLD_ID_ATTRIBUTE to AttributeValue.S(householdId.toString()))
        })
        return result.item?.takeIf { it.isNotEmpty() }?.let { mapToHousehold(it) }
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
        )
    }
}
