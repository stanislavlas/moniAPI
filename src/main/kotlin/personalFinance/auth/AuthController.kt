package personalFinance.auth

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import personalFinance.common.getUserId
import personalFinance.models.Currency
import personalFinance.models.api.AuthUserResponse
import personalFinance.models.api.RegistrationPendingResponse

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val authenticationManager: AuthenticationManager,
    private val jwtAuth: JwtAuth,
) {
    @PostMapping("/login")
    fun loginAndGetJwt(@RequestBody authRequest: AuthRequest): AuthUserResponse {
        return authService.getUserWithJwt(
            email = authRequest.email,
            password = authRequest.password,
        )
    }

    @PostMapping("/create")
    fun createAndGetJwt(@RequestBody createRequest: CreateRequest): RegistrationPendingResponse {
        return authService.createUser(
            currency = createRequest.currency,
            email = createRequest.email,
            name = createRequest.name,
            password = createRequest.password,
        )
    }

    @PostMapping("/verify")
    fun verifyRegistration(@RequestBody request: VerifyRegistrationRequest): AuthUserResponse {
        return runBlocking { authService.verifyRegistration(request.code) }
    }

    @PostMapping("/resend")
    fun resendVerification(@RequestBody request: ResendVerificationRequest): Map<String, Boolean> {
        runBlocking { authService.resendVerificationCode(request.email) }
        return mapOf("success" to true)
    }

    @PostMapping("/refresh")
    fun refreshToken(@RequestBody refreshRequest: RefreshRequest): RefreshResponse {
        val tokens = runBlocking {
            authService.refreshAccessToken(refreshRequest.refreshToken)
        } ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token")

        return RefreshResponse(
            accessToken = tokens.first,
            refreshToken = tokens.second
        )
    }

    @PostMapping("/logout")
    fun logout(@RequestBody logoutRequest: LogoutRequest): Map<String, Boolean> {
        runBlocking {
            authService.logout(logoutRequest.refreshToken)
        }
        return mapOf("success" to true)
    }

    @DeleteMapping("/account")
    fun deleteAccount(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody deleteRequest: DeleteAccountRequest
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)

        runBlocking {
            authService.deleteUserAccount(userId, deleteRequest.password)
        }

        return mapOf("success" to true)
    }

    @PutMapping("/password")
    fun changePassword(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody request: ChangePasswordRequest,
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)

        runBlocking {
            authService.changePassword(userId, request.currentPassword, request.newPassword)
        }

        return mapOf("success" to true)
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@RequestBody request: ForgotPasswordRequest): Map<String, Boolean> {
        runBlocking { authService.forgotPassword(request.email) }
        return mapOf("success" to true)
    }

    @PostMapping("/reset-password")
    fun resetPassword(@RequestBody request: ResetPasswordRequest): Map<String, Boolean> {
        runBlocking { authService.resetPassword(request.code, request.newPassword) }
        return mapOf("success" to true)
    }
}

data class AuthRequest(
    val email: String,
    val password: String,
)

data class CreateRequest(
    val currency: Currency,
    val email: String,
    val name: String,
    val password: String,
)

data class VerifyRegistrationRequest(
    val code: String,
)

data class ResendVerificationRequest(
    val email: String,
)

data class RefreshRequest(
    val refreshToken: String,
)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)

data class LogoutRequest(
    val refreshToken: String,
)

data class DeleteAccountRequest(
    val password: String,
)

data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

data class ForgotPasswordRequest(
    val email: String,
)

data class ResetPasswordRequest(
    val code: String,
    val newPassword: String,
)