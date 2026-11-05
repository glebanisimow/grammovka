package app.grammovka.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** File name is referenced by res/xml backup rules: this store is excluded from backups. */
private val Context.secretsDataStore: DataStore<Preferences> by preferencesDataStore(name = "secrets")

enum class Secret(val prefKey: String) {
    ANTHROPIC_KEY("anthropic_key"),
    USDA_KEY("usda_key"),
}

/**
 * API keys encrypted with AES-256-GCM. The key lives in Android Keystore and never leaves it;
 * the ciphertext (IV ‖ ciphertext+tag, Base64) lives in the "secrets" DataStore.
 * EncryptedSharedPreferences / security-crypto are deprecated and not used.
 *
 * Docs: https://developer.android.com/privacy-and-security/keystore
 */
class SecretStore(private val context: Context) {

    fun isSet(secret: Secret): Flow<Boolean> =
        context.secretsDataStore.data.map { it[stringPreferencesKey(secret.prefKey)] != null }

    suspend fun get(secret: Secret): String? {
        val blob = context.secretsDataStore.data.first()[stringPreferencesKey(secret.prefKey)] ?: return null
        return runCatching { decrypt(blob) }.getOrElse {
            // Keystore key is gone (e.g. app data restored on another device): the value is unrecoverable.
            clear(secret)
            null
        }
    }

    suspend fun put(secret: Secret, value: String) {
        val blob = encrypt(value.trim())
        context.secretsDataStore.edit { it[stringPreferencesKey(secret.prefKey)] = blob }
    }

    suspend fun clear(secret: Secret) {
        context.secretsDataStore.edit { it.remove(stringPreferencesKey(secret.prefKey)) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "grammovka_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
