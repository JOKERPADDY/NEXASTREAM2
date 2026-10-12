package com.nexastream.app.providers

import android.util.Base64
import android.util.Log
import com.nexastream.app.BuildConfig
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object CricfyCrypto {
    private const val TAG = "CricfyCrypto"

    fun decryptProviderList(encrypted: String): String {
        return decryptProviderList(
            encrypted,
            listOf(BuildConfig.CRICFY_SECRET1, BuildConfig.CRICFY_SECRET2),
        )
    }

    fun decryptProviderList(encrypted: String, secrets: List<String>): String {
        val ciphertext = decodeBase64(encrypted)
        val configuredKeys = secrets
            .filter(String::isNotBlank)
            .map(::parseKey)
        require(configuredKeys.isNotEmpty()) {
            "Cricfy AES keys are missing. Configure CRICFY_SECRET1 or CRICFY_SECRET2 in local.properties."
        }

        configuredKeys.forEach { (key, iv) ->
            tryDecrypt(ciphertext, key, iv)?.let { plaintext ->
                if (plaintext.trimStart().let { it.startsWith("[") || it.startsWith("{") }) {
                    return plaintext
                }
            }
        }
        throw GeneralSecurityException("Cricfy provider list could not be decrypted with the configured AES keys")
    }

    fun decryptPlaylist(content: String): String {
        val trimmed = content.trim()
        if (trimmed.startsWith("#EXTM3U") ||
            trimmed.startsWith("#EXTINF") ||
            trimmed.startsWith("#KODIPROP")
        ) {
            return trimmed
        }
        if (trimmed.length < 79) return trimmed

        return try {
            val encrypted = trimmed.substring(0, 10) +
                trimmed.substring(34, trimmed.length - 54) +
                trimmed.takeLast(10)
            val iv = decodeBase64(trimmed.substring(10, 34))
            val key = decodeBase64(trimmed.substring(trimmed.length - 54, trimmed.length - 10))
            val ciphertext = decodeBase64(encrypted)
            decryptBytes(ciphertext, key, iv)
        } catch (error: Exception) {
            logW(TAG, "Playlist envelope decryption failed; trying the response as plain M3U", error)
            trimmed
        }
    }

    private fun parseKey(secret: String): Pair<ByteArray, ByteArray> {
        val parts = secret.split(':', limit = 2)
        require(parts.size == 2) { "Cricfy AES secret must use keyHex:ivHex format" }
        val key = decodeHex(parts[0])
        val iv = decodeHex(parts[1])
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key size" }
        require(iv.size == 16) { "Invalid AES IV size" }
        return key to iv
    }

    private fun decodeHex(value: String): ByteArray {
        require(value.length % 2 == 0) { "Hex value must have an even number of characters" }
        return value.chunked(2).map { pair ->
            pair.toInt(16).toByte()
        }.toByteArray()
    }

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.filterNot(Char::isWhitespace)
        return try {
            Base64.decode(normalized, Base64.DEFAULT)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(normalized)
        }
    }

    private fun tryDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): String? =
        try {
            decryptBytes(ciphertext, key, iv)
        } catch (_: Exception) {
            null
        }

    internal fun decryptBytes(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): String {
        require(ciphertext.isNotEmpty() && ciphertext.size % 16 == 0) {
            "AES-CBC ciphertext must be a non-empty multiple of 16 bytes"
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun logW(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg")
            tr?.printStackTrace(System.err)
        }
    }
}
