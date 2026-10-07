package com.hippo.ehviewer.smb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.jni.smbInvalidate
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

private const val TAG = "SmbCredentialStore"

/** Credentials are encrypted per SMB server/share before entering DataStore. */
data class SmbCredentials(
    val user: String,
    val password: String,
    val domain: String,
)

object SmbCredentialStore {
    private const val KEY_ALIAS = "ehviewer_smb_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(credentials: SmbCredentials): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject()
            .put("user", credentials.user)
            .put("password", credentials.password)
            .put("domain", credentials.domain)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): SmbCredentials {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_SIZE) { "Invalid SMB credential blob" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, packed.copyOf(IV_SIZE)),
        )
        val json = JSONObject(String(cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)), StandardCharsets.UTF_8))
        return SmbCredentials(
            user = json.optString("user"),
            password = json.optString("password"),
            domain = json.optString("domain"),
        )
    }

    @Synchronized
    fun get(location: SmbLocation): SmbCredentials? = runCatching {
        val value = JSONObject(Settings.smbCredentials.value).optString(location.credentialKey, null) ?: return null
        decrypt(value)
    }.getOrNull()

    @Synchronized
    fun put(location: SmbLocation, credentials: SmbCredentials) {
        val json = JSONObject(Settings.smbCredentials.value)
        json.put(location.credentialKey, encrypt(credentials))
        Settings.smbCredentials.value = json.toString()
        // Drop cached SMB sessions so the next call re-authenticates with the
        // new credentials instead of reusing a session bound to the old auth.
        runCatching { smbInvalidate() }.onFailure { Log.e(TAG, "Failed to invalidate SMB sessions", it) }
    }

    @Synchronized
    fun remove(location: SmbLocation) {
        val json = JSONObject(Settings.smbCredentials.value)
        json.remove(location.credentialKey)
        Settings.smbCredentials.value = json.toString()
        // Credentials are gone, so any cached session for this target must be
        // dropped before the next (now anonymous) call is attempted.
        runCatching { smbInvalidate() }.onFailure { Log.e(TAG, "Failed to invalidate SMB sessions", it) }
    }
}
