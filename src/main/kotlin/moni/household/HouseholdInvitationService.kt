package moni.household

import org.springframework.stereotype.Service
import moni.dataStore.HouseholdInvitationRepository
import moni.dataStore.HouseholdRepository
import moni.dataStore.IDataStoreClient
import moni.models.internal.HouseholdInvitation
import moni.models.internal.InvitationStatus
import java.time.Instant
import java.util.UUID

@Service
class HouseholdInvitationService(
    private val invitationRepository: HouseholdInvitationRepository,
    private val householdRepository: HouseholdRepository,
    private val householdService: HouseholdService,
    private val dataStoreClient: IDataStoreClient,
) {
    /** Owner sends an invitation to [invitedEmail]. */
    suspend fun sendInvitation(ownerUserId: UUID, invitedEmail: String): HouseholdInvitation {
        val owner = dataStoreClient.getUserById(ownerUserId)
        val household = householdRepository.findById(
            owner.householdId ?: throw IllegalArgumentException("You are not in a household")
        ) ?: throw IllegalArgumentException("Household not found")
        if (household.ownerId != ownerUserId) throw IllegalArgumentException("Only the owner can invite members")
        if (household.members.any { it.email.equals(invitedEmail, ignoreCase = true) })
            throw IllegalArgumentException("That user is already a member of this household")

        val existing = invitationRepository.findByInvitedEmail(invitedEmail.lowercase())
            .firstOrNull { it.householdId == household.householdId && it.status == InvitationStatus.PENDING }
        if (existing != null) throw IllegalArgumentException("A pending invitation already exists for that email")

        val invitation = HouseholdInvitation(
            invitationId    = UUID.randomUUID(),
            householdId     = household.householdId,
            householdName   = household.name,
            invitedByUserId = ownerUserId,
            invitedByName   = owner.name,
            invitedEmail    = invitedEmail.lowercase(),
        )
        invitationRepository.save(invitation)
        return invitation
    }

    /** Returns all PENDING, non-expired invitations addressed to the authenticated user's email. */
    suspend fun getPendingInvitationsForUser(userId: UUID): List<HouseholdInvitation> {
        val user = dataStoreClient.getUserById(userId)
        val now = Instant.now()
        return invitationRepository.findByInvitedEmail(user.email.lowercase())
            .filter { it.status == InvitationStatus.PENDING && it.expiresAt.isAfter(now) }
    }

    /** Returns all invitations sent from the caller's household (any status). */
    suspend fun getSentInvitations(ownerUserId: UUID): List<HouseholdInvitation> {
        val owner = dataStoreClient.getUserById(ownerUserId)
        val householdId = owner.householdId ?: throw IllegalArgumentException("You are not in a household")
        val household = householdRepository.findById(householdId) ?: throw IllegalArgumentException("Household not found")
        if (household.ownerId != ownerUserId) throw IllegalArgumentException("Only the owner can view sent invitations")
        return invitationRepository.findByHouseholdId(householdId)
    }

    /** Invitee accepts: joins the household and marks invitation ACCEPTED. */
    suspend fun acceptInvitation(userId: UUID, invitationId: UUID): HouseholdInvitation {
        val user = dataStoreClient.getUserById(userId)
        val invitation = requirePendingInvitation(invitationId, user.email)
        val household = householdRepository.findById(invitation.householdId)
            ?: throw IllegalArgumentException("Household no longer exists")

        householdService.joinHousehold(household, userId)

        val updated = invitation.copy(status = InvitationStatus.ACCEPTED, respondedAt = Instant.now())
        invitationRepository.save(updated)
        return updated
    }

    /** Invitee rejects: marks invitation REJECTED. */
    suspend fun rejectInvitation(userId: UUID, invitationId: UUID): HouseholdInvitation {
        val user = dataStoreClient.getUserById(userId)
        val invitation = requirePendingInvitation(invitationId, user.email)
        val updated = invitation.copy(status = InvitationStatus.REJECTED, respondedAt = Instant.now())
        invitationRepository.save(updated)
        return updated
    }

    /** Owner cancels a pending invitation. */
    suspend fun cancelInvitation(ownerUserId: UUID, invitationId: UUID) {
        val invitation = invitationRepository.findById(invitationId)
            ?: throw NoSuchElementException("Invitation not found")
        val owner = dataStoreClient.getUserById(ownerUserId)
        val householdId = owner.householdId ?: throw IllegalArgumentException("You are not in a household")
        if (invitation.householdId != householdId) throw IllegalArgumentException("Invitation does not belong to your household")
        val household = householdRepository.findById(householdId) ?: throw IllegalArgumentException("Household not found")
        if (household.ownerId != ownerUserId) throw IllegalArgumentException("Only the owner can cancel invitations")
        if (invitation.status != InvitationStatus.PENDING) throw IllegalArgumentException("Only PENDING invitations can be cancelled")

        val updated = invitation.copy(status = InvitationStatus.CANCELLED, respondedAt = Instant.now())
        invitationRepository.save(updated)
    }

    private suspend fun requirePendingInvitation(invitationId: UUID, userEmail: String): HouseholdInvitation {
        val invitation = invitationRepository.findById(invitationId)
            ?: throw NoSuchElementException("Invitation not found")
        if (!invitation.invitedEmail.equals(userEmail, ignoreCase = true))
            throw IllegalArgumentException("This invitation was not sent to you")
        if (invitation.status != InvitationStatus.PENDING)
            throw IllegalArgumentException("Invitation is no longer pending (status: ${invitation.status})")
        if (invitation.expiresAt.isBefore(Instant.now()))
            throw IllegalArgumentException("Invitation has expired")
        return invitation
    }
}
