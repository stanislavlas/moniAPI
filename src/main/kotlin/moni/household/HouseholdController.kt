package moni.household

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import moni.auth.JwtAuth
import moni.common.getUserId
import moni.common.successResponse
import moni.dataStore.IDataStoreClient
import moni.models.api.HouseholdResponse
import moni.models.api.toApi
import java.util.*

@RestController
@RequestMapping("/api/households")
class HouseholdController(
    private val householdService: HouseholdService,
    private val jwtAuth: JwtAuth,
    private val dataStoreClient: IDataStoreClient,
) {
    @GetMapping
    fun getHousehold(
        @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<HouseholdResponse> {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            val household = householdService.getHouseholdByUserId(userId)
            if (household != null) {
                val memberUsers = householdService.fetchMemberUsers(household)
                ResponseEntity.ok(household.toApi(memberUsers))
            } else {
                ResponseEntity.noContent().build()
            }
        }
    }

    @PostMapping
    fun createHousehold(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: CreateHouseholdRequest
    ): HouseholdResponse {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            val household = householdService.createHousehold(userId = userId, name = request.name)
            val memberUsers = householdService.fetchMemberUsers(household)
            household.toApi(memberUsers)
        }
    }

    @PutMapping
    fun renameHousehold(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: RenameHouseholdRequest
    ): HouseholdResponse {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            val householdId = requireHouseholdId(userId)
            val household = householdService.renameHousehold(householdId = householdId, userId = userId, newName = request.name)
            val memberUsers = householdService.fetchMemberUsers(household)
            household.toApi(memberUsers)
        }
    }

    @DeleteMapping
    fun deleteHousehold(
        @RequestHeader("Authorization") authorization: String
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        runBlocking {
            val householdId = requireHouseholdId(userId)
            householdService.deleteHousehold(householdId = householdId, userId = userId)
        }
        return successResponse()
    }

    @DeleteMapping("/members/{memberId}")
    fun removeMember(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable memberId: String
    ): HouseholdResponse {
        val ownerId = authorization.getUserId(jwtAuth)
        val memberUUID = try { UUID.fromString(memberId) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid member ID format")
        }
        return runBlocking {
            val householdId = requireHouseholdId(ownerId)
            val household = householdService.removeMember(
                householdId  = householdId,
                ownerId      = ownerId,
                memberUserId = memberUUID,
            )
            val memberUsers = householdService.fetchMemberUsers(household)
            household.toApi(memberUsers)
        }
    }

    @PostMapping("/leave")
    fun leaveHousehold(
        @RequestHeader("Authorization") authorization: String
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)
        runBlocking {
            val householdId = requireHouseholdId(userId)
            householdService.leaveHousehold(householdId = householdId, userId = userId)
        }
        return successResponse()
    }

    private suspend fun requireHouseholdId(userId: UUID): UUID =
        dataStoreClient.getUserById(userId).householdId
            ?: throw IllegalArgumentException("User is not in a household")
}

data class CreateHouseholdRequest(
    @field:NotBlank val name: String,
)

data class RenameHouseholdRequest(
    @field:NotBlank val name: String
)
