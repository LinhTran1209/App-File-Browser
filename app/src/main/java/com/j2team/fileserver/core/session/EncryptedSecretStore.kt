package com.j2team.fileserver.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretStore {
    /** Consumes [credential.password] and clears it before returning. */
    fun put(profileId: String, credential: StoredCredential)
    fun get(profileId: String): StoredCredential?
    fun delete(profileId: String)
}

class EncryptedSecretStore(context: Context) : SecretStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun put(profileId: String, credential: StoredCredential) {
        val plaintext = "${credential.username}\u0000${credential.password.concatToString()}".toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, secretKey())
                updateAAD(profileId.toByteArray(StandardCharsets.UTF_8))
            }
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            preferences.edit()
                .putString(profileId, "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}")
                .apply()
        } finally {
            plaintext.fill(0)
            credential.password.fill('\u0000')
        }
    }

    override fun get(profileId: String): StoredCredential? {
        val encoded = preferences.getString(profileId, null) ?: return null
        val separator = encoded.indexOf(':')
        if (separator <= 0 || separator == encoded.lastIndex) return null
        return runCatching {
            val iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP)
            val plaintext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(profileId.toByteArray(StandardCharsets.UTF_8))
                doFinal(ciphertext)
            }
            try {
                val values = plaintext.toString(StandardCharsets.UTF_8).split('\u0000', limit = 2)
                if (values.size != 2) null else StoredCredential(values[0], values[1].toCharArray())
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    override fun delete(profileId: String) {
        preferences.edit().remove(profileId).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "file_server_credentials_v1"
        const val PREFERENCES_NAME = "encrypted_server_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TAG_BITS = 128
    }
}
