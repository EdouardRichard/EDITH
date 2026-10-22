package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 0.3 Phase 1 / design §5.2: one dependency-free prompt-size estimate shared by the context budgeter, the turn trimmer
 * and the plugin. The estimator is deliberately conservative rather than a real tokenizer; these cases pin the weights
 * so no caller falls back to byte counts again.
 */
class TokenMeterTest {

    @Test
    fun `empty text estimates zero tokens`() {
        assertEquals(0, TokenMeter.estimate(""))
    }

    @Test
    fun `ascii counts one token per four code points rounded up`() {
        assertEquals(1, TokenMeter.estimate("a"))
        assertEquals(1, TokenMeter.estimate("abcd"))
        assertEquals(2, TokenMeter.estimate("hello"))
        assertEquals(25, TokenMeter.estimate("a".repeat(100)))
    }

    @Test
    fun `cjk counts one token per code point`() {
        assertEquals(1, TokenMeter.estimate("你"))
        assertEquals(2, TokenMeter.estimate("你好"))
        assertEquals(5, TokenMeter.estimate("こんにちは"))
        assertEquals(5, TokenMeter.estimate("コンニチハ"))
        assertEquals(5, TokenMeter.estimate("안녕하세요"))
    }

    @Test
    fun `non ascii scripts and emoji count one token per code point`() {
        assertEquals(6, TokenMeter.estimate("привет"))
        // Four astral code points are eight UTF-16 chars: counting chars would estimate 8, not 4.
        assertEquals(4, TokenMeter.estimate("😀😀😀😀"))
    }

    @Test
    fun `mixed text adds the weights and rounds up`() {
        assertEquals(4, TokenMeter.estimate("你好hello"))
        assertEquals(4, TokenMeter.estimate("hello你好"))
        assertEquals(2, TokenMeter.estimate("a😀"))
    }
}
