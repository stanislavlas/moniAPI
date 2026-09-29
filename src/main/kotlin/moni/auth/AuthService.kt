package moni.auth

import kotlinx.coroutines.runBlocking
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import moni.category.CategoryService
import moni.config.AuthException
import moni.config.ForbiddenException
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.IDataStoreClient
import moni.household.HouseholdService
import moni.models.Currency
import moni.models.api.AuthUserResponse
import moni.models.api.RegistrationPendingResponse
import moni.models.internal.User
import moni.models.internal.VerificationType
import moni.verification.VerificationCodeService
import java.util.UUID

@Service
class AuthService(
    private val jwtAuth: JwtAuth,
    private val dataStore: IDataStoreClient,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
    private val currencyConversionService: CurrencyConversionService,
    private val verificationCodeService: VerificationCodeService,
    private val entryRepository: EntryRepository,
    private val categoryService: CategoryService,
    private val householdService: HouseholdService,
) {
    suspend fun getUserWithJwt(email: String, password: String): AuthUserResponse {
        val user = dataStore.getUserByEmail(email = email)
            ?: throw AuthException("Incorrect credentials")

        val isPasswordMatch = verifyPassword(
            rawPassword = password,
            encodedPassword = user.password,
        )

        if (!isPasswordMatch) {
            throw AuthException("Incorrect credentials")
        }

        if (!user.emailVerified) {
            throw ForbiddenException("Email address not verified. Please verify your email before signing in.")
        }

        // Generate both access and refresh tokens
        val accessToken = jwtAuth.generateJWT(user.userId)
        val refreshToken = refreshTokenService.generateRefreshToken(user.userId)

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
            if (user.emailVerified) {
                throw IllegalArgumentException("User with email $email already exists")
            }
            // Unverified user — clean up stale record and allow re-registration.
            // NOTE: There is a potential TOCTOU race here: two concurrent registrations for the
            // same email could both pass the existence check and both attempt to delete + create.
            // DynamoDB does not support compare-and-delete atomically. The worst-case outcome is
            // two user records being written for the same email, which the GSI unique constraint
            // (if configured) would catch. For now this window is accepted as very low probability
            // in a personal-finance app with low concurrent traffic.
            runBlocking {
                verificationCodeService.deleteAllForUser(user.userId)
                dataStore.deleteUser(user.userId)
            }
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

        // 1. Persist the verified user first. If this throws, nothing else runs and the
        //    code remains valid so the user can retry.
        dataStore.putUser(verifiedUser)

        // 2. Consume the code. If this fails after a successful putUser the code stays in
        //    DynamoDB but is now harmless — the user record is already verified and a
        //    second attempt via the same code would succeed idempotently.
        verificationCodeService.consume(code)

        // 3. Issue tokens only after both writes are complete.
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

        // Use resendForUser which enforces rate-limiting via the MAX_RESEND_ATTEMPTS guard
        verificationCodeService.resendForUser(
            userId = user.userId,
            type = VerificationType.REGISTRATION,
            email = email,
            name = user.name,
        )
    }

    suspend fun refreshAccessToken(refreshToken: String): Pair<String, String>? {
        // validateAndRevoke atomically validates and deletes the old token
        val storedToken = refreshTokenService.validateAndRevoke(refreshToken) ?: return null

        // Generate new access token
        val newAccessToken = jwtAuth.generateJWT(storedToken.userId)

        // Generate new refresh token (token rotation)
        val newRefreshToken = refreshTokenService.generateRefreshToken(storedToken.userId)

        return Pair(newAccessToken, newRefreshToken)
    }

    suspend fun logout(refreshToken: String) {
        refreshTokenService.revokeToken(refreshToken)
    }

    suspend fun deleteUserAccount(userId: UUID, password: String) {
        val user = dataStore.getUserById(userId)

        val isPasswordMatch = verifyPassword(
            rawPassword = password,
            encodedPassword = user.password,
        )

        if (!isPasswordMatch) {
            throw AuthException("Incorrect password")
        }

        // 1. Leave / clean up household membership before deleting the user record so that
        //    the household's members list and the owner pointer remain consistent.
        val householdId = user.householdId
        if (householdId != null) {
            val household = householdService.getHouseholdByUserId(userId)
            if (household != null) {
                if (household.ownerId == userId) {
                    // Owner is deleting their account — delete the whole household so
                    // remaining members aren't left orphaned.
                    householdService.deleteHousehold(householdId, userId)
                } else {
                    householdService.leaveHousehold(householdId, userId)
                }
            }
        }

        // 2. Delete personal entries (household entries authored by this user are left intact
        //    since other household members still need them).
        entryRepository.deleteByUserId(userId)

        // 3. Delete personal categories (household categories survive for other members).
        categoryService.deletePersonalCategories(userId)

        // 4. Revoke all refresh tokens.
        refreshTokenService.revokeAllUserTokens(userId)

        // 5. Delete user record last — everything referencing it has been cleaned up.
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