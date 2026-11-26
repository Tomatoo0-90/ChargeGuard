package com.chargeguard.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** SharedPreferences-backed [KeyValueStore]. Holds only salt, MAC and counters, never the PIN. */
class SharedPrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("owner_pin", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

    override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
        val changes = mutableMapOf<String, Any?>().apply(block)
        prefs.edit(commit = true) {
            for ((k, v) in changes) when (v) {
                null -> remove(k)
                is String -> putString(k, v)
                is Long -> putLong(k, v)
                is Int -> putLong(k, v.toLong())
                is Boolean -> putBoolean(k, v)
                else -> error("Unsupported type for $k")
            }
        }
    }
}

/** HMAC-SHA256 with a non-exportable key generated inside Android Keystore. */
class KeystorePinMac : PinMac {
    override fun mac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(getOrCreateKey())
        return mac.doFinal(data)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "chargeguard_owner_pin_hmac"
    }
}
