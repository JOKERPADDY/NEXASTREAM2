package com.nexastream.app.providers.magis

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Handles 3DES encryption/decryption for Magis TV portal core API payloads.
 */
object Magis3Des {
    fun encryptBody(plainJson: String, keyHex: String): String {
        val keyBytes = hexToBytes(keyHex)
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "DESede"))
        val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        val b64Str = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return bytesToHex(b64Str.toByteArray(Charsets.US_ASCII))
    }

    fun decryptBlob(wireHex: String, keyHex: String): String {
        val keyBytes = hexToBytes(keyHex)
        val asciiBytes = hexToBytes(wireHex)
        val b64Str = String(asciiBytes, Charsets.US_ASCII)
        val encryptedBytes = Base64.decode(b64Str, Base64.DEFAULT)
        val cipher = Cipher.getInstance("DESede/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "DESede"))
        val decrypted = cipher.doFinal(encryptedBytes)
        return String(decrypted, Charsets.UTF_8)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
