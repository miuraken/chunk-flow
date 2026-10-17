package com.miuraken.chunkflow.core.media

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Mp3FrameHeaderTest {

    @Test
    fun `MPEG1のLayerIII_ビットレートとサンプルレートとパディングからフレーム長を求めサンプル数は1152`() {
        // FF FB 90: MPEG-1 Layer III・保護なし・128kbps・44.1kHz・パディングなし
        assertEquals(Mp3FrameInfo(frameSizeBytes = 417, samplesPerFrame = 1_152), Mp3FrameHeader.parse(0xFF, 0xFB, 0x90))
        // パディングあり（FF FB 92）は 1 バイト長い
        assertEquals(Mp3FrameInfo(frameSizeBytes = 418, samplesPerFrame = 1_152), Mp3FrameHeader.parse(0xFF, 0xFB, 0x92))
        // 保護あり（FF FA）でも長さは変わらない
        assertEquals(Mp3FrameInfo(frameSizeBytes = 417, samplesPerFrame = 1_152), Mp3FrameHeader.parse(0xFF, 0xFA, 0x90))
        // 320kbps・32kHz・パディングありは Layer III の最大長 1,441 バイト
        assertEquals(Mp3FrameInfo(frameSizeBytes = 1_441, samplesPerFrame = 1_152), Mp3FrameHeader.parse(0xFF, 0xFB, 0xEA))
    }

    @Test
    fun `MPEG2とMPEG2_5のLayerIII_係数72でフレーム長を求めサンプル数は576`() {
        // FF F3 80: MPEG-2 Layer III・64kbps・22.05kHz → 72000 × 64 ÷ 22050 = 208
        assertEquals(Mp3FrameInfo(frameSizeBytes = 208, samplesPerFrame = 576), Mp3FrameHeader.parse(0xFF, 0xF3, 0x80))
        // FF E3 18: MPEG-2.5 Layer III・8kbps・8kHz → 72000 × 8 ÷ 8000 = 72
        assertEquals(Mp3FrameInfo(frameSizeBytes = 72, samplesPerFrame = 576), Mp3FrameHeader.parse(0xFF, 0xE3, 0x18))
    }

    @Test
    fun `正しくないヘッダ_同期語と予約値とLayerIIIでないものとフリーフォーマットはnull`() {
        assertNull(Mp3FrameHeader.parse(0xFE, 0xFB, 0x90), "同期語の 1 バイト目が違う")
        assertNull(Mp3FrameHeader.parse(0xFF, 0x7B, 0x90), "同期語の残り 3 ビットが違う")
        assertNull(Mp3FrameHeader.parse(0xFF, 0xEB, 0x90), "予約のバージョン")
        assertNull(Mp3FrameHeader.parse(0xFF, 0xFF, 0x90), "Layer I")
        assertNull(Mp3FrameHeader.parse(0xFF, 0xFD, 0x90), "Layer II")
        assertNull(Mp3FrameHeader.parse(0xFF, 0xFB, 0x00), "フリーフォーマット（ビットレート 0）")
        assertNull(Mp3FrameHeader.parse(0xFF, 0xFB, 0xF0), "不正なビットレート（15）")
        assertNull(Mp3FrameHeader.parse(0xFF, 0xFB, 0x9C), "予約のサンプルレート")
    }

    @Test
    fun `ByteBufferから読む_指定位置のヘッダを読み範囲外はnullで位置と範囲を変えない`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0xFF.toByte(), 0xFB.toByte(), 0x92.toByte(), 0xC0.toByte()))
        buffer.position(1)

        assertEquals(Mp3FrameInfo(frameSizeBytes = 418, samplesPerFrame = 1_152), Mp3FrameHeader.parse(buffer, 2))
        assertNull(Mp3FrameHeader.parse(buffer, 0), "ヘッダでない位置")
        assertNull(Mp3FrameHeader.parse(buffer, 3), "4 バイトが範囲に収まらない")
        assertNull(Mp3FrameHeader.parse(buffer, -1), "負の位置")
        assertEquals(1, buffer.position())
        assertEquals(6, buffer.limit())
    }
}
