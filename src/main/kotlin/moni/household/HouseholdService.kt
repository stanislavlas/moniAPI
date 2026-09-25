package moni.household

import org.springframework.stereotype.Service
import moni.category.CategoryService
import moni.config.ForbiddenException
import moni.dataStore.HouseholdRepository
import moni.dataStore.IDataStoreClient
import moni.models.internal.Household
import moni.models.internal.HouseholdMember
import moni.models.internal.MemberRole
import java.util.*

@Service
class HouseholdService(
    private val householdRepository: HouseholdRepository,
    private val dataStoreClient: IDataStoreClient,
    private val categoryService: CategoryService,
) {

    suspend fun getHouseholdByUserId(userId: UUID): Household? {
        val user = dataStoreClient.getUserById(userId)
        val householdId = user.householdId ?: return null
        return householdRepository.findById(householdId)
    }

    suspend fun createHousehold(userId: UUID, name: String): Household {
        val user = dataStoreClient.getUserById(userId)
        if (user.householdId != null) throw IllegalArgumentException("User is already in a household")

        val household = Household(
            householdId = UUID.randomUUID(),
            name        = name,
            ownerId     = userId,
            members     = listOf(HouseholdMember(userId, user.name, user.email, MemberRole.OWNER)),
        )
        householdRepository.save(household)
        categoryService.assignToHousehold(userId, household.householdId)
        dataStoreClient.putUser(user.copy(householdId = household.householdId, householdRole = MemberRole.OWNER))
        return household
    }

    suspend fun renameHousehold(householdId: UUID, userId: UUID, newName: String): Household {
        val household = householdRepository.findById(householdId) ?: throw NoSuchElementException("Household not found")
        if (household.ownerId != userId) throw ForbiddenException("Only the owner can rename the household")
        val updated = household.copy(name = newName)
        householdRepository.save(updated)
        return updated
    }

    suspend fun deleteHousehold(householdId: UUID, userId: UUID) {
        val household = householdRepository.findById(householdId) ?: throw NoSuchElementException("Household not found")
        if (household.ownerId != userId) throw ForbiddenException("Only the owner can delete the household")

        household.members.forEach { member ->
            categoryService.restoreToUser(member.userId, householdId)
            val memberUser = dataStoreClient.getUserById(member.userId)
            dataStoreClient.putUser(memberUser.copy(householdId = null, householdRole = null))
        }
        householdRepository.delete(householdId)
    }

    /**
     * Adds [joiningUserId] as a MEMBER of [household].
     * Precondition: caller has already validated ownership / invitation.
     */
    suspend fun joinHousehold(household: Household, joiningUserId: UUID): Household {
        val newMember = dataStoreClient.getUserById(joiningUserId)
        if (newMember.householdId != null) throw IllegalArgumentException("That user is already in a household")
        if (household.members.any { it.userId == joiningUserId }) throw IllegalArgumentException("User is already a member of this household")

        val updated = household.copy(
            members = household.members + HouseholdMember(newMember.userId, newMember.name, newMember.email, MemberRole.MEMBER)
        )
        householdRepository.save(updated)
        categoryService.assignToHousehold(joiningUserId, household.householdId)
        dataStoreClient.putUser(newMember.copy(householdId = household.householdId, householdRole = MemberRole.MEMBER))
        return updated
    }

    suspend fun removeMember(householdId: UUID, ownerId: UUID, memberUserId: UUID): Household {
        val household = householdRepository.findById(householdId) ?: throw NoSuchElementException("Household not found")
        if (household.ownerId != ownerId) throw ForbiddenException("Only the owner can remove members")
        if (memberUserId == ownerId) throw IllegalArgumentException("Owner cannot remove themselves")

        val updatedMembers = household.members.filter { it.userId != memberUserId }
        if (updatedMembers.size == household.members.size) throw NoSuchElementException("Member not found in household")

        val updated = household.copy(members = updatedMembers)
        householdRepository.save(updated)
        categoryService.restoreToUser(memberUserId, householdId)
        val memberUser = dataStoreClient.getUserById(memberUserId)
        dataStoreClient.putUser(memberUser.copy(householdId = null, householdRole = null))
        return updated
    }

    suspend fun leaveHousehold(householdId: UUID, userId: UUID) {
        val household = householdRepository.findById(householdId) ?: throw NoSuchElementException("Household not found")
        if (household.ownerId == userId) throw ForbiddenException("Owner cannot leave household. Delete it or transfer ownership first.")

        householdRepository.save(household.copy(members = household.members.filter { it.userId != userId }))
        categoryService.restoreToUser(userId, householdId)
        val user = dataStoreClient.getUserById(userId)
        dataStoreClient.putUser(user.copy(householdId = null, householdRole = null))
    }

    /**
     * Asserts that [userId] is a member of [householdId].
     * Throws [NoSuchElementException] if the household doesn't exist, or [ForbiddenException] if the user is not a member.
     */
    suspend fun assertMembership(userId: UUID, householdId: UUID) {
        val household = householdRepository.findById(householdId)
            ?: throw NoSuchElementException("Household not found")
        if (household.members.none { it.userId == userId })
            throw ForbiddenException("User is not a member of this household")
    }

    /**
     * Asserts that [userId] is a member of the given [household] (already fetched).
     * Avoids a second DynamoDB lookup when the caller already has the household object.
     */
    fun assertMembership(userId: UUID, household: Household) {
        if (household.members.none { it.userId == userId })
            throw ForbiddenException("User is not a member of this household")
    }
}
