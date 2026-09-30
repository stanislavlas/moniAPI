package moni.auth

import kotlinx.coroutines.runBlocking
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import moni.common.getUserId
import moni.common.successResponse
import moni.config.AuthException
import moni.models.Currency
import moni.models.api.AuthUserResponse
import moni.models.api.RegistrationPendingResponse

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val jwtAuth: JwtAuth,
) {
    @PostMapping("/login")
    fun loginAndGetJwt(@Valid @RequestBody authRequest: AuthRequest): AuthUserResponse {
        return runBlocking {
            authService.getUserWithJwt(
                email = authRequest.email,
                password = authRequest.password,
            )
        }
    }

    @PostMapping("/create")
    fun createAndGetJwt(@Valid @RequestBody createRequest: CreateRequest): RegistrationPendingResponse {
        return runBlocking {
            authService.createUser(
                currency = createRequest.currency,
                email = createRequest.email,
                name = createRequest.name,
                password = createRequest.password,
            )
        }
    }

    @PostMapping("/verify")
    fun verifyRegistration(@Valid @RequestBody request: VerifyRegistrationRequest): AuthUserResponse {
        return runBlocking { authService.verifyRegistration(request.code) }
    }

    @PostMapping("/resend")
    fun resendVerification(@Valid @RequestBody request: ResendVerificationRequest): Map<String, Boolean> {
        runBlocking { authService.resendVerificationCode(request.email) }
        return successResponse()
    }

    @PostMapping("/refresh")
    fun refreshToken(@Valid @RequestBody refreshRequest: RefreshRequest): RefreshResponse {
        val tokens = runBlocking {
            authService.refreshAccessToken(refreshRequest.refreshToken)
        } ?: throw AuthException("Invalid or expired refresh token")

        return RefreshResponse(
            accessToken = tokens.first,
            refreshToken = tokens.second
        )
    }

    @PostMapping("/logout")
    fun logout(@Valid @RequestBody logoutRequest: LogoutRequest): Map<String, Boolean> {
        runBlocking {
            authService.logout(logoutRequest.refreshToken)
        }
        return successResponse()
    }

    @DeleteMapping("/account")
    fun deleteAccount(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody deleteRequest: DeleteAccountRequest
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)

        runBlocking {
            authService.deleteUserAccount(userId, deleteRequest.password)
        }

        return successResponse()
    }

    @PutMapping("/password")
    fun changePassword(
        @RequestHeader("Authorization") authorization: String,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): Map<String, Boolean> {
        val userId = authorization.getUserId(jwtAuth)

        runBlocking {
            authService.changePassword(userId, request.currentPassword, request.newPassword)
        }

        return successResponse()
    }

    @PostMapping("/forgot-password")
    fun forgotPassword(@Valid @RequestBody request: ForgotPasswordRequest): Map<String, Boolean> {
        runBlocking { authService.forgotPassword(request.email) }
        return successResponse()
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): Map<String, Boolean> {
        runBlocking { authService.resetPassword(request.code, request.newPassword) }
        return successResponse()
    }
}

data class AuthRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class CreateRequest(
    val currency: Currency,
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val name: String,
    @field:NotBlank @field:Size(min = 8, message = "Password must be at least 8 characters") val password: String,
)

data class VerifyRegistrationRequest(
    @field:NotBlank val code: String,
)

data class ResendVerificationRequest(
    @field:Email @field:NotBlank val email: String,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)

data class LogoutRequest(
    @field:NotBlank val refreshToken: String,
)

data class DeleteAccountRequest(
    @field:NotBlank val password: String,
)

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank @field:Size(min = 8, message = "Password must be at least 8 characters") val newPassword: String,
)

data class ForgotPasswordRequest(
    @field:Email @field:NotBlank val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val code: String,
    @field:NotBlank @field:Size(min = 8, message = "Password must be at least 8 characters") val newPassword: String,
)