package com.nexastream.app.providers.magis

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin implementation of Magis TV's modified MD5 algorithm (tweaked_md5).
 * Bit-for-bit identical to the decompiled libranger-jni.so compression routine.
 */
object TweakedMd5 {
    private const val M32 = 0xFFFFFFFFL

    private val MD5_IV = byteArrayOf(
        0x01.toByte(), 0x23.toByte(), 0x45.toByte(), 0x67.toByte(),
        0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
        0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(),
        0x76.toByte(), 0x54.toByte(), 0x32.toByte(), 0x10.toByte()
    )

    private val K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd46f3085.toInt(), 0x04881d05, // K[42] tweaked
        0xd9d4d039.toInt(), 0xe6bd99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(), // K[45] tweaked
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffecc47d.toInt(), 0x85845dd1.toInt(), // K[54] tweaked
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2da7d2bb, 0xeb86d391.toInt()  // K[62] tweaked
    )

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    private val G = IntArray(64).apply {
        val round1 = intArrayOf(10, 11, 12, 13, 14, 15, 6, 7, 8, 9, 0, 1, 2, 3, 4, 5) // Round 1 schedule tweak
        for (i in 0 until 16) this[i] = round1[i]
        for (i in 16 until 32) this[i] = (5 * i + 1) % 16
        for (i in 32 until 48) this[i] = (3 * i + 5) % 16
        for (i in 48 until 64) this[i] = (7 * i) % 16
    }

    fun digestHex(msg: ByteArray): String {
        var state = MD5_IV.copyOf()
        val fullBlocks = msg.size - (msg.size % 64)

        for (i in 0 until fullBlocks step 64) {
            state = compress(state, msg.copyOfRange(i, i + 64))
        }

        val rem = msg.copyOfRange(fullBlocks, msg.size)
        val padLen = (56 - (rem.size + 1)).let { if (it < 0) it + 64 else it }
        val finalBuf = ByteBuffer.allocate(rem.size + 1 + padLen + 8).order(ByteOrder.LITTLE_ENDIAN)
        finalBuf.put(rem)
        finalBuf.put(0x80.toByte())
        finalBuf.put(ByteArray(padLen))
        finalBuf.putLong(msg.size.toLong() * 8)

        val finalBytes = finalBuf.array()
        for (j in finalBytes.indices step 64) {
            state = compress(state, finalBytes.copyOfRange(j, j + 64))
        }

        return state.joinToString("") { "%02x".format(it) }
    }

    private fun compress(state16: ByteArray, block64: ByteArray): ByteArray {
        val stateBuf = ByteBuffer.wrap(state16).order(ByteOrder.LITTLE_ENDIAN)
        val a0 = stateBuf.int
        val b0 = stateBuf.int
        val c0 = stateBuf.int
        val d0 = stateBuf.int

        val mBuf = ByteBuffer.wrap(block64).order(ByteOrder.LITTLE_ENDIAN)
        val m = IntArray(16) { mBuf.int }

        var a = a0
        var b = b0
        var c = c0
        var d = d0

        for (i in 0 until 64) {
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (d and b) or (d.inv() and c)
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            val temp = ((f.toLong() and M32) + (a.toLong() and M32) + (K[i].toLong() and M32) + (m[G[i]].toLong() and M32)) and M32
            val s = S[i]
            val rot = ((temp shl s) or (temp ushr (32 - s))) and M32

            a = d
            d = c
            c = b
            b = (((b.toLong() and M32) + rot) and M32).toInt()
        }

        val outBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        outBuf.putInt((((a.toLong() and M32) + (a0.toLong() and M32)) and M32).toInt())
        outBuf.putInt((((b.toLong() and M32) + (b0.toLong() and M32)) and M32).toInt())
        outBuf.putInt((((c.toLong() and M32) + (c0.toLong() and M32)) and M32).toInt())
        outBuf.putInt((((d.toLong() and M32) + (d0.toLong() and M32)) and M32).toInt())
        return outBuf.array()
    }
}
