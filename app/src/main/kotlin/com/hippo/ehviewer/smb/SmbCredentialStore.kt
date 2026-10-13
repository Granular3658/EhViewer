package com.hippo.ehviewer.smb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hippo.ehviewer.Settings
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

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

    /**
     * The credentials stored for a host, whichever share they were entered for.
     *
     * The store is keyed by host, port *and* share, but a browser asks about a
     * host before it knows a share name -- listing the shares is how it finds
     * one. Any credential for that host will do: a server authenticates the
     * session, not the share.
     */
    @Synchronized
    fun find(host: String, port: Int): SmbCredentials? = runCatching {
        val prefix = "smb://$host:$port/"
        val json = JSONObject(Settings.smbCredentials.value)
        val key = json.keys().asSequence().firstOrNull { it.startsWith(prefix) } ?: return null
        decrypt(json.getString(key))
    }.getOrNull()

    @Synchronized
    fun put(location: SmbLocation, credentials: SmbCredentials) {
        val json = JSONObject(Settings.smbCredentials.value)
        json.put(location.credentialKey, encrypt(credentials))
        Settings.smbCredentials.value = json.toString()
    }

    @Synchronized
    fun remove(location: SmbLocation) {
        val json = JSONObject(Settings.smbCredentials.value)
        json.remove(location.credentialKey)
        Settings.smbCredentials.value = json.toString()
    }
}
