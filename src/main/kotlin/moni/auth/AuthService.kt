package moni.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import moni.category.CategoryService
import moni.config.AuthException
import moni.config.ForbiddenException
import moni.currency.CurrencyConversionService
import moni.dataStore.EntryRepository
import moni.dataStore.IDataStoreClient
import moni.dataStore.RecurringEntryRepository
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
    private val recurringEntryRepository: RecurringEntryRepository,
) {
    suspend fun getUserWithJwt(email: String, password: String): AuthUserResponse {
        val user = dataStore.getUserByEmail(email.trim().lowercase())
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

    suspend fun createUser(
        currency: Currency,
        email: String,
        name: String,
        password: String,
    ): RegistrationPendingResponse {
        if (!currencyConversionService.isValidCurrency(currency)) {
            throw IllegalArgumentException("Unknown currency code")
        }

        val normEmail = email.trim().lowercase()
        val user = dataStore.getUserByEmail(normEmail)

        if (user != null) {
            if (user.emailVerified) {
                throw IllegalArgumentException("An account with that email address already exists")
            }
            // Unverified user — clean up stale record and allow re-registration.
            // NOTE: There is a potential TOCTOU race here: two concurrent registrations for the
            // same email could both pass the existence check and both attempt to delete + create.
            // DynamoDB does not support compare-and-delete atomically. The worst-case outcome is
            // two user records being written for the same email, which the GSI unique constraint
            // (if configured) would catch. For now this window is accepted as very low probability
            // in a personal-finance app with low concurrent traffic.
            verificationCodeService.deleteAllForUser(user.userId)
            dataStore.deleteUser(user.userId)
        }

        val newUser = User(
            userId = UUID.randomUUID(),
            currency = currency,
            email = normEmail,
            name = name,
            password = passwordEncoder.encode(password),
            emailVerified = false,
        )

        dataStore.putUser(user = newUser)

        // Send verification code to the registered email
        verificationCodeService.generateAndSend(
            userId = newUser.userId,
            type = VerificationType.REGISTRATION,
            email = normEmail,
            name = name,
        )

        return RegistrationPendingResponse(
            userId = newUser.userId,
            message = "Registration successful. Please check your email for a verification code.",
        )
    }

    suspend fun verifyRegistration(code: String): AuthUserResponse {
        val verificationCode = verificationCodeService.validate(code, VerificationType.REGISTRATION)

        val user = try {
            dataStore.getUserById(verificationCode.userId)
        } catch (_: NoSuchElementException) {
            // User deleted between code generation and verification — treat as invalid code
            throw IllegalArgumentException("Invalid verification code")
        }

        // Guard: if already verified, consume the stale code so it can't be reused,
        // then reject. This prevents an attacker who sniffed the OTP from re-issuing
        // tokens after the legitimate user has already verified.
        if (user.emailVerified) {
            verificationCodeService.consume(code)
            throw IllegalArgumentException("Email is already verified")
        }

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
        val normEmail = email.trim().lowercase()
        val user = dataStore.getUserByEmail(normEmail)
            ?: throw IllegalArgumentException("No account found with that email address")

        if (user.emailVerified) {
            throw IllegalArgumentException("Email address is already verified")
        }

        // Use resendForUser which enforces rate-limiting via the MAX_RESEND_ATTEMPTS guard
        verificationCodeService.resendForUser(
            userId = user.userId,
            type = VerificationType.REGISTRATION,
            email = normEmail,
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
            // Use the householdId we already have on the user — avoids a second getUserById
            // call that getHouseholdByUserId would trigger internally.
            val household = householdService.getHouseholdById(householdId)
            if (household != null) {
                if (household.ownerId == userId) {
                    householdService.deleteHousehold(householdId, userId)
                } else {
                    householdService.leaveHousehold(householdId, userId)
                }
            }
        }

        // 2. Delete all entries authored by this user (personal and household).
        entryRepository.deleteByUserId(userId)

        // 3. Delete all recurring entry templates for this user.
        recurringEntryRepository.deleteByUserId(userId)

        // 4. Delete personal categories (household categories survive for other members).
        categoryService.deletePersonalCategories(userId)

        // 5. Revoke all refresh tokens.
        refreshTokenService.revokeAllUserTokens(userId)

        // 6. Delete user record last — everything referencing it has been cleaned up.
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
        // Revoke all existing sessions so stolen refresh tokens can no longer be used
        refreshTokenService.revokeAllUserTokens(userId)
    }

    suspend fun forgotPassword(email: String) {
        val user = dataStore.getUserByEmail(email.trim().lowercase()) ?: return // silently ignore unknown emails
        if (!user.emailVerified) return // silently ignore unverified accounts — no tamper risk
        verificationCodeService.generateAndSend(
            userId = user.userId,
            type = VerificationType.PASSWORD_RESET,
            email = user.email,
            name = user.name,
        )
    }

    suspend fun resetPassword(code: String, newPassword: String) {
        val verificationCode = verificationCodeService.validate(code, VerificationType.PASSWORD_RESET)
        val encodedNew = passwordEncoder.encode(newPassword)
        dataStore.updateUserPassword(verificationCode.userId, encodedNew)
        verificationCodeService.consume(code)
        // Revoke all existing sessions so stolen refresh tokens can no longer be used
        refreshTokenService.revokeAllUserTokens(verificationCode.userId)
    }
}