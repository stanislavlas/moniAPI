package moni.models.api

import moni.models.internal.MemberRole
import moni.models.internal.User
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

fun moni.models.internal.Household.toApi(memberUsers: Map<UUID, User>) = HouseholdResponse(
    householdId = householdId,
    name        = name,
    ownerId     = ownerId,
    members     = members.mapNotNull { m ->
        val user = memberUsers[m.userId] ?: return@mapNotNull null
        HouseholdMemberResponse(
            userId   = m.userId,
            name     = user.name,
            email    = user.email,
            role     = m.role,
            joinedAt = m.joinedAt,
        )
    },
    createdAt   = createdAt,
)
