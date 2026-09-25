package moni.models.internal

import java.time.Instant
import java.util.UUID

enum class InvitationStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    CANCELLED
}

data class HouseholdInvitation(
    val invitationId: UUID,
    val householdId: UUID,
    val householdName: String,
    val invitedByUserId: UUID,
    val invitedByName: String,
    val invitedEmail: String,
    val status: InvitationStatus = InvitationStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant = Instant.now().plusSeconds(7 * 24 * 3600), // 7 days
    val respondedAt: Instant? = null,
)
