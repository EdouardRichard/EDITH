package com.sheen.rokid.testsupport

import kotlin.math.PI
import kotlin.math.sin

object SyntheticPcm {
    fun sine(sampleRateHz: Int, durationMillis: Int, frequencyHz: Double = 440.0): ByteArray {
        require(sampleRateHz > 0 && durationMillis >= 0 && frequencyHz > 0)
        val count = Math.multiplyExact(sampleRateHz.toLong(), durationMillis.toLong()) / 1000
        require(count <= Int.MAX_VALUE / 2)
        return ByteArray(count.toInt() * 2).also { bytes ->
            repeat(count.toInt()) { index ->
                val sample = (sin(2 * PI * frequencyHz * index / sampleRateHz) * 16384).toInt()
                bytes[index * 2] = sample.toByte(); bytes[index * 2 + 1] = (sample shr 8).toByte()
            }
        }
    }
}
