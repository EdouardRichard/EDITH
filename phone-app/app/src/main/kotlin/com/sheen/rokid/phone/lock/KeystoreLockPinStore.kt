package com.sheen.rokid.phone.lock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The lock-screen PIN, encrypted at rest with an Android Keystore AES/GCM key.
 *
 * There is no plaintext PIN in SharedPreferences and no backup: the App has `allowBackup=false`, and the Keystore key
 * never leaves the device. [read] returns a mutable [CharArray] so the caller can zero it; the stored value is not a
 * `String` field.
 *
 * A failed decrypt returns `null` and removes the unreadable blob: a PIN the App cannot decrypt must behave like "not
 * configured", never like a wrong PIN that is then typed into the keyguard.
 */
class KeystoreLockPinStore(context: Context) : LockPinStore {

    // Lazy: a composition built with a context-less test double must still construct; the store is only touched when
    // the settings page or an unlock actually reads it.
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun hasPin(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    override fun read(): CharArray? {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plain = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            try {
                String(plain, Charsets.UTF_8).toCharArray()
            } finally {
                plain.fill(0)
            }
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(pin: String) {
        val plain = pin.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain)
            prefs.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        } finally {
            plain.fill(0)
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).commit()
        runCatching {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "edith.lock.pin.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFS = "edith_lock_pin"
        const val KEY_CIPHERTEXT = "pin_ciphertext"
        const val KEY_IV = "pin_iv"
    }
}
