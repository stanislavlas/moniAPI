package moni.household

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import moni.dataStore.HouseholdInvitationRepository
import moni.dataStore.HouseholdRepository
import moni.dataStore.IDataStoreClient
import moni.models.internal.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class HouseholdInvitationServiceTest {

    private val invitationRepo   = mockk<HouseholdInvitationRepository>(relaxed = true)
    private val householdRepo    = mockk<HouseholdRepository>(relaxed = true)
    private val householdService = mockk<HouseholdService>(relaxed = true)
    private val dataStoreClient  = mockk<IDataStoreClient>()

    private val service = HouseholdInvitationService(invitationRepo, householdRepo, householdService, dataStoreClient)

    private val ownerId     = UUID.randomUUID()
    private val inviteeId   = UUID.randomUUID()
    private val householdId = UUID.randomUUID()

    private val owner = User(
        userId = ownerId, name = "Alice", email = "alice@example.com",
        password = "hash", currency = "EUR",
        householdId = householdId, householdRole = MemberRole.OWNER,
    )
    private val invitee = User(
        userId = inviteeId, name = "Bob", email = "bob@example.com",
        password = "hash", currency = "EUR",
    )
    private val household = Household(
        householdId = householdId, name = "Our Home", ownerId = ownerId,
        members = listOf(HouseholdMember(ownerId, "Alice", "alice@example.com", MemberRole.OWNER)),
    )

    @BeforeEach
    fun setUp() {
        coEvery { dataStoreClient.getUserById(ownerId) } returns owner
        coEvery { dataStoreClient.getUserById(inviteeId) } returns invitee
        coEvery { householdRepo.findById(householdId) } returns household
        coEvery { invitationRepo.findByInvitedEmail("bob@example.com") } returns emptyList()
    }

    // ── sendInvitation ────────────────────────────────────────────────────────

    @Test
    fun `sendInvitation saves and returns invitation`() = runBlocking {
        val result = service.sendInvitation(ownerId, "bob@example.com")
        assertEquals(householdId, result.householdId)
        assertEquals("bob@example.com", result.invitedEmail)
        assertEquals(InvitationStatus.PENDING, result.status)
        coVerify(exactly = 1) { invitationRepo.save(any()) }
    }

    @Test
    fun `sendInvitation throws when caller is not owner`() {
        val nonOwnerId = UUID.randomUUID()
        coEvery { dataStoreClient.getUserById(nonOwnerId) } returns owner.copy(userId = nonOwnerId)
        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendInvitation(nonOwnerId, "bob@example.com") }
        }
    }

    @Test
    fun `sendInvitation throws when invitee is already a member`() {
        val hhWithBob = household.copy(
            members = household.members + HouseholdMember(inviteeId, "Bob", "bob@example.com", MemberRole.MEMBER)
        )
        coEvery { householdRepo.findById(householdId) } returns hhWithBob
        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendInvitation(ownerId, "bob@example.com") }
        }
    }

    @Test
    fun `sendInvitation throws when pending invite already exists`() {
        val pending = HouseholdInvitation(
            invitationId = UUID.randomUUID(), householdId = householdId,
            householdName = "Our Home", invitedByUserId = ownerId,
            invitedByName = "Alice", invitedEmail = "bob@example.com",
            status = InvitationStatus.PENDING,
        )
        coEvery { invitationRepo.findByInvitedEmail("bob@example.com") } returns listOf(pending)
        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendInvitation(ownerId, "bob@example.com") }
        }
    }

    // ── acceptInvitation ──────────────────────────────────────────────────────

    @Test
    fun `acceptInvitation calls joinHousehold and marks ACCEPTED`() = runBlocking {
        val invId = UUID.randomUUID()
        val invitation = HouseholdInvitation(
            invitationId = invId, householdId = householdId,
            householdName = "Our Home", invitedByUserId = ownerId,
            invitedByName = "Alice", invitedEmail = "bob@example.com",
            status = InvitationStatus.PENDING, expiresAt = Instant.now().plusSeconds(3600),
        )
        coEvery { invitationRepo.findById(invId) } returns invitation

        val result = service.acceptInvitation(inviteeId, invId)

        assertEquals(InvitationStatus.ACCEPTED, result.status)
        assertNotNull(result.respondedAt)
        coVerify(exactly = 1) { householdService.joinHousehold(household, inviteeId) }
        coVerify(exactly = 1) { invitationRepo.save(match { it.status == InvitationStatus.ACCEPTED }) }
    }

    @Test
    fun `acceptInvitation throws when invitation belongs to different user`() {
        val invId = UUID.randomUUID()
        coEvery { invitationRepo.findById(invId) } returns HouseholdInvitation(
            invitationId = invId, householdId = householdId,
            householdName = "Our Home", invitedByUserId = ownerId,
            invitedByName = "Alice", invitedEmail = "someone-else@example.com",
            status = InvitationStatus.PENDING, expiresAt = Instant.now().plusSeconds(3600),
        )
        assertThrows<IllegalArgumentException> {
            runBlocking { service.acceptInvitation(inviteeId, invId) }
        }
    }

    @Test
    fun `acceptInvitation throws when invitation is expired`() {
        val invId = UUID.randomUUID()
        coEvery { invitationRepo.findById(invId) } returns HouseholdInvitation(
            invitationId = invId, householdId = householdId,
            householdName = "Our Home", invitedByUserId = ownerId,
            invitedByName = "Alice", invitedEmail = "bob@example.com",
            status = InvitationStatus.PENDING, expiresAt = Instant.now().minusSeconds(1),
        )
        assertThrows<IllegalArgumentException> {
            runBlocking { service.acceptInvitation(inviteeId, invId) }
        }
    }

    // ── rejectInvitation ──────────────────────────────────────────────────────

    @Test
    fun `rejectInvitation marks invitation REJECTED without joining`() = runBlocking {
        val invId = UUID.randomUUID()
        coEvery { invitationRepo.findById(invId) } returns HouseholdInvitation(
            invitationId = invId, householdId = householdId,
            householdName = "Our Home", invitedByUserId = ownerId,
            invitedByName = "Alice", invitedEmail = "bob@example.com",
            status = InvitationStatus.PENDING, expiresAt = Instant.now().plusSeconds(3600),
        )
        val result = service.rejectInvitation(inviteeId, invId)
        assertEquals(InvitationStatus.REJECTED, result.status)
        coVerify(exactly = 0) { householdService.joinHousehold(any(), any()) }
    }

    // ── cancelInvitation ──────────────────────────────────────────────────────

    @Test
    fun `cancelInvitation marks invitation CANCELLED`() = runBlocking {
        val invId = UUID.randomUUID()
        coEvery { invitationRepo.findById(invId) } returns HouseholdInvitation(
            invitationId = invId, householdId = householdId,
            householdName = "Our Home", invitedByUserId = ownerId,
            invitedByName = "Alice", invitedEmail = "bob@example.com",
            status = InvitationStatus.PENDING, expiresAt = Instant.now().plusSeconds(3600),
        )
        service.cancelInvitation(ownerId, invId)
        coVerify(exactly = 1) { invitationRepo.save(match { it.status == InvitationStatus.CANCELLED }) }
    }
}
