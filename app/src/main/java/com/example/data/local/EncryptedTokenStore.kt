package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts Azure DevOps Personal Access Tokens (PATs) at rest using AES-256-GCM.
 *
 * Uses the hardware-backed `AndroidKeyStore` when available on device, with an automatic
 * AES-256-GCM software key fallback for local JVM / Robolectric unit test environments where
 * the `AndroidKeyStore` provider is not registered.
 *
 * Also supports transparent migration of legacy plaintext tokens: if a stored value does not
 * carry the [ENCRYPTED_PREFIX] header, [decryptToken] returns it as-is so callers can re-save
 * it in encrypted form without forcing users to re-authenticate.
 */
class EncryptedTokenStore(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val keyPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    fun isEncrypted(storedValue: String?): Boolean {
        return !storedValue.isNullOrBlank() && storedValue.startsWith(ENCRYPTED_PREFIX)
    }

    /**
     * Encrypts [plainToken] using AES-256-GCM and returns a prefixed Base64 payload:
     * `enc:v1:<Base64(IV[12] + Ciphertext + AuthTag)>`.
     */
    fun encryptToken(plainToken: String): String {
        val trimmed = plainToken.trim()
        if (trimmed.isEmpty()) return ""
        if (isEncrypted(trimmed)) return trimmed

        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)

            ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Software key fallback if hardware Keystore cipher initialization fails
            encryptWithFallbackKey(trimmed)
        }
    }

    /**
     * Decrypts a token previously encrypted by [encryptToken].
     * If [storedValue] does not start with [ENCRYPTED_PREFIX], treats it as a legacy plaintext
     * token and returns it directly for seamless in-place migration.
     */
    fun decryptToken(storedValue: String): String {
        val trimmed = storedValue.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith(ENCRYPTED_PREFIX)) {
            return trimmed
        }

        val payloadBase64 = trimmed.removePrefix(ENCRYPTED_PREFIX)
        return try {
            val combined = Base64.decode(payloadBase64, Base64.NO_WRAP)
            if (combined.size < 2) return ""
            val ivSize = combined[0].toInt() and 0xFF
            if (ivSize <= 0 || combined.size <= 1 + ivSize) return ""

            val iv = combined.copyOfRange(1, 1 + ivSize)
            val cipherBytes = combined.copyOfRange(1 + ivSize, combined.size)

            try {
                val secretKey = getOrCreateSecretKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            } catch (_: Exception) {
                val fallbackKey = getOrCreateSoftwareFallbackKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, fallbackKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun encryptWithFallbackKey(plainToken: String): String {
        val fallbackKey = getOrCreateSoftwareFallbackKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, fallbackKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainToken.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(1 + iv.size + cipherBytes.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)

        return ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }

        val keystoreKey = runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val existingKey = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existingKey != null) {
                existingKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        }.getOrNull()

        val resolved = keystoreKey ?: getOrCreateSoftwareFallbackKey()
        cachedSecretKey = resolved
        return resolved
    }

    @Synchronized
    private fun getOrCreateSoftwareFallbackKey(): SecretKey {
        val existingBase64 = keyPrefs.getString(KEY_FALLBACK_SECRET, null)
        if (!existingBase64.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(existingBase64, Base64.NO_WRAP) }.getOrNull()
            if (decoded != null && decoded.size == AES_KEY_SIZE_BITS / 8) {
                return SecretKeySpec(decoded, "AES")
            }
        }
        val keyBytes = ByteArray(AES_KEY_SIZE_BITS / 8)
        SecureRandom().nextBytes(keyBytes)
        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        keyPrefs.edit().putString(KEY_FALLBACK_SECRET, encoded).commit()
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        const val ENCRYPTED_PREFIX = "enc:v1:"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "azure_boards_pat_master_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PREFS_FILE_NAME = "azure_boards_keystore_fallback_prefs"
        private const val KEY_FALLBACK_SECRET = "pref_fallback_aes_256_key"
    }
}
