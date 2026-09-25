package moni.models.internal

import java.time.Instant
import java.util.*

data class Household(
    val householdId: UUID,
    val name: String,
    val ownerId: UUID,
    val members: List<HouseholdMember>,
    val createdAt: Instant = Instant.now(),
)

data class HouseholdMember(
    val userId: UUID,
    val name: String,
    val email: String,
    val role: MemberRole,
    val joinedAt: Instant = Instant.now()
)

enum class MemberRole {
    OWNER,
    MEMBER
}
