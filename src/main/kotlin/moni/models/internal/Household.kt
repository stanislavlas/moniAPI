package moni.models.internal

import java.time.Instant
import java.util.*

data class Household(
    val householdId: UUID,
    val name: String,
    val ownerId: UUID,
    val members: List<HouseholdMember>,
    val createdAt: Instant = Instant.now(),
    val inviteCode: String? = null
)

data class HouseholdMember(
    val userId: UUID,
    val userName: String,
    val email: String,
    val role: MemberRole,
    val joinedAt: Instant = Instant.now()
)

enum class MemberRole {
    OWNER,
    MEMBER
}
