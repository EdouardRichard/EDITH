package com.sheen.rokid.model

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

data class SseFrame(val event: String?, val data: String) {
    override fun toString() = "SseFrame(redacted)"
}

class SseDecodingException(val safeCode: String) : IllegalArgumentException(safeCode)

/** Request-local SSE framing. Only an empty line dispatches; EOF discards unfinished data. */
class SseDecoder(maxFrameBytes: Int = 65536) {
    private val limit = maxFrameBytes.also { require(it > 0) }
    private val utf8 = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private val pending = ByteBuffer.allocate(4)
    private val decoded = CharBuffer.allocate(2)
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var event: String? = null
    private var hasData = false
    private var frameBytes = 0
    private var skipLf = false
    private var firstCharacter = true
    private var finished = false

    fun feed(bytes: ByteArray): List<SseFrame> {
        if (finished) return emptyList()
        val result = mutableListOf<SseFrame>()
        for (byte in bytes) {
            // Do not charge the second half of CRLF to a freshly reset frame.
            if (!(skipLf && frameBytes == 0 && byte == 10.toByte())) {
                if (frameBytes == limit) invalid("FRAME_LIMIT")
                frameBytes++
            }
            pending.put(byte)
            decode(false, result)
        }
        return result
    }

    fun finish(): List<SseFrame> {
        if (finished) return emptyList()
        val result = mutableListOf<SseFrame>()
        decode(true, result)
        decoded.clear()
        if (utf8.flush(decoded).isError) invalid("INVALID_UTF8")
        decoded.flip()
        while (decoded.hasRemaining()) character(decoded.get(), result)
        finished = true
        line.clear(); data.clear(); event = null
        return result
    }

    private fun decode(end: Boolean, result: MutableList<SseFrame>) {
        pending.flip()
        decoded.clear()
        val status = utf8.decode(pending, decoded, end)
        if (status.isError) invalid("INVALID_UTF8")
        pending.compact()
        decoded.flip()
        while (decoded.hasRemaining()) character(decoded.get(), result)
    }

    private fun character(char: Char, result: MutableList<SseFrame>) {
        if (firstCharacter) {
            firstCharacter = false
            if (char == '\uFEFF') return
        }
        if (skipLf) {
            skipLf = false
            if (char == '\n') return
        }
        when (char) {
            '\r' -> { completeLine(result); skipLf = true }
            '\n' -> completeLine(result)
            else -> line.append(char)
        }
    }

    private fun completeLine(result: MutableList<SseFrame>) {
        if (line.isEmpty()) {
            if (hasData) result += SseFrame(event?.takeIf { it.isNotEmpty() }, data.toString().dropLast(1))
            data.clear(); event = null; hasData = false; frameBytes = 0
            return
        }
        if (line[0] != ':') {
            val colon = line.indexOf(":")
            val field = if (colon < 0) line.toString() else line.substring(0, colon)
            val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
            when (field) {
                "event" -> event = value
                "data" -> { data.append(value).append('\n'); hasData = true }
            }
        }
        line.clear()
    }

    private fun invalid(code: String): Nothing {
        finished = true
        line.clear(); data.clear(); event = null
        throw SseDecodingException(code)
    }
}
