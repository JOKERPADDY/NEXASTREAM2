package com.nexastream.app.providers.magis

/**
 * Signer for Magis TV segment requests using TweakedMd5.
 */
object SignO3 {
    private val SALT = "salt3333=4".toByteArray(Charsets.UTF_8) + byteArrayOf(
        0x98.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x15.toByte(), 0x32.toByte(),
        0xc9.toByte(), 0xc3.toByte(), 0x82.toByte(), 0x17.toByte(), 0x08.toByte(), 0xc0.toByte()
    )

    fun sign2(token: String, startMomentMs: Long): String {
        val msgStr = "token=$token&sign2_method=sign_o3&instance=0&start_moment=$startMomentMs"
        val msgBytes = msgStr.toByteArray(Charsets.UTF_8) + SALT
        return TweakedMd5.digestHex(msgBytes)
    }
}
