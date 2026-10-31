package app.citti.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the app needs to talk to the server. [appPassword] is a secret: it never reaches a log or a message. */
data class Credentials(
    val serverUrl: String,
    val user: String,
    val appPassword: String,
) {
    override fun toString(): String = "Credentials(serverUrl=$serverUrl, user=$user, appPassword=***)"
}

/** The one place the app password lives at rest. */
interface CredentialStore {
    suspend fun load(): Credentials?

    suspend fun save(c: Credentials)

    suspend fun clear()
}

/**
 * Creates the platform [CredentialStore]. Android: Keystore-encrypted (the actual takes a Context in
 * its constructor). JVM: in-memory/file, for tests only. iOS can use the Keychain later.
 */
expect class CredentialStoreFactory {
    fun create(): CredentialStore
}

@Serializable
private class StoredCredentials(
    val serverUrl: String,
    val user: String,
    val appPassword: String,
)

private val storedJson = Json { ignoreUnknownKeys = true }

internal fun Credentials.toBytes(): ByteArray =
    storedJson.encodeToString(StoredCredentials.serializer(), StoredCredentials(serverUrl, user, appPassword)).encodeToByteArray()

internal fun credentialsFromBytes(bytes: ByteArray): Credentials {
    val s = storedJson.decodeFromString(StoredCredentials.serializer(), bytes.decodeToString())
    return Credentials(s.serverUrl, s.user, s.appPassword)
}
