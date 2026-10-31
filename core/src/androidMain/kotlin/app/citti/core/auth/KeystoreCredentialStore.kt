package app.citti.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android factory. Pass the application Context; no global is kept. */
actual class CredentialStoreFactory(
    private val context: Context,
) {
    actual fun create(): CredentialStore = KeystoreCredentialStore(context.applicationContext)
}

private const val KEY_ALIAS = "citti.credentials"
private const val PREFS_NAME = "citti_credentials"
private const val PREF_KEY = "credentials"

/**
 * Credentials encrypted with an Android Keystore AES-GCM key, kept in a private SharedPreferences file.
 * A blob that cannot be decrypted (key lost after a restore, tampering) loads as null and is dropped.
 */
internal class KeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = AesGcmCipher(::keystoreKey)

    override suspend fun load(): Credentials? {
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            credentialsFromBytes(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)))
        } catch (e: CredentialDecryptException) {
            prefs.edit().remove(PREF_KEY).apply()
            null
        } catch (e: IllegalArgumentException) {
            prefs.edit().remove(PREF_KEY).apply()
            null
        }
    }

    override suspend fun save(c: Credentials) {
        val blob = Base64.encodeToString(cipher.encrypt(c.toBytes()), Base64.NO_WRAP)
        prefs.edit().putString(PREF_KEY, blob).commit()
    }

    override suspend fun clear() {
        prefs.edit().remove(PREF_KEY).commit()
    }

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
