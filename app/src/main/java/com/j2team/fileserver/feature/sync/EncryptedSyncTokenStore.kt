package com.j2team.fileserver.feature.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedSyncTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted_sync_tokens", Context.MODE_PRIVATE)

    fun put(folderId: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
            updateAAD(folderId.toByteArray())
        }
        val encrypted = cipher.doFinal(token.toByteArray())
        preferences.edit().putString(folderId,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun get(folderId: String): String? = runCatching {
        val encoded = preferences.getString(folderId, null) ?: return null
        val split = encoded.indexOf(':').takeIf { it > 0 } ?: return null
        val iv = Base64.decode(encoded.substring(0, split), Base64.NO_WRAP)
        val encrypted = Base64.decode(encoded.substring(split + 1), Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            updateAAD(folderId.toByteArray())
            String(doFinal(encrypted), Charsets.UTF_8)
        }
    }.getOrNull()

    fun delete(folderId: String) { preferences.edit().remove(folderId).apply() }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "file_server_sync_tokens_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
