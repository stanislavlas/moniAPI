package moni.common

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.stereotype.Component
import moni.dataStore.IDataStoreClient
import java.util.UUID

@Component
class UserResolver(private val dataStoreClient: IDataStoreClient) {

    /**
     * Fetches the current display name for each unique userId concurrently.
     * Users that no longer exist (e.g. deleted accounts) are silently omitted
     * from the result; callers should fall back to the stored snapshot name.
     */
    suspend fun resolveNames(userIds: List<UUID>): Map<UUID, String> = coroutineScope {
        userIds.map { id ->
            async {
                try { id to dataStoreClient.getUserById(id).name }
                catch (_: Exception) { id to null }
            }
        }.awaitAll().mapNotNull { (id, name) -> name?.let { id to it } }.toMap()
    }
}
