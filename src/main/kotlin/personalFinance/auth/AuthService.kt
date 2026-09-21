package personalFinance.auth

import kotlinx.coroutines.runBlocking
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import personalFinance.currency.CurrencyConversionService
import personalFinance.dataStore.IDataStoreClient
import personalFinance.models.Currency
import personalFinance.models.api.AuthUserResponse
import personalFinance.models.api.RegistrationPendingResponse
import personalFinance.models.internal.User
import personalFinance.models.internal.VerificationType
import personalFinance.verification.VerificationCodeService
import java.util.UUID

@Service
class AuthService(
    private val jwtAuth: JwtAuth,
    private val dataStore: IDataStoreClient,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
    private val currencyConversionService: CurrencyConversionService,
    private val verificationCodeService: VerificationCodeService,
) {
    fun getUserWithJwt(email: String, password: String): AuthUserResponse {
        val user = runBlocking { dataStore.getUserByEmail(email = email) }
            ?: throw Exception("User with email $email does not exist")

        val isPasswordMatch = verifyPassword(
            rawPassword = password,
            encodedPassword = user.password,
        )

        if (!isPasswordMatch) {
            throw Exception("Incorrect credentials")
        }

        if (!user.emailVerified) {
            throw IllegalStateException("Email address not verified. Please verify your email before signing in.")
        }

        // Generate both access and refresh tokens
        val accessToken = jwtAuth.generateJWT(user.userId)
        val refreshToken = runBlocking { refreshTokenService.generateRefreshToken(user.userId) }

        return AuthUserResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = user.toApi(),
        )
    }

    fun createUser(
        currency: Currency,
        email: String,
        name: String,
        password: String,
    ): RegistrationPendingResponse {
        if (!currencyConversionService.isValidCurrency(currency)) {
            throw IllegalArgumentException("Unknown currency code: $currency")
        }

        val user = runBlocking { dataStore.getUserByEmail(email = email) }

        if (user != null) {
            throw Exception("User with email $email already exists")
        }

        val newUser = User(
            userId = UUID.randomUUID(),
            currency = currency,
            email = email,
            name = name,
            password = passwordEncoder.encode(password),
            emailVerified = false,
        )

        runBlocking { dataStore.putUser(user = newUser) }

        // Send verification code to the registered email
        runBlocking {
            verificationCodeService.generateAndSend(
                userId = newUser.userId,
                type = VerificationType.REGISTRATION,
                email = email,
                name = name,
            )
        }

        return RegistrationPendingResponse(
            userId = newUser.userId,
            message = "Registration successful. Please check your email for a verification code.",
        )
    }

    suspend fun verifyRegistration(code: String): AuthUserResponse {
        val verificationCode = verificationCodeService.validate(code, VerificationType.REGISTRATION)

        val user = dataStore.getUserById(verificationCode.userId)
        val verifiedUser = user.copy(emailVerified = true)
        dataStore.putUser(verifiedUser)

        verificationCodeService.consume(code)

        // Now issue tokens
        val accessToken = jwtAuth.generateJWT(verifiedUser.userId)
        val refreshToken = refreshTokenService.generateRefreshToken(verifiedUser.userId)

        return AuthUserResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            user = verifiedUser.toApi(),
        )
    }

    suspend fun resendVerificationCode(email: String) {
        val user = dataStore.getUserByEmail(email)
            ?: throw IllegalArgumentException("No account found with that email address")

        if (user.emailVerified) {
            throw IllegalArgumentException("Email address is already verified")
        }

        // Find existing pending code for this user by scanning (or just issue a new one)
        // For simplicity, we generate a fresh code — VerificationCodeService handles resend rate-limiting
        // by finding any existing code for this user. Since we don't have a userId->code index,
        // we just generate a new one (the old code will naturally expire).
        verificationCodeService.generateAndSend(
            userId = user.userId,
            type = VerificationType.REGISTRATION,
            email = email,
            name = user.name,
        )
    }

    suspend fun refreshAccessToken(refreshToken: String): Pair<String, String>? {
        val storedToken = refreshTokenService.validateAndRotate(refreshToken) ?: return null

        // Generate new access token
        val newAccessToken = jwtAuth.generateJWT(storedToken.userId)

        // Generate new refresh token (token rotation)
        val newRefreshToken = refreshTokenService.generateRefreshToken(storedToken.userId)

        // Revoke old refresh token
        refreshTokenService.revokeToken(refreshToken)

        return Pair(newAccessToken, newRefreshToken)
    }

    suspend fun logout(refreshToken: String) {
        refreshTokenService.revokeToken(refreshToken)
    }

    suspend fun deleteUserAccount(userId: UUID, password: String) {
        val user = dataStore.getUserById(userId)
            ?: throw Exception("User not found")

        val isPasswordMatch = verifyPassword(
            rawPassword = password,
            encodedPassword = user.password,
        )

        if (!isPasswordMatch) {
            throw Exception("Incorrect password")
        }

        // Revoke all refresh tokens
        refreshTokenService.revokeAllUserTokens(userId)

        // Delete user from DynamoDB
        dataStore.deleteUser(userId)
    }

    private fun verifyPassword(rawPassword: String, encodedPassword: String) =
        passwordEncoder.matches(rawPassword, encodedPassword)

    suspend fun changePassword(userId: UUID, currentPassword: String, newPassword: String) {
        val user = dataStore.getUserById(userId)

        if (!verifyPassword(currentPassword, user.password)) {
            throw IllegalArgumentException("Incorrect current password")
        }

        val encodedNew = passwordEncoder.encode(newPassword)
        dataStore.updateUserPassword(userId, encodedNew)
    }

    suspend fun forgotPassword(email: String) {
        val user = dataStore.getUserByEmail(email) ?: return // silently ignore unknown emails
        verificationCodeService.generateAndSend(
            userId = user.userId,
            type = VerificationType.PASSWORD_RESET,
            email = email,
            name = user.name,
        )
    }

    suspend fun resetPassword(code: String, newPassword: String) {
        val verificationCode = verificationCodeService.validate(code, VerificationType.PASSWORD_RESET)
        val encodedNew = passwordEncoder.encode(newPassword)
        dataStore.updateUserPassword(verificationCode.userId, encodedNew)
        verificationCodeService.consume(code)
    }
}