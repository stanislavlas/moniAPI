package moni.household

import org.springframework.stereotype.Service
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import moni.category.CategoryService
import moni.config.ForbiddenException
import moni.dataStore.EntryRepository
import moni.dataStore.HouseholdInvitationRepository
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
    private val invitationRepository: HouseholdInvitationRepository,
    private val entryRepository: EntryRepository,
) {

    suspend fun getHouseholdByUserId(userId: UUID): Household? {
        val user = dataStoreClient.getUserById(userId)
        val householdId = user.householdId ?: return null
        val household = householdRepository.findById(householdId)

        // Guard against stale householdId on the user record — the household may have been
        // deleted or the user may have been removed as a member without the user record being
        // updated (e.g. a partial write failure). If the user is not in the members list,
        // clear the stale reference and return null so the UI correctly shows no household.
        if (household == null || household.members.none { it.userId == userId }) {
            dataStoreClient.putUser(user.copy(householdId = null, householdRole = null))
            return null
        }

        return household
    }

    /** Direct lookup by householdId — avoids fetching the User record when the ID is already known. */
    suspend fun getHouseholdById(householdId: UUID): Household? =
        householdRepository.findById(householdId)

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

        // Fetch all member users in parallel to avoid N+1 sequential DynamoDB reads
        val memberUsers = coroutineScope {
            household.members.map { member -> async { dataStoreClient.getUserById(member.userId) } }.awaitAll()
        }
        memberUsers.forEach { memberUser ->
            categoryService.restoreToUser(memberUser.userId, householdId)
            dataStoreClient.putUser(memberUser.copy(householdId = null, householdRole = null))
        }

        // Delete all pending/historic invitations so they don't accumulate as orphans
        invitationRepository.findByHouseholdId(householdId).forEach { invitation ->
            invitationRepository.delete(invitation.invitationId)
        }

        // Delete all entries belonging to the household
        entryRepository.deleteByHouseholdId(householdId)

        householdRepository.delete(householdId)
    }

    /**
     * Adds [joiningUserId] as a MEMBER of [household].
     * Precondition: caller has already validated ownership / invitation.
     *
     * Membership is checked against the household's own [members] list — the single source
     * of truth fetched from DynamoDB at call time. The user record's [householdId] field is
     * a denormalised cache that can be stale if a previous join/leave failed partway through,
     * so it is NOT used as the authoritative check here.
     */
    suspend fun joinHousehold(household: Household, joiningUserId: UUID): Household {
        val newMember = dataStoreClient.getUserById(joiningUserId)
        // Use household.members as the authoritative source — avoids acting on a stale
        // User.householdId that could be out of sync after a partial earlier write.
        if (household.members.any { it.userId == joiningUserId })
            throw IllegalArgumentException("User is already a member of this household")

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
        assertMembership(userId, household)
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
