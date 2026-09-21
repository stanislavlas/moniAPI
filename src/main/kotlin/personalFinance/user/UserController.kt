package personalFinance.user

import org.springframework.web.bind.annotation.*
import personalFinance.auth.JwtAuth
import personalFinance.common.getUserId
import personalFinance.models.api.User

@RestController
@RequestMapping("/api/user")
class UserController(
    private val jwtAuth: JwtAuth,
    private val userService: UserService,
) {

    @GetMapping("")
    fun getUser(@RequestHeader("Authorization") authHeader: String): User {
        return userService.getUser(authHeader.getUserId(jwtAuth)).toApi()
    }

    @PatchMapping("")
    fun updateUser(
        @RequestHeader("Authorization") authHeader: String,
        @RequestBody request: UpdateUserRequest,
    ): User {
        return userService.updateUser(
            userId                  = authHeader.getUserId(jwtAuth),
            name                    = request.name,
            currency                = request.currency,
            email                   = request.email,
            currentPassword         = request.currentPassword,
            notificationsEnabled    = request.notificationsEnabled,
            notificationFrequency   = request.notificationFrequency,
            notificationCustomDays  = request.notificationCustomDays,
            notificationTime        = request.notificationTime,
        ).toApi()
    }
}

data class UpdateUserRequest(
    val name: String? = null,
    val currency: String? = null,
    val email: String? = null,
    val currentPassword: String? = null,
    val notificationsEnabled: Boolean? = null,
    val notificationFrequency: String? = null,
    val notificationCustomDays: Int? = null,
    val notificationTime: String? = null,
)
