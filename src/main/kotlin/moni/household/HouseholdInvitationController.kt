package moni.household

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.common.successResponse
import moni.models.api.InvitationResponse
import moni.models.api.toApi
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
        @Valid @RequestBody request: SendInvitationRequest,
    ): InvitationResponse {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.sendInvitation(userId, request.email).toApi() }
    }

    @GetMapping
    fun getPendingInvitations(
        @RequestHeader("Authorization") authorization: String,
    ): List<InvitationResponse> {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.getPendingInvitationsForUser(userId).map { it.toApi() } }
    }

    @GetMapping("/sent")
    fun getSentInvitations(
        @RequestHeader("Authorization") authorization: String,
    ): List<InvitationResponse> {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking { invitationService.getSentInvitations(userId).map { it.toApi() } }
    }

    @PostMapping("/{invitationId}/accept")
    fun acceptInvitation(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable invitationId: String,
    ): InvitationResponse {
        val userId = authorization.getUserId(jwtAuth)
        val uuid = try { UUID.fromString(invitationId) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid invitation ID format")
        }
        return runBlocking { invitationService.acceptInvitation(userId, uuid).toApi() }
    }

    @PostMapping("/{invitationId}/reject")
    fun rejectInvitation(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable invitationId: String,
    ): InvitationResponse {
        val userId = authorization.getUserId(jwtAuth)
        val uuid = try { UUID.fromString(invitationId) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid invitation ID format")
        }
        return runBlocking { invitationService.rejectInvitation(userId, uuid).toApi() }
    }

    @DeleteMapping("/{invitationId}")
    fun cancelInvitation(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable invitationId: String,
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        val uuid = try { UUID.fromString(invitationId) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid invitation ID format")
        }
        runBlocking { invitationService.cancelInvitation(userId, uuid) }
        return successResponse()
    }
}

data class SendInvitationRequest(
    @field:Email @field:NotBlank val email: String,
)
