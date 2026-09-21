package personalFinance.household

import org.springframework.stereotype.Service
import personalFinance.category.CategoryService
import personalFinance.dataStore.HouseholdRepository
import personalFinance.dataStore.IDataStoreClient
import personalFinance.models.internal.Household
import personalFinance.models.internal.HouseholdMember
import personalFinance.models.internal.MemberRole
import java.security.SecureRandom
import java.util.*

@Service
class HouseholdService(
    private val householdRepository: HouseholdRepository,
    private val dataStoreClient: IDataStoreClient,
    private val categoryService: CategoryService,
) {
    private val secureRandom = SecureRandom()

    suspend fun getHouseholdByUserId(userId: UUID): Household? {
        val user = dataStoreClient.getUserById(userId)
        val householdId = user.householdId ?: return null
        return householdRepository.findById(householdId)
    }

    suspend fun createHousehold(userId: UUID, name: String): Household {
        val user = dataStoreClient.getUserById(userId)
        if (user.householdId != null) throw Exception("User is already in a household")

        val household = Household(
            householdId = UUID.randomUUID(),
            name        = name,
            ownerId     = userId,
            members     = listOf(HouseholdMember(userId, user.name, user.email, MemberRole.OWNER)),
            inviteCode  = generateInviteCode(),
        )
        householdRepository.save(household)
        categoryService.assignToHousehold(userId, household.householdId)
        dataStoreClient.putUser(user.copy(householdId = household.householdId, householdRole = MemberRole.OWNER))
        return household
    }

    suspend fun renameHousehold(householdId: UUID, userId: UUID, newName: String): Household {
        val household = householdRepository.findById(householdId) ?: throw Exception("Household not found")
        if (household.ownerId != userId) throw Exception("Only the owner can rename the household")
        val updated = household.copy(name = newName)
        householdRepository.save(updated)
        return updated
    }

    suspend fun deleteHousehold(householdId: UUID, userId: UUID) {
        val household = householdRepository.findById(householdId) ?: throw Exception("Household not found")
        if (household.ownerId != userId) throw Exception("Only the owner can delete the household")

        household.members.forEach { member ->
            categoryService.restoreToUser(member.userId, householdId)
            val memberUser = dataStoreClient.getUserById(member.userId)
            dataStoreClient.putUser(memberUser.copy(householdId = null, householdRole = null))
        }
        householdRepository.delete(householdId)
    }

    suspend fun addMember(householdId: UUID, userId: UUID, memberEmail: String): Household {
        val household = householdRepository.findById(householdId) ?: throw Exception("Household not found")
        if (household.ownerId != userId) throw IllegalArgumentException("Only the owner can add members")

        val newMember = dataStoreClient.getUserByEmail(memberEmail)
            ?: throw IllegalArgumentException("No account found for $memberEmail")
        if (newMember.householdId != null) throw IllegalArgumentException("That user is already in a household")
        if (household.members.any { it.userId == newMember.userId }) throw IllegalArgumentException("User is already a member of this household")

        val updated = household.copy(
            members = household.members + HouseholdMember(newMember.userId, newMember.name, newMember.email, MemberRole.MEMBER)
        )
        householdRepository.save(updated)
        categoryService.assignToHousehold(newMember.userId, householdId)
        dataStoreClient.putUser(newMember.copy(householdId = householdId, householdRole = MemberRole.MEMBER))
        return updated
    }

    suspend fun removeMember(householdId: UUID, ownerId: UUID, memberUserId: UUID): Household {
        val household = householdRepository.findById(householdId) ?: throw Exception("Household not found")
        if (household.ownerId != ownerId) throw Exception("Only the owner can remove members")
        if (memberUserId == ownerId) throw Exception("Owner cannot remove themselves")

        val updatedMembers = household.members.filter { it.userId != memberUserId }
        if (updatedMembers.size == household.members.size) throw Exception("Member not found in household")

        val updated = household.copy(members = updatedMembers)
        householdRepository.save(updated)
        categoryService.restoreToUser(memberUserId, householdId)
        val memberUser = dataStoreClient.getUserById(memberUserId)
        dataStoreClient.putUser(memberUser.copy(householdId = null, householdRole = null))
        return updated
    }

    suspend fun leaveHousehold(householdId: UUID, userId: UUID) {
        val household = householdRepository.findById(householdId) ?: throw Exception("Household not found")
        if (household.ownerId == userId) throw Exception("Owner cannot leave household. Delete it or transfer ownership first.")

        householdRepository.save(household.copy(members = household.members.filter { it.userId != userId }))
        categoryService.restoreToUser(userId, householdId)
        val user = dataStoreClient.getUserById(userId)
        dataStoreClient.putUser(user.copy(householdId = null, householdRole = null))
    }

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
    }
}
