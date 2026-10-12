package com.nexastream.app.providers

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CricfyCryptoTest {

    @Test
    fun `decrypts AES CBC PKCS padded provider content`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val plaintext = "{\"providers\":[]}"
        val encryptedContent = encryptBytes(plaintext, key, iv)

        assertEquals(plaintext, CricfyCrypto.decryptBytes(encryptedContent, key, iv))
    }

    @Test
    fun `fails decryption with an incorrect AES key`() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (15 - it).toByte() }
        val encryptedContent = encryptBytes("{\"providers\":[]}", key, iv)

        assertThrows(GeneralSecurityException::class.java) {
            CricfyCrypto.decryptBytes(encryptedContent, ByteArray(16), iv)
        }
    }

    private fun encryptBytes(content: String, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(content.toByteArray(Charsets.UTF_8))
    }
}
