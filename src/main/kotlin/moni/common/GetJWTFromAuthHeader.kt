package moni.common

import moni.auth.JwtAuth
import java.util.UUID

fun String?.getJWT(): String {
    if (this != null && this.startsWith("Bearer ")) {
        return this.substring(7)
    }

    throw Exception("Incorrect authentication header")
}

/** Extract the authenticated userId from a raw Authorization header in a single call. */
fun String.getUserId(jwtAuth: JwtAuth): UUID = jwtAuth.getUserIdFromJWT(this.getJWT())
