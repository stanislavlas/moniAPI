package moni.common

import moni.auth.JwtAuth
import java.util.UUID

private fun String.extractJWT(): String {
    if (startsWith("Bearer ")) return substring(7)
    throw IllegalArgumentException("Incorrect authentication header")
}

/** Extract the authenticated userId from a raw Authorization header in a single call. */
fun String.getUserId(jwtAuth: JwtAuth): UUID = jwtAuth.getUserIdFromJWT(this.extractJWT())
