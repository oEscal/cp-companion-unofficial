package pt.cpcompanion.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small Android Keystore-backed value store. Values are encrypted; keys are non-sensitive labels. */
class SecureStorage(context: Context) {
    private val prefs = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)
    private val alias = "cp_companion_store_v1"
    private val secretKey: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::loadOrCreateKey)

    fun contains(key: String): Boolean = prefs.contains(key)

    /** Encrypt immediately but allow SharedPreferences to flush the result off the caller thread. */
    fun put(key: String, value: String) {
        prefs.edit().putString(key, encrypt(value)).apply()
    }

    /** Use only for ownership/session state that must be durable before the next platform call. */
    fun putDurable(key: String, value: String) {
        check(prefs.edit().putString(key, encrypt(value)).commit()) {
            "Could not persist encrypted app state"
        }
    }

    fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrElse {
            // A value can become unreadable after a device restore, key invalidation, or
            // interrupted write. Remove only the damaged entry so the app can recover
            // without repeatedly failing on every startup.
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun remove(key: String) {
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
    }

    fun removeDurable(key: String) {
        if (prefs.contains(key)) {
            check(prefs.edit().remove(key).commit()) { "Could not remove encrypted app state" }
        }
    }

    fun encryptPayload(value: String): String = encrypt(value)

    fun decryptPayload(encoded: String): String = decrypt(encoded)

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val decoded = Base64.decode(encoded, Base64.NO_WRAP)
        require(decoded.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) {
            "Invalid encrypted value size"
        }
        val packed = ByteBuffer.wrap(decoded)
        val ivSize = packed.int
        require(ivSize in 12..32)
        require(packed.remaining() >= ivSize + MIN_GCM_TAG_BYTES) {
            "Invalid encrypted value envelope"
        }
        val iv = ByteArray(ivSize).also { packed.get(it) }
        val encrypted = ByteArray(packed.remaining()).also { packed.get(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MIN_GCM_TAG_BYTES = 16
        const val MIN_ENVELOPE_BYTES = 4 + 12 + MIN_GCM_TAG_BYTES
        const val MAX_ENVELOPE_BYTES = 4 * 1024 * 1024
    }
}
