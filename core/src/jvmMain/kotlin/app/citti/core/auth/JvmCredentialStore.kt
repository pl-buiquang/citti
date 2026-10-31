package app.citti.core.auth

import java.io.File

/**
 * FOR TESTS AND THE JVM BENCHMARK HARNESS ONLY: the credentials are NOT encrypted. With a [file] they
 * are written as plain JSON; without, they live in memory. Never ship this to users.
 */
actual class CredentialStoreFactory(
    private val file: File? = null,
) {
    actual fun create(): CredentialStore = PlainCredentialStore(file)
}

internal class PlainCredentialStore(
    private val file: File?,
) : CredentialStore {
    private var memory: Credentials? = null

    override suspend fun load(): Credentials? =
        if (file == null) {
            memory
        } else {
            file.takeIf { it.exists() }?.let { credentialsFromBytes(it.readBytes()) }
        }

    override suspend fun save(c: Credentials) {
        if (file == null) memory = c else file.writeBytes(c.toBytes())
    }

    override suspend fun clear() {
        memory = null
        file?.delete()
    }
}
