package com.pocketdisco.session

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionCipher(private val key: SecretKey) {
    fun encrypt(value: String): ByteArray {
        require(value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        check(cipher.iv.size == IV_BYTES)
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(value: ByteArray): String {
        require(value.size in (IV_BYTES + TAG_BYTES)..(MAX_BYTES + IV_BYTES + TAG_BYTES))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(value, IV_BYTES, value.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    companion object {
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val MAX_BYTES = 16_384
    }
}
