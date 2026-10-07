package com.pocketdisco.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.pocketdisco.BuildConfig
import com.pocketdisco.spec.NativeDeviceSessionSpec
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class DeviceSessionModule(context: ReactApplicationContext) : NativeDeviceSessionSpec(context) {
    private val storage = context.getSharedPreferences("device_session", Context.MODE_PRIVATE)
    private val tasks = Executors.newSingleThreadExecutor()

    override fun getName() = NAME

    override fun getSettings(): WritableMap = Arguments.createMap().apply {
        putString("apiUrl", BuildConfig.API_URL)
        putBoolean("allowLocalServer", BuildConfig.ALLOW_LOCAL_SERVER)
    }

    override fun randomId(): String = UUID.randomUUID().toString()

    override fun load(promise: Promise) = tasks.execute {
        try {
            val encoded = storage.getString("session", null)
            val value = encoded?.let {
                SessionCipher(key()).decrypt(Base64.decode(it, Base64.NO_WRAP))
            }
            promise.resolve(value)
        } catch (_: Exception) {
            storage.edit().remove("session").commit()
            promise.reject("session_unavailable", "Please sign in again.")
        }
    }

    override fun save(value: String, promise: Promise) = tasks.execute {
        try {
            val encrypted = SessionCipher(key()).encrypt(value)
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(storage.edit().putString("session", encoded).commit())
            promise.resolve(null)
        } catch (_: Exception) {
            promise.reject("session_save_failed", "Could not save this session securely.")
        }
    }

    override fun clear(promise: Promise) = tasks.execute {
        if (storage.edit().remove("session").commit()) {
            promise.resolve(null)
        } else {
            promise.reject("session_clear_failed", "Could not clear this session.")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    override fun invalidate() {
        tasks.shutdown()
        super.invalidate()
    }

    companion object {
        const val NAME = "DeviceSession"
        private const val KEY_ALIAS = "pocketdisco.session.v1"
    }
}
