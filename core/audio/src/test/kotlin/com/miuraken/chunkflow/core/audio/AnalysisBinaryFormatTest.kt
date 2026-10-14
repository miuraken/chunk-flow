package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.Waveform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisBinaryFormatTest {

    @Test
    fun `waveform_エンコードとデコードおよびアトミックファイル読み書きが往復一致する`() {
        val original = Waveform(
            binMs = 10,
            peak = byteArrayOf(0, 50, 128.toByte(), 255.toByte()),
            rms = byteArrayOf(0, 25, 90, 200.toByte()),
        )

        val encoded = AnalysisBinaryFormat.encodeWaveform(original)
        assertEquals(AnalysisBinaryFormat.HEADER_SIZE_BYTES + 8, encoded.size)

        val decoded = AnalysisBinaryFormat.decodeWaveform(encoded)
        assertNotNull(decoded)
        assertEquals(10, decoded.binMs)
        assertContentEquals(original.peak, decoded.peak)
        assertContentEquals(original.rms, decoded.rms)

        val tempDir = Files.createTempDirectory("cf_wf_test").toFile()
        try {
            val file = File(tempDir, "waveform.bin")
            assertTrue(AnalysisBinaryFormat.writeWaveformAtomic(file, original))
            val fromFile = AnalysisBinaryFormat.readWaveform(file)
            assertNotNull(fromFile)
            assertEquals(10, fromFile.binMs)
            assertContentEquals(original.peak, fromFile.peak)
            assertContentEquals(original.rms, fromFile.rms)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `features_エンコードとデコードおよびアトミックファイル読み書きが往復一致する`() {
        val original = StoredFeatures(
            binMs = 10,
            logEnergyDb = floatArrayOf(-100f, -45.5f, -12.25f, 0f),
        )

        val encoded = AnalysisBinaryFormat.encodeFeatures(original)
        assertEquals(AnalysisBinaryFormat.HEADER_SIZE_BYTES + 16, encoded.size)

        val decoded = AnalysisBinaryFormat.decodeFeatures(encoded)
        assertNotNull(decoded)
        assertEquals(10, decoded.binMs)
        assertContentEquals(original.logEnergyDb, decoded.logEnergyDb)

        val tempDir = Files.createTempDirectory("cf_feat_test").toFile()
        try {
            val file = File(tempDir, "features.bin")
            assertTrue(AnalysisBinaryFormat.writeFeaturesAtomic(file, original))
            val fromFile = AnalysisBinaryFormat.readFeatures(file)
            assertNotNull(fromFile)
            assertEquals(10, fromFile.binMs)
            assertContentEquals(original.logEnergyDb, fromFile.logEnergyDb)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `破損データ検証_マジック不一致やバージョン違いや切り詰めやNaNは例外を投げずにnullを返す`() {
        val wf = Waveform(binMs = 10, peak = byteArrayOf(1, 2, 3), rms = byteArrayOf(1, 2, 3))
        val validWfBytes = AnalysisBinaryFormat.encodeWaveform(wf)

        // 1. ヘッダ未満の短いバイト列
        assertNull(AnalysisBinaryFormat.decodeWaveform(ByteArray(5)))
        assertNull(AnalysisBinaryFormat.decodeFeatures(ByteArray(15)))

        // 2. マジックヘッダ破損
        val badMagic = validWfBytes.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertNull(AnalysisBinaryFormat.decodeWaveform(badMagic))
        // 波形バイト列を特徴量として読もうとした場合もマジック不一致で null
        assertNull(AnalysisBinaryFormat.decodeFeatures(validWfBytes))

        // 3. 非対応バージョン
        val badVersion = validWfBytes.copyOf().apply { this[7] = 99 }
        assertNull(AnalysisBinaryFormat.decodeWaveform(badVersion))

        // 4. ペイロードの切り詰め・末尾余剰バイト
        assertNull(AnalysisBinaryFormat.decodeWaveform(validWfBytes.copyOf(validWfBytes.size - 1)))
        assertNull(AnalysisBinaryFormat.decodeWaveform(validWfBytes + byteArrayOf(0)))

        // 5. features.bin に NaN が混入している場合
        val validFeatBytes = AnalysisBinaryFormat.encodeFeatures(floatArrayOf(-50f, -20f), binMs = 10)
        assertNull(AnalysisBinaryFormat.decodeFeatures(validFeatBytes.copyOf(validFeatBytes.size - 2)))
        val nanBytes = validFeatBytes.copyOf().apply {
            // 最初の Float を NaN (0x7FC00000) に書き換える
            this[16] = 0x7F.toByte()
            this[17] = 0xC0.toByte()
            this[18] = 0x00
            this[19] = 0x00
        }
        assertNull(AnalysisBinaryFormat.decodeFeatures(nanBytes))
    }
}
