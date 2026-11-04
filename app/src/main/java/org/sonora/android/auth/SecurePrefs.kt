package org.sonora.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Strings encrypted at rest with an AES-GCM key that lives in the Android Keystore and never leaves
 * the device. Meant for credentials: the YouTube Music session grants full access to the account.
 *
 * The file is excluded from backups (res/xml/backup_rules.xml). If it comes back anyway without its
 * key (a device transfer), a value that cannot be decrypted is dropped: the listener signs in again,
 * nothing crashes. A Keystore that cannot be used at all, as on a few OEM builds, falls back to the
 * app's private storage rather than failing.
 */
internal class SecurePrefs(context: Context, name: String) {
    private companion object {
        const val TAG = "SecurePrefs"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lfly_credentials_v1"
        const val ENCRYPTED = "v1:"
        const val PLAIN = "plain:"
        const val TAG_BITS = 128
    }

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun getString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        val value = when {
            raw.startsWith(ENCRYPTED) -> runCatching { decrypt(raw.removePrefix(ENCRYPTED)) }
                .onFailure { Log.w(TAG, "A saved credential can no longer be read; it is dropped") }
                .getOrNull()
            raw.startsWith(PLAIN) -> raw.removePrefix(PLAIN)
            else -> null
        }
        if (value == null) prefs.edit().remove(key).apply()
        return value
    }

    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val stored = runCatching { ENCRYPTED + encrypt(value) }
            .onFailure { Log.w(TAG, "Keystore unavailable; keeping the credential in private storage") }
            .getOrElse { PLAIN + value }
        prefs.edit().putString(key, stored).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val iv = Base64.decode(payload.substringBefore(':'), Base64.NO_WRAP)
        val sealed = Base64.decode(payload.substringAfter(':'), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }
}
