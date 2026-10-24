package com.infinity.comicreader.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets (share passwords) so that only this app on this device can open them. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    /** The secret [sealed] holds; throws when it was sealed by another key or altered. */
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM under [key]: a fresh 12-byte nonce, then the ciphertext with its tag. */
class AesGcmBox(private val key: () -> SecretKey) : SecretBox {
    override fun seal(plain: ByteArray): ByteArray = seal(plain, null)

    override fun open(sealed: ByteArray): ByteArray = open(sealed, null)

    /** Seals [plain] bound to [bound], which must be the same to open it. */
    fun seal(plain: ByteArray, bound: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Unexpected nonce size ${iv.size}" }
        bound?.let(cipher::updateAAD)
        return iv + cipher.doFinal(plain)
    }

    /** The secret [sealed] holds; throws when it was sealed by another key, bound to other than [bound], or altered. */
    fun open(sealed: ByteArray, bound: ByteArray?): ByteArray {
        require(sealed.size > IV_BYTES) { "Too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        bound?.let(cipher::updateAAD)
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** The app's AES key in the Android Keystore, made on first use; it never leaves the keystore. */
object KeystoreKey {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "share_credentials"

    @Synchronized
    fun get(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
