package dev.tvlauncher.muse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.tvlauncher.muse.pairing.GadgetIdentity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Muse secrets at rest: the SDK token and, once paired, the device tokens. Values are
 * AES-256-GCM encrypted under a non-exportable Android Keystore key and kept in
 * app-private prefs, so a copy of the app's files alone can't reveal them.
 * Never log what this returns.
 */
class MuseSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("muse_secrets", Context.MODE_PRIVATE)

    var sdkToken: String?
        get() = read(KEY_SDK_TOKEN)
        set(value) {
            write(KEY_SDK_TOKEN, value)
        }

    /** Cheap check (no decryption) for whether a pairing is stored. */
    /** Meta Model API key for realtime speech-to-text (Muse Voice Transcribe); from scripts/meta-asr-key.sh. */
    var metaApiKey: String?
        get() = read(KEY_META_API_KEY)
        set(value) {
            write(KEY_META_API_KEY, value)
        }

    val isPaired: Boolean get() = prefs.contains(KEY_PAIRING)

    val pairing: MusePairing? get() = read(KEY_PAIRING)?.let(MusePairing::fromJson)

    /** Saves (or with null, forgets) the pairing; returns once it's on disk. */
    fun savePairing(pairing: MusePairing?): Boolean = write(KEY_PAIRING, pairing?.toJson(), sync = true)

    /**
     * The gadget identity, made on first use. Not a secret: it's stored in plain text so
     * it survives losing the Keystore key, and it's kept when unpairing (as upstream does).
     */
    fun identity(): GadgetIdentity {
        prefs.getString(KEY_IDENTITY_MAC, null)?.takeIf(GadgetIdentity::isValidMac)?.let { return GadgetIdentity(it) }
        return GadgetIdentity.generate().also { prefs.edit().putString(KEY_IDENTITY_MAC, it.mac).commit() }
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null // key lost (e.g. after a restore) or data damaged: treat as absent
        }
    }

    private fun write(name: String, value: String?, sync: Boolean = false): Boolean {
        val edit = prefs.edit()
        if (value == null) {
            edit.remove(name)
        } else {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            edit.putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
        }
        if (sync) return edit.commit()
        edit.apply()
        return true
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "muse-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_SDK_TOKEN = "sdk_token"
        const val KEY_META_API_KEY = "meta_api_key"
        const val KEY_PAIRING = "pairing"
        const val KEY_IDENTITY_MAC = "identity_mac"
    }
}
