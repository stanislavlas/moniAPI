package moni.models.api

import moni.models.internal.InvitationStatus
import java.time.Instant
import java.util.UUID

data class InvitationResponse(
    val invitationId: UUID,
    val householdId: UUID,
    val householdName: String,
    val invitedByName: String,
    val invitedEmail: String,
    val status: InvitationStatus,
    val createdAt: Instant,
    val expiresAt: Instant,
    val respondedAt: Instant?,
)

fun moni.models.internal.HouseholdInvitation.toApi() = InvitationResponse(
    invitationId  = invitationId,
    householdId   = householdId,
    householdName = householdName,
    invitedByName = invitedByName,
    invitedEmail  = invitedEmail,
    status        = status,
    createdAt     = createdAt,
    expiresAt     = expiresAt,
    respondedAt   = respondedAt,
)
