package moni.models.api

import moni.models.internal.MemberRole
import java.time.Instant
import java.util.UUID

data class HouseholdMemberResponse(
    val userId: UUID,
    val name: String,
    val email: String,
    val role: MemberRole,
    val joinedAt: Instant,
)

data class HouseholdResponse(
    val householdId: UUID,
    val name: String,
    val ownerId: UUID,
    val members: List<HouseholdMemberResponse>,
    val createdAt: Instant,
)

fun moni.models.internal.Household.toApi() = HouseholdResponse(
    householdId = householdId,
    name        = name,
    ownerId     = ownerId,
    members     = members.map { m ->
        HouseholdMemberResponse(
            userId   = m.userId,
            name     = m.name,
            email    = m.email,
            role     = m.role,
            joinedAt = m.joinedAt,
        )
    },
    createdAt   = createdAt,
)
