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
import moni.models.internal.Household
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
    ): ResponseEntity<Household> {
        val userId = authorization.getUserId(jwtAuth)
        val household = runBlocking { householdService.getHouseholdByUserId(userId) }
        return if (household != null) ResponseEntity.ok(household)
        else ResponseEntity.noContent().build()
    }

    @PostMapping
    fun createHousehold(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: CreateHouseholdRequest
    ): Household {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            householdService.createHousehold(userId = userId, name = request.name)
        }
    }

    @PutMapping
    fun renameHousehold(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: RenameHouseholdRequest
    ): Household {
        val userId = authorization.getUserId(jwtAuth)
        return runBlocking {
            val householdId = requireHouseholdId(userId)
            householdService.renameHousehold(householdId = householdId, userId = userId, newName = request.name)
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
    ): Household {
        val ownerId = authorization.getUserId(jwtAuth)
        return runBlocking {
            val householdId = requireHouseholdId(ownerId)
            householdService.removeMember(
                householdId  = householdId,
                ownerId      = ownerId,
                memberUserId = UUID.fromString(memberId),
            )
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
            ?: throw IllegalStateException("User is not in a household")
}

data class CreateHouseholdRequest(
    @field:NotBlank val name: String,
)

data class RenameHouseholdRequest(
    @field:NotBlank val name: String
)
