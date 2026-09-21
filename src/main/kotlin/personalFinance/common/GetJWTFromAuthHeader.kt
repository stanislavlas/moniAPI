package personalFinance.common

import personalFinance.auth.JwtAuth
import java.util.UUID

fun String?.getJWT(): String {
    if (this != null && this.startsWith("Bearer ")) {
        return this.substring(7)
    }

    throw Exception("Incorrect authentication header")
}

// Alias for compatibility
fun GetJWTFromAuthHeader(authHeader: String): String = authHeader.getJWT()

/** Extract the authenticated userId from a raw Authorization header in a single call. */
fun String.getUserId(jwtAuth: JwtAuth): UUID = jwtAuth.getUserIdFromJWT(this.getJWT())
