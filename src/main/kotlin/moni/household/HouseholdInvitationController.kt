package moni.household

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.models.internal.HouseholdInvitation
import java.util.UUID

@RestController
@RequestMapping("/api/households/invitations")
class HouseholdInvitationController(
    private val invitationService: HouseholdInvitationService,
    private val jwtAuth: JwtAuth,
) {
    @PostMapping
    fun sendInvitation(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: SendInvitationRequest,
    ): HouseholdInvitation {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.sendInvitation(userId, request.email) }
    }

    @GetMapping
    fun getPendingInvitations(
        @RequestHeader("Authorization") authorization: String,
    ): List<HouseholdInvitation> {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.getPendingInvitationsForUser(userId) }
    }

    @GetMapping("/sent")
    fun getSentInvitations(
        @RequestHeader("Authorization") authorization: String,
    ): List<HouseholdInvitation> {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.getSentInvitations(userId) }
    }

    @PostMapping("/{invitationId}/accept")
    fun acceptInvitation(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable invitationId: String,
    ): HouseholdInvitation {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.acceptInvitation(userId, UUID.fromString(invitationId)) }
    }

    @PostMapping("/{invitationId}/reject")
    fun rejectInvitation(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable invitationId: String,
    ): HouseholdInvitation {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.rejectInvitation(userId, UUID.fromString(invitationId)) }
    }

    @DeleteMapping("/{invitationId}")
    fun cancelInvitation(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable invitationId: String,
    ): ResponseEntity<Map<String, Boolean>> {
        val userId = authorization.getUserId(jwtAuth)
        runBlocking { invitationService.cancelInvitation(userId, UUID.fromString(invitationId)) }
        return ResponseEntity.ok(mapOf("success" to true))
    }
}

data class SendInvitationRequest(val email: String)
