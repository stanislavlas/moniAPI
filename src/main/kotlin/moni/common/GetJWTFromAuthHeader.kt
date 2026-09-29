package moni.common

import moni.auth.JwtAuth
import moni.dataStore.IDataStoreClient
import moni.models.internal.User
import java.util.UUID

private fun String.extractJWT(): String {
    if (startsWith("Bearer ")) return substring(7)
    throw IllegalArgumentException("Incorrect authentication header")
}

/** Extract the authenticated userId from a raw Authorization header in a single call. */
fun String.getUserId(jwtAuth: JwtAuth): UUID = jwtAuth.getUserIdFromJWT(this.extractJWT())

/**
 * Convenience helper: extract userId from the Authorization header, then fetch the full
 * [User] object from the data store in one call. Eliminates the repeated boilerplate:
 *
 * ```kotlin
 * val userId = authorization.getUserId(jwtAuth)
 * val user   = dataStoreClient.getUserById(userId)
 * ```
 */
suspend fun String.getUser(jwtAuth: JwtAuth, dataStoreClient: IDataStoreClient): User {
    val userId = getUserId(jwtAuth)
    return dataStoreClient.getUserById(userId)
}
