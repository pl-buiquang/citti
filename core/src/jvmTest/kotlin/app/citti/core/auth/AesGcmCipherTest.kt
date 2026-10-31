package app.citti.core.auth

import kotlinx.coroutines.test.runTest
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AesGcmCipherTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmCipher { key }

    @Test
    fun roundTrip() {
        val plain = "s3cret-app-password".encodeToByteArray()
        assertContentEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun ciphertextHidesPlaintextAndIvIsUnique() {
        val plain = "s3cret-app-password".encodeToByteArray()
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        assertNotEquals(a.toList(), b.toList())
        assertNotEquals(a.copyOfRange(0, 12).toList(), b.copyOfRange(0, 12).toList())
        assertFalse(a.decodeToString().contains("s3cret"))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val data = cipher.encrypt("hello".encodeToByteArray())
        data[data.size - 1] = (data[data.size - 1].toInt() xor 1).toByte()
        assertFailsWith<CredentialDecryptException> { cipher.decrypt(data) }
        val ivTampered = cipher.encrypt("hello".encodeToByteArray()).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<CredentialDecryptException> { cipher.decrypt(ivTampered) }
    }

    @Test
    fun wrongKeyAndTruncationAreRejected() {
        val data = cipher.encrypt("hello".encodeToByteArray())
        assertFailsWith<CredentialDecryptException> { AesGcmCipher { newKey() }.decrypt(data) }
        assertFailsWith<CredentialDecryptException> { cipher.decrypt(ByteArray(5)) }
    }

    @Test
    fun credentialsSerializeRoundTrip() {
        val c = Credentials("https://host/cloud", "alice", "pw")
        assertEquals(c, credentialsFromBytes(c.toBytes()))
    }

    @Test
    fun jvmStoreSavesLoadsAndClears() =
        runTest {
            val file = File.createTempFile("citti", ".json").also { it.delete() }
            try {
                val c = Credentials("https://host", "alice", "pw")
                for (store in listOf(CredentialStoreFactory().create(), CredentialStoreFactory(file).create())) {
                    assertNull(store.load())
                    store.save(c)
                    assertEquals(c, store.load())
                    store.clear()
                    assertNull(store.load())
                }
            } finally {
                file.delete()
            }
        }
}
