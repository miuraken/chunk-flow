package com.miuraken.chunkflow.core.media

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WhisperTinyLanguageDetectorTest {

    @Test
    fun `supportedLanguageCodes_量子化モデルバイナリをロードし99言語のコード一覧を返す`() {
        val context = RuntimeEnvironment.getApplication()
        val detector = WhisperTinyLanguageDetector(context)
        val codes = detector.supportedLanguageCodes()

        assertEquals(99, codes.size)
        assertEquals(listOf("en", "zh", "de", "es", "ru", "ko", "fr", "ja"), codes.take(8))
        assertTrue("ko" in codes)
        assertTrue("ja" in codes)
        assertTrue("en" in codes)
        assertTrue("zh" in codes)
        assertTrue("hi" in codes)
    }

    @Test
    fun `detectLanguagePair_KoreanLessonJaの実音声チャンクで日本語と韓国語をミリ秒単位で正確に識別する`() {
        val context = RuntimeEnvironment.getApplication()
        val detector = WhisperTinyLanguageDetector(context)

        // Korean_lesson_ja.mp3 の日本語チャンク (4066..5050ms: 「第6課です」)
        val jaShorts = decodeGzipBase64Shorts(JA_SPEECH_GZIP_B64)
        val jaPrediction = detector.detectLanguagePair(
            pcm16kMono = jaShorts,
            targetTag = "ko",
            baseTag = "ja",
        )
        assertNotNull(jaPrediction)
        assertEquals("ja", jaPrediction.selectedTag)
        assertEquals("ko", jaPrediction.fallbackTag)
        assertTrue(
            jaPrediction.baseProbability > 0.95f,
            "Expected ja probability > 0.95, actual=${jaPrediction.baseProbability}",
        )
        assertTrue(
            jaPrediction.selectedProbability > jaPrediction.targetProbability,
            "Expected selectedProb > targetProb",
        )

        // Korean_lesson_ja.mp3 の韓国語チャンク (9858..11098ms: 「오늘 명동에 가세요?」)
        val koShorts = decodeGzipBase64Shorts(KO_SPEECH_GZIP_B64)
        val koPrediction = detector.detectLanguagePair(
            pcm16kMono = koShorts,
            targetTag = "ko-KR",
            baseTag = "ja-JP",
        )
        assertNotNull(koPrediction)
        assertEquals("ko-KR", koPrediction.selectedTag)
        assertEquals("ja-JP", koPrediction.fallbackTag)
        assertTrue(
            koPrediction.targetProbability > 0.95f,
            "Expected ko probability > 0.95, actual=${koPrediction.targetProbability}",
        )
        assertTrue(
            koPrediction.inferenceTimeMs < 2_000L,
            "Expected fast inference (< 2000ms), actual=${koPrediction.inferenceTimeMs}ms",
        )
    }

    @Test
    fun `detectLanguagePair_無音チャンクや同一言語ペアではnullを返す`() {
        val detector = WhisperTinyLanguageDetector()
        val silent = ShortArray(16_000) { 0 }
        assertNull(detector.detectLanguagePair(silent, targetTag = "ko", baseTag = "ja"))

        val koShorts = decodeGzipBase64Shorts(KO_SPEECH_GZIP_B64)
        assertNull(detector.detectLanguagePair(koShorts, targetTag = "ko", baseTag = "ko-KR"))
        assertNull(detector.detectLanguagePair(koShorts, targetTag = "unknown_lang", baseTag = "ja"))
    }

    @Test
    fun `detectLanguagePair_短縮窓1500msでも実音声チャンクの日本語と韓国語を高精度に識別する`() {
        val context = RuntimeEnvironment.getApplication()
        val detector = WhisperTinyLanguageDetector(context)

        val jaShorts = decodeGzipBase64Shorts(JA_SPEECH_GZIP_B64)
        val koShorts = decodeGzipBase64Shorts(KO_SPEECH_GZIP_B64)

        val jaFast = detector.detectLanguagePair(
            pcm16kMono = jaShorts,
            targetTag = "ko",
            baseTag = "ja",
            maxWindowSamples = WhisperTinyLanguageDetector.FAST_LID_WINDOW_SAMPLES,
        )
        assertNotNull(jaFast)
        assertEquals("ja", jaFast.selectedTag)
        assertTrue(jaFast.baseProbability > 0.95f)

        val koFast = detector.detectLanguagePair(
            pcm16kMono = koShorts,
            targetTag = "ko-KR",
            baseTag = "ja-JP",
            maxWindowSamples = WhisperTinyLanguageDetector.FAST_LID_WINDOW_SAMPLES,
        )
        assertNotNull(koFast)
        assertEquals("ko-KR", koFast.selectedTag)
        assertTrue(koFast.targetProbability > 0.95f)
    }

    @Test
    fun `VoiceProfileLanguageTracker_実音声チャンクのWhisper埋め込みを学習し言語判定と統合できる`() {
        val context = RuntimeEnvironment.getApplication()
        val detector = WhisperTinyLanguageDetector(context)
        val tracker = VoiceProfileLanguageTracker()
        val jaShorts = decodeGzipBase64Shorts(JA_SPEECH_GZIP_B64)
        val koShorts = decodeGzipBase64Shorts(KO_SPEECH_GZIP_B64)
        val audioKey = "/test/korean_lesson_ja.mp3"

        // 同じ発話の開始位置を少しずつずらした部分音声を、各言語 3 件ずつアンカーとして登録する
        fun windows(shorts: ShortArray): List<ShortArray> = (0 until 3).map { k ->
            shorts.copyOfRange(shorts.size * k / 10, shorts.size)
        }
        var observationKey = 0L
        for (window in windows(jaShorts)) {
            val analysis = assertNotNull(detector.analyzeLanguagePair(window, targetTag = "ko", baseTag = "ja"))
            assertEquals(384, analysis.encoderEmbedding.size)
            assertTrue(analysis.prediction.pairMass in 0.0f..1.0f)
            tracker.recordSpeakerObservation(audioKey, observationKey++, "ja", analysis.encoderEmbedding)
        }
        for (window in windows(koShorts)) {
            val analysis = assertNotNull(detector.analyzeLanguagePair(window, targetTag = "ko", baseTag = "ja"))
            tracker.recordSpeakerObservation(audioKey, observationKey++, "ko", analysis.encoderEmbedding)
        }

        val koQuery = assertNotNull(detector.analyzeLanguagePair(koShorts, targetTag = "ko-KR", baseTag = "ja-JP"))
        val predForKo = assertNotNull(
            tracker.predictLanguageFromVoiceProfile(
                audioScopeKey = audioKey,
                voiceProfile = koQuery.encoderEmbedding,
                targetTag = "ko-KR",
                baseTag = "ja-JP",
            ),
        )
        assertTrue(predForKo.targetProbability > predForKo.baseProbability)
        assertTrue(predForKo.logOdds > 0f, "logOdds=${predForKo.logOdds}")

        // ペア外に確率が流れた曖昧な Whisper 判定（0.52 vs 0.48）でも、ファイル内の判定で対象言語が補強される
        val ambiguousWhisper = LanguagePairPrediction(
            selectedTag = "ko-KR",
            fallbackTag = "ja-JP",
            selectedProbability = 0.52f,
            targetProbability = 0.52f,
            baseProbability = 0.48f,
            inferenceTimeMs = 10L,
            pairMass = 0.3f,
        )
        val combined = tracker.combineWithWhisperPrediction(
            whisperPrediction = ambiguousWhisper,
            speakerPrediction = predForKo,
            targetTag = "ko-KR",
            baseTag = "ja-JP",
            analyzedDurationMs = 1_200L,
        )
        assertEquals("ko-KR", combined.selectedTag)
        assertTrue(combined.targetProbability > ambiguousWhisper.targetProbability)
    }

    @Test
    fun `languageFamilyIndices_ヒンディー語にはウルドゥー語を合算し相手側の言語は含めない`() {
        val index = WhisperTinyLanguageDetector.WHISPER_99_LANGUAGE_CODES.withIndex().associate { it.value to it.index }
        val hiWithEn = WhisperTinyLanguageDetector.languageFamilyIndices("hi", "en", index).toSet()
        assertEquals(setOf(index.getValue("hi"), index.getValue("ur")), hiWithEn)
        // ヒンディー語とウルドゥー語のペアでは合算しない
        val hiWithUr = WhisperTinyLanguageDetector.languageFamilyIndices("hi", "ur", index).toSet()
        assertEquals(setOf(index.getValue("hi")), hiWithUr)
        val jaWithKo = WhisperTinyLanguageDetector.languageFamilyIndices("ja", "ko", index).toSet()
        assertEquals(setOf(index.getValue("ja")), jaWithKo)
    }

    @Test
    fun `buildFamilyPairPrediction_同系言語の確率を合算しペア外の確率をpairMassに反映する`() {
        val codes = WhisperTinyLanguageDetector.WHISPER_99_LANGUAGE_CODES
        val index = codes.withIndex().associate { it.value to it.index }
        val logits = FloatArray(codes.size) { -20f }
        // ヒンディー語の発話にウルドゥー語の確率が多く割り当てられた状態（en > hi だが hi + ur > en）
        logits[index.getValue("ur")] = 2.0f
        logits[index.getValue("en")] = 1.0f
        logits[index.getValue("hi")] = 0.5f
        logits[index.getValue("pt")] = 1.0f
        val prediction = WhisperTinyLanguageDetector.buildFamilyPairPrediction(
            logits = logits,
            targetIndices = WhisperTinyLanguageDetector.languageFamilyIndices("hi", "en", index),
            baseIndices = WhisperTinyLanguageDetector.languageFamilyIndices("en", "hi", index),
            targetTag = "hi",
            baseTag = "en",
            inferenceTimeMs = 0L,
        )
        assertEquals("hi", prediction.selectedTag)
        val expTarget = kotlin.math.exp(2.0) + kotlin.math.exp(0.5)
        val expBase = kotlin.math.exp(1.0)
        val expectedTarget = (expTarget / (expTarget + expBase)).toFloat()
        assertEquals(expectedTarget, prediction.targetProbability, 1e-4f)
        // pt に流れた分だけ pairMass は 1 より小さい
        val expectedMass = ((expTarget + expBase) / (expTarget + expBase + kotlin.math.exp(1.0))).toFloat()
        assertEquals(expectedMass, prediction.pairMass, 1e-3f)
    }

    private fun decodeGzipBase64Shorts(b64: String): ShortArray {
        val compressed = Base64.getDecoder().decode(b64)
        val rawBytes = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        val shortCount = rawBytes.size / 2
        val shorts = ShortArray(shortCount)
        val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = bb.short
        }
        return shorts
    }

    companion object {
        private val JA_SPEECH_GZIP_B64: String =
            "H4sIAE/W0GoC/zRcBZgUR9PunpnV29tz5Q4OOdydACFocLegIRDcCQRIQgiuST40uGsgBEhwJ5Dg7u7n6zva3X9NH//Tz+yOdldX" +
                "vfVW9d3U2pATidB0ZjDKCLMhBEeE6SzIVCaiMJyXWYjlwncB8zMPNB9cM8+EmMI02FdYAO4Jw6cPNgWeM6AnAWnQiwYNIQGaDkcE" +
                "xtB5Y8yKKNwjwngMviX4xnxkjV+1wJ4C48nMjqLhmswwv+5AduRGNrjfApKbmxVFwFEEioSrNn5FgtEYPEtAfgqbgAzoUYR7XXDN" +
                "xqUxoHcvzEXn8/FxSUUUAFmDMCMGm/mMKbH5aTBTToNJ0C+CPiKhlxiQKxXFwV4sioctEj5j4EwRlALfbrgShxJBLje0aLgqwegR" +
                "0CwwDyvs2+GMHWTCMCdzBDdcYaB/DOOYOrGA7Boz9RECLWiM8k1AKkguw6bDFgYtikhhBte6BnOm8KxpvUKdR4B0pmYioW8BRrXB" +
                "FgVnorhcDpDYBZ8Ori/GnxT5DM3nzX4pNIn3VDi6qUUMdrWA5BboTWcSt5+pFztsGkgS5lZTQK8B3kwpFdCnuV/A3gFu/CwH9rzs" +
                "A+g9H74LMSPDsyE4CsFZc4wCaEGYv/qxVxW2MEgQ5jYJMcJxgkE2UxaVS2YiWeNn2ceZWLmtzRmZo/g4Vj0gQxb07WHZcCYPZPCA" +
                "XAG+HwQJTOQTOKbc8qYE5hnEdSTzc1YYUYG+TEtS5kQm2p2gUSfYPgGwYOrcBVqOBSREwnc06DgGJcNx4Rkn7EeB/kSQPYJ7nIBM" +
                "36PQo4XrW4RnXLCfBPfZ4ZlYQFMC3JuMSqCGqCV6gDZhu9BBSBdShBxcRqgr3MOp+BoaiTqj2egr5GMl0aeoB+qGuqN/WDxLYudo" +
                "WbqTTCLVSTEyiHQnn5FR5BL5nE6gi+h4+iPdSc/RPfR72okOpHuph9pZDKvB/ge6Ko8+Q5+gqqgx6onagfR+mG1l9AX6Eg1E09A/" +
                "yI6L4dLYjgVcBKdgPzqDtqCVaA86ig6inWgbOoneIxF70S1o/8LZ5SBlC+ihLEqHGXnZa7Cp6b0q2OMe+4/dZX+wFWw0G87GwjaJ" +
                "DWYT2By2jp1iJ9lldok9ASvI3BZuVAbVQs1QR5BkCvS7Ee1AW2HMPehPtB2tRT+AfFPQYNBHf9QcZtEefY6qo9LcSnbAy0N2nv3D" +
                "/oKej7ID7Hd2jJ1mJ2CUQ3B0Gq7cYP/ClRPsDLvGrkO7xV6xt5z3ZI4KG9g8HuaAwVoR3MLRcJwCUpVCGTC7DFQBRisNxwnABYlg" +
                "zUTOUTHQEuFZK7epFdChQK8h6DMfUJ/FHrNH7A20J7B3hz2Ez3cgwS12lR+9gWNTnnvQbrHn8MQrLlEYEGv2lgwjmPwShdKAjwqR" +
                "UwKkKAJbBoxcEpWD70zAUSfQzjm0EAvCbuG28FS4LuwRXgvHhM7CabDlBjQCNFsePQKpgiwTNYV+trAK7AWdSK10OokgS4wpRmej" +
                "iVHKaG/8YVQgG4lOitP+9Bf6mGr0NN1NfwZMnaKH6Tq6gN6jDdgM6EuAPr8C+9wGK20Gqy0Fey2GsZqjLmgyWPAKuoleo3TcAnfD" +
                "XXFNXAMn4QSsA9JWgz1/RP/jn1PQIrQO7YX7F6LpaDRYuBUqDrN/DTq6wPaxXWw92w4WvcVusovsGXsJXv4G9s+zw7CdAv0dYXvZ" +
                "bThrAyS2RB3QUJBlG8j0F8hzCnB6AJC0BKSbhZahX9A8GGUmWgCo2oLuoyK4L/4L38DxgltoIHQRvhSaC7HCVfw3Hojb4xfoP8Dh" +
                "anQMPUQ30B3A/3l0GOScj+qhYigXkNYVPLI0a8mGsR2ANYwaoVVwVxZ6hQ7BqDNRHXSBtWYFdBXtR8vTR2Qn+Z6MIV+RDqQhaUFa" +
                "k09JeWIjl42txiZjjjHcaG7UNaobrYxhcHTQeGqkkN5kEVlHlpJ5ZDH5iQwklUks8RgfjBzjmfHG0IzKZBhZSA6RO2CzkrQWbQUj" +
                "DafT6Qp6kt6kfhrHurCf2QuWgdqg38BvE3BjXBu3wV/iyXgI/h5PxxPxPHwa+3CG0BCYaKawRjgoPBRuCX8KO4Xzwl9wPEOYIPwo" +
                "fCtMF34WlglrhSNCtiCKKeJnYmdxprhE3CVeFR+LVCwvtZEmSjult1KcpbllimWP5ZFFsKZYq1rrWSta46yG5bllq2WUJd3yQdot" +
                "TZYqSI/FOWIl8aWwALT/EP+EM/AJNAi4fzNrzwS2nw6hxegTsoHMIG1JJVKGlCaJgFcnMYxEUopUIA1IF9Ble9IXdDqOTAddrSB/" +
                "kX/JE3Kb3IXPW+QGuUouk31kB5lLpoHuR5AhZAKZSMbC/Z3BAvVIUehTNt6Cts8ZB4yVxlJjkjHOmGyMNQYZvcEWnxoVjIpGFfCQ" +
                "NkYnY6jRF67PMLYYF+CZDLDgYLKNXCdBUp72Bq3/R4O0POvFZgN2XzIXIGIE4O4QcKaGLDgZV8b1obXEzXEr/BnfrwHftXB13BC2" +
                "6nyvNq4En/Xhuylca4Q/xx1wf7DYcLDXXLwKb8Un8UX8DoJmlFADENtL+E74BaxyVyBCKbGFOFlcKh4Ur4kfRJtUVKorNZEaSy2k" +
                "BlIdKVOKlwTJI16H60vE4WJTMUF8LVwUVgqjhZoCwZfwSkBFKRxCZyH+dAHWec32sB9ZK1aMBehF+judDazRjVajpWkqTaBxNJ3G" +
                "0hj4LEtr06aggW/oXLqDHqDH6B36iubRAhqiOqWQggSplz6n1+g/9ChnlG9od1qJUvKA7CHfka4kk/iMm8bfxmbjN2OZsdD4Gb5X" +
                "GWuNXWCVv4zfwUOWGLOML4124CGxhsuINuqAx2w1XoMV+pA1YO0k2pHOgd6f0gAVWSrLZOVZfdaODWDLId7YgJl+Aj7wohp4AF6A" +
                "j+JT+Awgfz/egTfgWXgkaLgX/gJaPzwW/GI53oOv4yysYocgCDYhXoiERrCGg/glnM/CXrBAotBCGCosBx36hSixltgPtL9c3Cr+" +
                "If4OOt4pbgBbzBQniv3F3uAtrcXqYoYYI6rCY+GUsAm8qpuQIeTiw3g2IMKOH6D1aBjEQit6xQ6yeRAzO7JqLJnZgEOe0rOgt2V0" +
                "Kp1GR9PBdCgdQL+kg+gIiP0z6QbQ+XX6gRo0BeZdndVkTVhDYKY2rDa0mmC/OIZZiObSl/QBWPIU3Ue3ApuPgx460rq0OEX0PXjL" +
                "HvCgHyCzGAy5RRNgqQrgc8VJOfC96nBcnzQjn4PXdCD9yXDgsv+R3eQKaN5HMPTQGOw/F3q9DlaPZVVZWzaC/QS8+AjYOQnVgLg9" +
                "EFC1FLj+InqLAghDnlEOVwOE94Q2FE/AU/E0/CNegtfiXXgf2OY/fBk/wW+xB1uEUpAddRFmC1uEM8Jb0HasWFZsDxj+TpwvrhBX" +
                "g56Xi4vFWeIU8RtxhNhHbC5mitFiQHgCnvGbMFFoKWQKCv4X/Gc0roet+B06AfGqL2REDvQSuHwm6wdYKcoUwO0JuoUuBhYdDDPq" +
                "TFvQT2gVaNVofcB4B/oVHQUZ1lLQ+V/AsvcA5ZjFsiKsMmvKGoHFWrLPYWsPzN8IbFACYoQdes0D6zyG7OwYRNH5YLPhYL1W0GM6" +
                "dYEXeMlzco38Q06QA8Anm8hasoosJ6vJFtjbTv4kR8hZuP6YZBGZxIH31aE96ViQYRswznNKaBKM1ZeNB+Y8CvmGzooA8/SAeLoG" +
                "4tAF9AxlIwHH44ow84acTUZCm4B/AD9YjbeApi/iq/g2xzTDyUIjoTtw/lKIAfeB62UhWiwt1gZm6QE4HiQOhK2v2A3wXA9skCZa" +
                "xALgnt+FxeAJLYTS4C1P8UG8EPyoLnbi98B+c1AvsL8VPYCcbD5Ey09ZOpPpI9DeRjoDkNwb9NoUdFELdFyGlqQZwAxVaQPQfGeI" +
                "AWPpDzDT9fQPuP8SfUjfAZtYWTQrBa0yYL0KZLi1WTPWmLWA7+qsDmQ3FVkKiwS9a8AFWfQJvUHPA+I3gf/MB/+ZBJbtQbvQtrQJ" +
                "jFqbVqZFYMwkmkIdNJImAqOlwn5xWhE8owVYfDA8sQBkPU7vgxWjWVmw7wDA9mbIKW8CuiMhZ2mBuqLxkB1sgszjBmQBAeTAmbgO" +
                "MHhnPBiPg+j2G6B6P3DsE9AzwW7I+isKn4CmxwhzANUHIW97LORBZI0BjigtVhDriJ+CzmuJZaAlAZZvQlw+AVj+ThgpNBZKChT8" +
                "Yzf+H/4Kf4KL4nzIAddBdtMFNO1GWZAbrQbm7gOYTAZN5IHmdtI1wLs/AXr70a+h9afDYG8oHQk6/o7j+QRwzCnA6CuaDWuHEHUA" +
                "b5SGrQr7hNVin4F2G3NGaQTYbg0MU4M1B11kgDVLw2ccS4CRogDvacwBo2aCTzjhXCbYKYP3VA3stRrw2R+QWQeXxR2E/UIF+56k" +
                "gYkxkRWc61zrInpYprJr2gVxsHsXStUfkdbBXgU1/Yvyyxd873vsq+TplfP+bdusjvlb/V2DK3w1/ef9FwO56mB8C0bYgTuL/1hn" +
                "OOc4yjmoY6q9a0RVt9fFXL85qaOnq2PEIEdL+2trBdstW0Xbcst88SosBufgV/gBnUKuGpvIUnqNfCBzjGeahXxgTLBZ7liSHU9c" +
                "090Z7k3uP12rXJ+7UqIPu885XPaN0m1JkaLxLvJGG2hMpjNIYxqrl1Q2qxnqAK259p8yWDmn7tK7G3P1H7QD2hz9P32zcUhPU0co" +
                "B2RD/kauH1jiu+K94Un1lC5okD8/97Oc5Tkdc8vnjslZk7Mtq3jOqJwPWZ2yl2aXzV6ct8pTqqBxgT0/OX9L3sbcWh/+fu94M/ld" +
                "Q+1K3OUarepKdW5UOlHp1wrryuZlzsvsV7ZjpXDV3Erd0kPOrrSZWtRYor3Xj5In7IQwEr8ifyhWldEJjo7xDYtcKnq96Lv0T9LK" +
                "J/0bU9OtRf4bjWNuulc6+uHu6uHAHW8Vj+hZ7HOHH2iDDR0y0t/1VnQUvmAr6W4bm5Y4M/F0gj3WcM9zXrOOtEwQFXydjtSi5cOB" +
                "tb4Vvur+r4NF5dNaTZLIeoLdj7Gb6JbQWvpU6ifcRdupoe9V98gVw3ND3pAQzgxd8i/2divYmr8oz54fzn/pKe6T/bf8430zfBP8" +
                "B4Lfh4fLMaE6oazg+4Al6A408pX21st/k0fzL+fi3JrZc3PK59TILSZbpSynL71F2eFVL1RtVmpQyZfp3eI7Rme670a+cay1nMey" +
                "Sr2jc5pkd8uP9c2RU5FHEiJGRWVGr4kdEPdL4tbUMmkVizYv4kzsFpVnxeh3eaL/H99w3zR/Wmh02CeH5S3KK6MSSpUeOyZEzY+/" +
                "GfdN3JPYle65jrHWh9Yn+A15oY3Q3+oJyi75pPKH0l5bRgbhf4Stwh2pg6WbZS1kWz3ET4X5gkXYgEbTV0Y82a330U6qD9Xmaiv1" +
                "nNJanhDqGnwf/F8oGPo8HAx3lk+Ed8rrw1tCz0JZYavaX7GHpwVwYKC3mcfIq51nzQvmt/F28Q/3jvRc8cz2VvHV877Nn1/QJjfB" +
                "P1PyuJ4mp2SuqHa/bmSVPSVapHWMrSjdoD60jpzRm2iZRrRuDX7jO+I3wt+SKpZp7syExSmt0v9Ks6dUSnDGLHNlObc6FtlkYQd5" +
                "oK0If+Yb6znglYKT5d30nGWWgzpnufq4/a4tjl6OHc5jzlv2+tb3UiJqzpqgZ+ovodX+G/Jicpq2wlfFeVIN6xF7OccHay/pgnhN" +
                "OiENESi7RR6RGRBZo+gGY4wxhXSib4wG2v+UQXI3JVl1q9WUAUqC+q02Uy+ub9duq2eV9/Kr8O5gmeD44NxAh+AI/2FvUc+4gh0e" +
                "3dPJd8C3xH82MNyf7d/h6ehLCnzrtfqLFLzxWAs65R8hb+yvYnuUvFLt5wZNa/6TWSXjYnS2ME37L1xVmR3G+h18GnmNFLWa2lsb" +
                "TLqKMyK+iuuR2jNjfUal9IUJFdyrbVNwBLphSJD1tzXijCrKjPCr8ILwP2o59q0lwTnOFRO9J6aSe5HDYZspPUFNSdioTbfTwegW" +
                "vk4mqr8pQW288ZK8w+OlZNt/9oUOyZ4qHmWYboX1yjekBtuNtuAWwkocYqPIG7260Uvvp/2kllYj1SfAKj3UBuperZR+zbATL8jg" +
                "0J4qA+XqweSA4J8cTAhtCNQJfgiU9HfzvvI3C8jBisH1oYzwmeCbUP2APdDFN62A5Ex6v/7dr3nTqBAxIa1Y1dN1Pm1orXw9s0aR" +
                "FClfbeM94RsX7Ba4xCri6awxfa8/IFWNCnSzpacbJf6Z1qf49GLHU4bFrHF8i6dp0+R9oUbKca26sZsc19cZPxgNjMPMIXxj+y1i" +
                "tvu7mC+jfZEOp1vqiNYZlwwHqsUa0S2kMu1Fj0KO/5A8p8XwY3GjdaGlhWgTGmMJ3YM8qAWLQqPxKkHDn+K/IYcipIqhaIp6TP1b" +
                "Daq11TaKR/GoNfSfDMP4kubQEmSsfkqpHp4aaO2vGvifv23AGnCEckMotD4w2X/E/9r7yGvz5/pWBBYEmwf/CWzIm5w1/f3vb5Z5" +
                "bulJroYlHDVuf+qpd7NiSuatWMJG+u7knMivVbAp9AwVSF9Jz2CVthxb2AW0yLYx6vekEen+jNLFBiSF3Tcs9wmS6/v7+heGbqh9" +
                "SH/mR6XxWOTCOcItqYdtr/OM+2wUdU9xnbK9E36C7GMPzdbm6ieMtpD3ZrLmeAjqD/mZgDcL89A99jdqgin6FE1DMyBrsuHvcE9U" +
                "lO2gjehB0t/4XD+kEdDAXLWM2lnNV56qSP9HH2+UNG7qv2tnlEB4WsgfHBbsH9we3BbUg5cDM/0/+ed507weTy/fucCMQJPAdV8d" +
                "77K8u1lZbz57nxM4KCmxVzK8NZIafl2nUdnfUlPd+/XRBeNyy+bn+Q6GvmO1rU+k80IKUjAW/xN/cO6IeZy8OD1cdEZ62YTLEX+I" +
                "iaS+4gg+DLjlH7VcI4PWhPi/kvZEJaVS9hjn6Ahv1JSY9OijrsP2s9I1Q1OLayn6M10i0/Fcqb30RGiIn9KVIP+vZAisDQ+iGuLX" +
                "0vfiXKEefgxWr4tWolNsI51K6pDmepyqyXvDVeQkJaic1LB+Q99vdDHKGwPVHfLfob9DqeGXwYWhGiFP8FTwbOh+oJzX5TmU/4X3" +
                "S58YqKMUC27zTS44mtXy/a0X5969Vc9Y3yXXLde9zsqGR6v4Sn5IXCeVkesX3PMMC5YJtTQEYYRQDpXRexrb2W2Ua58e8y6lR7GH" +
                "RVOKfUj2u99ZA2wbGa69kkP6BeODXlN/rFbQn6p+bSKeZpnpKBa5MPphbFRUmquk87E0gPU3Dhvd6W/kA52Fx0Dk7YyWsRH0N+Os" +
                "rhr1YdVUkTVHJSBDHAur0Daw/ryFe+PH6AIbSpuRpvputYPSVW4jN5IrKveUgFpEr63/pR/Ur2vblPHhvNClYM/g5tDT0PPg5eDL" +
                "0Lvg0MBJX3HvHq/N+52vhG93QeWCNTljs93vrmYVhHOkvbFN019XKl+rXNXymfNSGrmLkPfBSqGE8P5wPfkNEdARI1/9QntOTwvF" +
                "HOGYF8nnimhFItNrJE+Lxvbz4hhxMawcI6hEX+mzlDYQg/opzXQ3qmZNd3ztyoxZF0vcVVzzbeOlmmg3GYcms1uoKo6BNcEF9hry" +
                "6CqkBHlu/EZ+ps+IQfbCaiePDmUG6iH0FIaLDP+IgvQfI6R30oaqaWp5pbVyXb4qz1NOKeeB/75W66uH5Irym5AvODbYLpgVKAhM" +
                "D+4GzisZ3BAY4T/muV3wMK9K/uG8b3PCOb2yi+UwOUf6JrpSsl7ir6q+Sq5MW/JeV5rUTL+lT9ODynmllDaIfK93lg+FM4yfxBIR" +
                "02IqJaWk5ifPSdmXWC3mcER7Wy37LusIsSleSarp/yo2ubh8U96q/cYOSfPtMyMaR52Luh75v4gG9sfW0eJj4XuhunACT0dH0H7W" +
                "gI6G2brJOHKBiLQGfUIYcdBYWGV/zj5DacBsafgX9BnS6QKyztC1reonSrryQC4ht5D3yz2VJXJ5eWB4RviH8M/hSuGu4dmhIsES" +
                "wVGBDv5nvrm+Ut5t3k0Fhz3X/KJnXX6bbN+HTdmVguctCe6YhAvps8v/UmF/0Qfxj1zf2GT0Es0Vt9FJWmt1nTZf/SRYKnhEXyHN" +
                "dc6MGh+3Pz42bm3skPiOsZciUyMfupe7d9kvCtHsgvpI1uSmmqql6hJrIKRKh0SrtY2rYeSv7n0u4hziaG4RpPvCVrwc1pVTcFfc" +
                "jT7Sc7XS+mU904indWEFGs98dCGtRqLofFYaz8YN8GVY91WmmtHLGKKtV5uqlxVRSQgvCOVA5jc+PCoUFVobsoamB+ODhwOjgkuC" +
                "1UObgsSX4PvSu9N7xSd7d3g8+XXy1+Rfz6mY118baRkdGZv4WamiFepn1It3RhnulvbH0i+2F7bFwjj9hTZeEQITvAflVKGmtb+9" +
                "okt3H4p8GvFJVI/YRrFrYn9L+DZxl3u7tajQmXn109ox40/yUp+kTzM2GEeMtvSppVbEpsg4d8sIZ0SBrYot1ha2Ztp8lgWWZeJk" +
                "1ln/Ud2grgYOek5G0L4kaNiN5cYtQ6LNUUdYmy3BmUwnr0gLUoHOIq+NXL2ldlSpH/401D9UMSSHXgcH+tf7G/su+IK+Wv7fgjmB" +
                "2aE/A8t8k7yjPO0gp33peez93UMLiuY9yfout502z/Ig6q+kHRmB4g1T1NjImPyo164k18WopZFjpDlGKeVDaFbon1AN4x/cWJwv" +
                "bpVmWIZKKy1VIn+Ie5bYMyknYW/cnkjDvsmObcmWSOmNsAKN0+vJ68JH5efaZ1Rndku89Xvn0YizljW2DNtq1y53x8inzmI2m9RY" +
                "+AGVZqfJaH0TqWkM0/cqZ8In1W36bJLBptC1pBis3hujEbQ0G4h2I4y3kYn6ZM2nlAALV1ML5P6hD4E0f7J3vzffU+Ct4d/sPent" +
                "7NnjneM74tvgu+od5DsemByUgse8Mfn384rl9Qm3EXrb2rumxH9bpGryHPcjV4nYk7G942fHHYs943iPotjXzEW9xhfEibvj2aQo" +
                "aL0fbSj8z9bB3dONI72R/aJaRU11V4+aF53qdrt+dGyVcvFQ9ozsIwfIRrLL8OqXaBpKRDXwasFuWWAda4tyxDru2okdOSbYU6wB" +
                "caXwGr1mWfRvcsAYqM/WhmoltfvqIC1eH2y80p3GMeOysZ9kA3sdoxeNqUYbQ9ED+nvNo3qULeElodeBmYH4wDjffK/fc7fgSsHX" +
                "nq6eMZ73BW89Hk8db1dvDY/Vt9dz19OrIMfTmlSQllvGgQFGJW+NHe16FPNzct2khonzErTYCY5e0jzbELsoZbA3bCDOMdbJb9Rk" +
                "cosco3XE2pbrQgepqHOyu4O7oftdtB7Dot5ENo48HdHO6XI+szeyDEZ3yXGIrRtJczKABVhFelefZTxlKcIxaYh1nfWiVETaYTlp" +
                "HWc7bF1mTbZUErNRkMWwfJpPB5ILelm1r3JXeaPW096qlbQS2lF1oKqoM7RRehf9T32nfkebr9XT1mnp2njVqfwUnhKqEmwQ2BG4" +
                "5y/rT/cjbzvvNk9PT2NvGe8TbzFPu/wzee/zyuVfzLUXrFW7s6r4jGNL1MzoZY6/I2qkrC96O+1G8o4UZ+LJ6B9j8uLnxeZH9LUF" +
                "LFPFdOMLfRIz6BI9XSmmUqW4eoD2FOOkBaJk+cKe6mznnBfZLGpD1K9RJyMnRg5ylXHtjfjKucf2QsoQ5uNrYMlUdJI42DT6XB2v" +
                "alo2ncSu0UV0EZlDPew0riDeF+bgWKG3oAh5eD8+KlTAQfoVMHbI+NE4b6QZg9V9Sp4SUk4DX1WW4+Sf5S/lr+Xh8ln5mTxajlde" +
                "QZQaovwJESVGmSFPlWvLHcMo7A4fCVYIvvC39f3rfQX2f5wn5kblbMmu57EqV5WVmiDa7Ydsr3BN56CkX1N2Jf+QdCNpYVLt5JZp" +
                "/Ys+SK4Zr8U0iWkbFXa0czR1rra40UGIjOP0h9pQgo3Jsk3ep26EVWMkjUXfoZt4sfQYfPdPR9eIBa4PEZkRq527Izq7Z0budujW" +
                "s9ZWtjbWbeIBdBlp7KIxWz2hLAR7FlWfykXCLHRMHq11gtXnGbWt3o7cpBfYr2gm/hareLJQV2gl7IEIvhWfYhdoU+qgP5Mxej6s" +
                "yeYq/ZUe8sbwuVCX0L3Q3lBsyBVaHKoVvhwOhMuGreFf5MXKSflPeY4SkGfLWjgi/Gewb/BwsFHwhm+2J7LA6xsE65oaegthnVBA" +
                "ZuqfOW7E/xudHLkxql/sirh7iY3SJqcnJEckHE08n/RFYm7clYQ38Q8illv72v6wF7OmizdwY6O//Eypo+/XFsjx4XbheaGG4Wxl" +
                "ulbTKEauGedJNr2Poy2xtle2n62VbavtXvtRx1RnsjPfuld6Lt21DLacw0voOLKSqEaqflgtCRlGqjpLWSGH1T56tFpLtep28jVd" +
                "Tk/SUXQ2+x7niLfEAXgvXi5sENxibfGoUEvYiy7R71gXNoJ0J7cNj1Zc/Vltob1Xyihb5WZyPzkrPFueKe+TReUP+ahcXkFKhBKv" +
                "NlDuyrOU+cotpa28IrxYPh/qGjoakmG9UizQRitKLhh7zf8AkYnkT2uXmHfuqvb7zvZRVWK8cV8makndEqyxg+LqJpyJHx13LE6M" +
                "dUR4rMWdayI62DtIlfFm2twIGJ9SbIxRtPDFsCX8fZjIk5Q82RteEm6ovNa6GeeMVmQmuUyusL7CZHG6uFa8LP4g7bM0skZb51t+" +
                "sHxt+U56Lw6Qekh+4SZ+C1F+GLA7hbXYZqKDbnvQHBJHehpPjK0Q576naewJvUPfsKP4K3wWncS3hENCc+EvIU2ME38TNgr9BZew" +
                "BpfB91FzdIJhdgWQtM84Zdw2ftMXa9u1MGg+Wn2olFCSlE+VSKW/PEEuA3uj5CeyS+moLFA6KSsVl5oFOoxQ0iBXmh0+FXoU+ivw" +
                "ZaCPWoPM1tuEj2obUD86w1iHF9nnW6fjKpafIlIiS0dOcteIrhk1xj0kxpqwNK549KGo1TF3oka5yrjTop46Y2y9rVusByUsThFW" +
                "sHvgZc/1ofocrYRaTNHlPPmgXEv5Xl2vLlZWqmP1QUYvsoZugtUJQWOEXHG91MdyzvLUMhayg522Z7YZtrq2FNtTawmb3XbI0lq6" +
                "Jeqg3R+Fdngi+oeZMTORXiJPDIexWj+mX9An6+P1MXqBtkLX9PHkIilFbpLPIYNoyxzoFv5DOiI1F34Sh0ojxCFSK2hVpP7CdvDJ" +
                "03g7CqMraC4pbxwmySRO/05LVq8BAzWW/yd/Ge4RfhS6GGwe2hZaEfoltCH0F2RX/cOv5LxQdmit/EoJyUPDD8IjlarqHqWl/DD8" +
                "bzhGHiG3CHuCnYOzAlFKRYj1L+R+6jK6hp0g01GybastUupoWR3x1M3cNGpDzP9i7bFr4qYklkk+lHA77mV89YSC2Aqx1+JYzLzI" +
                "oRHhCI9zI/Dbr5Z5AsYjUUt2hm4mG3VVFSCaGMo2VVX/UI7LF5U4rT3kmSv0mbpHL0ZkYmX10Tq0CKXgCUKSOEGsIk2QmkgDpV3S" +
                "bUm0bJKQ1Fc8AzpoI2zAu9EdFqb16WsykswwduuV9enaDXW/+lgdqmYpWcrvyhGlrfpKfaIu0obosbqsjTNa0Jf0NnWyJoDSKpaD" +
                "UgnLQ1uc9Yxlp01y7ol4bW9vHW4/YV9jX2W/Y11vrW8ZLUaJH8S64jxUjh2hfxtNjLbkd/2cukTxAjYeyy/lTuGzISnsDn0RmhIu" +
                "Bfns0tDEkDXsCiO5udwmnBs+Fi4uZ8lblHilj1xXOaQ8VpjSUR2iZqhP5JXKe8jLXVquMl9N1tfrZVk73Aefpn3IbeGebZrtuqWf" +
                "bafjg3Obc7fr0+hPYmZEN416GnUg5mrszLglcfNi4qN6RXWLfhZV2r3V1S3iG8c02zRrvPUXy2rxd/QP7Ujvkh/JXmOAnqXegKjj" +
                "VIupvSGGTlKaKUuVDeptbTLovaUe0iuSEtQKOUJttAI9RuvwEWG5aIiqeEHEgLqtUkCyWkZJV8TvxWnQmoj/gD/XxQch9rRE0WgA" +
                "W0wNchjYqhusejYZJYxf9d/0jfpJ/Q99ot4c1qwT9UN6BeMzY6Ax1FhheIzOkD/Vof1pW/qM/kdHsgmsBhakOSgVD8WXhPLSOaG6" +
                "+KO0VmgkiFIf8ZW0WGohDBB+EEYLYaGE+Cl+ypaydugbdokW0J/IAwMZP+vL9BrGbL202lHtChx8R/1CfausVzYqc5TdymL1igr5" +
                "jNpO9ah9NKJ9ovfUyuiD9Rz9OMi1yqhiTDe6GxVIOeMErMvfGO3JAGMR+R8agTLpNHpMeCZGCbPwXamErYu1p+VXW2/naSdxLHJ+" +
                "7artGuPCkZ9Fdot8HVHUNd71LqKPU3VUcp6yj7dlWEOWtZa60l5hIp6IJ6AybBcVaQ+y1ihuLNU76q10RTurzdC+1f4GmQboX0CE" +
                "r258b9wzqpFcMp4epD3YL6whOo/a4/X4Hv5GmCe8EJqJ48U54g/if5DrzxIXiF+IZcXbwndCupCDN+Iw+hlVQ7+ziuwk7QVZzCRi" +
                "IRONDkZYP6V/o1fQLfoV7Y32WsvXsjRDq6p30tfob/Wqxg/GJaMEGUrukHJ0Hn1IU9g37DLLQAPRH+gFysRf4Dn4In6OHUKaUFv4" +
                "TBgsdBQmCy2FqUJ5oayYakViB7QdGeJBazNpFJqBt4obxBTxIQ4IS8R+uCbqiJOFssJwfIA9o4eogLJYfdaZXjWKk9tkEYGsBKLw" +
                "3/pRfZ1+zKhPOhgzgYVjjT8gv+5rzDNKG02NaRAvypNMUpHsMJYZVUlH/obHfGM8nO1LEulhY5Zx2pgLLdO4oY8wruo+XSUvSR8j" +
                "yXhKfkR30Cn6mspotnhUHCIkSJuB92dZq9jG2hs4v3QecSQ6OzsvOdtEWCJOOd84HI4tjjjHTdtS211rtuUvaRVEMj/MfQ4qCRny" +
                "n3Q6SSXbjEf6XN2rddM2a+e1xVoDLUetrk3Xnmsz9K/0Djo2Rhl3jRUkApB+l5ZjK1hlFI8n4UH4HE4UOgn3QXNFxEpiAvQcKZaE" +
                "vVvCWKGqcAvvxNfxKPwA8BcDq+YjbBhLYVfpHFqOviWMqOQY+Zl0JZ3Jt7Cm+YucIyfJVeDOxnQdPU+fUzurzAaxE8zHyqP2qA3q" +
                "hRZCrPiAfEgG776GHqK3KAvdQ7+hQXC1IaqPktBTtpeNYW3ZZ/BsNKx+dvF3M6ZCG08/p6VoCVqP9qFD6DD4HA850UH6jkYCypqx" +
                "rmwBIMXF30AbgVaiCwjhergT7oa/xpPxOnwan8T/4KN4F96CN+BtcOYn3BVXwak4ERP0DO1AsyC7aIcaoAxE2Gt2nG2EHqcCJwyE" +
                "3ouzOJbIirIEFsFk6qVBKrBYlsbSWRKLZ6VZE9aDDWB9WU/WB56YBTreyjazbfD5B0j1gL1gj9gNdpdlM5mZb7kiZOHvc9ZDbVFp" +
                "2JqjFmg8mobmo1XQdqKD6C7IVACakpGAI3FxXA1XhsylJM7AZWG/PC6G3diBgygX7noFet0Pz81CU9EE9CXooDx/gzCfv7e2ic1l" +
                "37OxIGF31oHVY6VgJqkge1EWxWzMCi2GlYBWBXRehzWFWQxnE2Huy+DJI/xtQIWZ7+LGo1KoLmoFXNsOdUTdYZxuaBTqicag1mDP" +
                "ceJ/qAWwxHtcVooVKrAAGi10F2bj4cgl/CqMQmXQTygWvFdndVln1poSeph+SwmJMhYZDclkI9J4os/Wr+ml9KtqTeDsTP2M1li7" +
                "qJUy/tNP662Mf4zxZCEgrAzEhwy0CUZ8gE8LTcWi0ifSH5Ld0svyuXWhtY91g3W7tROsKztZG1j7WWdZ8qV5UmUJS8PF00Kk0BM/" +
                "A+mDbA1kf93pv+Q7Up+UJ/eNo8YYo4hR1ShjTDL+NOYYG8DLg7C+v0sS6Bd0CeTeldlulgbzXY5uoXzUDG/Ht7BVSBVEgeJUoY7Q" +
                "RKgvYOEcXgRoG4Fb4tL4PzQSfYqikAHWX8fage/k0yv0Hr1A/6AzYW3Xmw6iqwHh++lROPOEJoB9pgNyDrKrjIImf0Bb0BFYLV1B" +
                "fpSMa+J2+FOwfCmcjkvAZxGcDVdXo8mA2k6oBtLZefYrWHkwjJQJGL1M/6Yb6Cq6hi4F7xlNv4TtVzizF/R+nF6lAZoJaBjFJrHZ" +
                "7C/AZTwgsBfY8ie0FJ1DHuTGUdiFo3EyjFUH18BpgLcQeoMeoRsw7m/oG8BBZZSGEmB+NwDly9g0QM1I4IgvwF+qgZUbAZbaga3b" +
                "sU7sK/Ck3+Cu4+wSeEE+c4KX1UaNAfmdYNRxEMFno+9h9MHoa9i+RJ3BI6qiOP5Grcpy2T12ku1hW9gGaGvYQsDzDDaTzYP4ux50" +
                "a76TeQq0bL5FHAMobQgoHQy+tAy8+gA6jm4C6wRQCAVh88NnAVjwBbqETsL1JTDyONQX1UHFkIAKoJdzMM5PbCisplqwGpAtJzDE" +
                "PkC+9pQ+oq8gU7gJLPqaeiil8awYeFMjmOEwkGYV+5OdZU9ZkEWADPVhbv1hRnPRZi7DecDNfUDtO+A/L0jwHuWg1yDFc/DzW+g0" +
                "OgZxaxNIvAB9CxbtByhthT4Buxbj74Cb77GHQQ+P2X12k11ht9m/4NknwT8PskPsMIy8C/Szje2EbRv47SbQ0grQ1Sr4XgU6WsVW" +
                "w94a0NUqOLsarm9jvwOif2cHoB1iR9kZsIz5/u91GOMVy+JvI4vAIubbx8VQBVQLNNQANQKpuqOvQL6uYLcvoU0EnxiNxqKhgPav" +
                "0BCw3zA0HGY+gL8n3g64oQnooiZgpQpwQBqKRQ7gPrPyIZflsXcw3g3A+3mYyzGYzQGQaQ+w5h6w6Sq2Euawmn9ugDmshbN72T6Y" +
                "7Wmw9lWQ9Sn0kQ9ymu8mp6MSqCxgqibI2AzQ0xPk64m+gM9eYN0+gKj2/F3tZsBhNVElVI6/KW5WcxBePfAMdHoJ7HeS/Q063Qdy" +
                "7AOtFn4fhM+/4coJuOMOsPlLXmOgciZ3wNhxoKFSwOPVoOc6MNv66DPUFHy/KRzVBBxXhblnAuJTUBFUHKKcG1jBihCv3xDg26zH" +
                "CIAUXl65QMDSEujHrArQmVnbYUYKN3/T28ErQmJQUWjmG+dxgA2zZsVgGIVBqjyYyQf2BnzsJvsPtHoGpD7+cUZ72H6w9J+gw/3Q" +
                "DoAeD/P30a8Bw+fAkx7oA4FkydBzKdBlLZhHI/Clxh/5viPYvDOgui3Xb1NodWF+9UCX5VFFeCYS5mSFOemAHbMi4hXo6QV7Ana6" +
                "Czp7wB5yWxe+ZX4ftH0HYuITuOMNoMCs3sjj9RIhXoNRWGkh8tlZed2Ck+varGIw610ksJvCEEdSmNeGBJnG60JUXtui8JiF4T6z" +
                "lsesgzCPTOTFwLELmllREQc6NStXTD3GcjvGcns64DkRmdVLZr2QWecR5v5nvkfvhc/3HC/5cO4BHGvAni9h3ByYiTlCDpzzw3E0" +
                "Zy8Lr9pI4GOkgPXMv/pG88qeEvz9fVOGkvzd/ng4nwQSJPCKGieXgjBzhgIyq09kXjFCeF1LgNcamdoK8qopH68yeQv7MmjeRGce" +
                "bKZEOdCyuG5NjJmVSGFW2AflNT06M+uZHDC2xGtGzIoeN2gpjnu+KbspdRKvQUngtSZxvEIhEvZS+PkoXgVl5VUIbi63C5mVMwqw" +
                "scwrtwrxbWI7DyQ0K2RMGf1czkJdmVpVebWCWW2kMsy1b9ZuWbnVKa+7QnwExpy86sgcyxw7gle8xHApi/B6iSSu73iQNI3bNAKO" +
                "UmDPtIMN2flcC/Vrhd7MqrAQt7MHNOUD+V5xBszi32a1xGPA7iNoDwG1L/nRO9D1C9h8vIItD5rKG+XYE0AD8R+rpKJBpnQYOQOQ" +
                "kAE6aYnWMo2eY1+jXngLqgZeWRztBnz+BZHhFFtCj7DLIP8Bthz8zXxbXgQeKYdes0zocQ9oYyCwSXnobwWwZSNgF7OKKg8QaWaW" +
                "TWCEc6DhSGRqm7EScPUxSCiBDHaQRQV/KwBERUGT4T4T0Q5kYsgOkuXwWqkPfBZ+PnsPr5AyK2PMuqnCiiiNmfVfOksEjWrwdBLM" +
                "0AqfKWCP4oDnWNiPAT2bPmtWI/l5RZ+JgwKQMRsw8AFGLoDR8kGXb+HT1GOQn3vP8ZEL92gcmWbVnI1X4kXxOjsDNBsHvTs400TC" +
                "d2GtjPMjC4q8rslkscJ6tcJKLQEYIsir3kxvLqwgK/RpL8iTx1su3FHA+ScfNlNCD7dtLpfTw9kpl1eSFfajcy8qrG8srEZkHLMW" +
                "XgcngqwO/i1w5jZr22wcrQ6Q2pyNWeln6ioFWCCJ129FQ4vhiHHyisIIXmFn9mnOwqyoLGQ9s1qtsAbSrJP0g2Q+znamlCYjeHm1" +
                "nFk96eczMrd8mKcfmvaxko+ywopMzDVmem40Z4BY7knxXOZElMolSoYjk6UcXDIbrwEyvc3GeTme84RZj+jiz0Z99CpzhaPw+r/C" +
                "ikedo8jP2cdElJdLlQ/afQf7WZy5TF2HufbNukAP17eHmfxuZoAMzpmsZ/CawcIKQRuv1IvgscLCdV44emGcMCsbLXDV+FgLKnI9" +
                "WhHm9YYKSCVzPJvIlnkeYdYAapzrDa5nk5Fy4LoEyPfDsalh42Mdonnn/9ciqhwFhNsF84o7c/6IV4+KHIVWLkkkjGOyFOKxW4MI" +
                "kQY9mxV+5r2J3A4JcKeV872Na9kNM9SZjecJEujC9As/YML0RYnXUwq8MlHgfZhzK6woLYw9EjxvxuV4nnOY6IrjnlloM7OWy8ar" +
                "uUx0JsJVOxwVVh3G8CrBOM735vUoHjndvA4sks8mFnhNBz2YnmeykMQrcgNcS2aFpQCR8DHsW5CppWew9wrW1TbkA72bfFVYjRnk" +
                "kSqfx6YseM7zMV55AAchzst+nvn6OQt5uXUKazw9YKMAvzPI/bGwWk3l1cVhnj1pHDUGr9o1z0jI9ACECuuFzQivcUSFef2lwr3C" +
                "4PWf+sfsgfAYZHqJeZ85qwLoXeA5TohrXuP9Ez5qPo8kOo8BJoJU/oTKa5sNjiyFS6QwG7cp+Vi/XBjpdX7NtKfJv4UVxJTPTOG1" +
                "z+xjrTD5mBlFcaSInCUQr3rWmB0VskUh2xRauZAd7bx+OBquxHMEuLmdTQaN5cgozPUTuK/H8+yyOGS16RA5orh3x8KVeO7v8Zyd" +
                "4vhfGCy8EtnBPcr4GLXN+KxzyyqgVY3nGurH2m31I9+H2f/XDsuAhKeAixye3ZjxMwuOnsM9ZiQy8azzKtoCzr6Fcw1yfZs+YvC6" +
                "Xgev7RU5m0kfq6EFXgFt54xVWA9u4fhn3GNNa5j1thpHlfrR500LeLi8Pl6tHubZuIlJM8aHec6k8kwzwFFj1lD7OLMp0IvCe6C8" +
                "Lw+cM58srBDO+Zjr+Dgf5/H6YBPVpkZCfOQAZxnTwjEgp+lPds4cdh79EGc1M8rYeJSzct3LnLFMFte55iivf1f5/Aorq020KB+r" +
                "sM27rJwVLajQM0zMuD7yhsQ1Zmougvu3g2dITs5Zdo6zSL4X+fFcYS6uMpN9zWis8ir/wgwygjMy5jHazaOzWZnv4riUEObZrIXH" +
                "aDO7My2JOI+oHB/mp8DXu8LH6G0+F8+Z2w5jxUKOk8RHlXi1u3mOMTNLLQq6Q7zePolHLiuPxw6el4rcwzAKcgtZODuafBDB46vB" +
                "a39NtDl5vCqsfDftUog3nXO//6O2TUYxvbvQ2pTfH/qY+RXms9mc2Xw8p8nia5vC3zgwmbHQTjLHYIBHQpXnXeaK+D0rzFRMns/m" +
                "HIZ4Bb/ykVvNKGRGwiI8HzCPTG738btsXLMh/psEFq5ng//mgcAjcWHMNDXr4PmIiXrjYwTFoJkgZxyVc5zEI0YeuwUZbxbP9gwe" +
                "dQ2eqfuZxFFZyJqMuXhWYuG1/RbOlSZ+C7M+Edb+vWGdmAj+6QI2SQD9v4UocI/9C2tpswb6MrsIa9Ht7Hd2lK8Ln8LVQm3JPLdM" +
                "5nxk5upp8Hx5VANW0iVQGb6OLscrkEvyrDOGs1Qiz7sjP65ATJQaH38bwuARpDBqyzzHfwvaNiNHPl/7foCjV3zPXIeHWPgjhxeu" +
                "iDDPLaL5Gi4eYmYi7KWDRBkgQRrf0gB/qZwtzV9riOHV9U6OPDv3A7MHU3cG54Qc7u8evuI3me0l6Pk9yGDmmD7+bcZH828bMvfl" +
                "IJ8B4zmfaeFCFrBwNjd14+S//mDl3unmWYPEfdbKfaRwNWbaOZ4zoZWv+TC3qsxji8FjXSH2Cn8dAfNo6Oc2Nu+2cxQizqx2jprC" +
                "2GPnGanOo5aJUQcfz2SjCJ452LlE8ZwHRJ4PSSiaZxlxfB1v4fI7OFNE8TlIXGem10v8uvDx70WIx2TTCwjPbujHDDjAV+BmRluY" +
                "X8ofPc3PubYw4zSP8+CuHL62MSN4kBX+hoPE52lG3MLf5TCYjY9m47GFcb+XeUQP81/L8HIP9fK8IcTzPsI5gXFuCPJ4FeDMK6JC" +
                "Hev8ivlrKIVsX/iLDzJf5eRxlrNylsvjqzEfn5MZ4XwcgwV8JIUV/t5IkMcSL+dIg/+6BeXRS+dPEc79Bv/NDtPi5u9sYO69hSsp" +
                "hTVFQdQVtWGIdYSVIAIPWgtnVqEXbBybzlazvyEjXY6OoX7oGXjlPR5zLeBzfVFF9I4dY1d5BLSBJ9YDz/RBJM/mHBkDmI9FhK/b" +
                "4sErzFV4GoxtRj3K0rnfVgILByGyvwPZUqC/svBpB8w9ghwxAONWRdXgGSvKBw54CSPbgc9rgW/ZkJk7PoDnVFYW+i4JyEHoFejP" +
                "jGzFgQFqAB98i8rgPnghfoM+Q8dZP3acfYPa4M34Mq6Le6FyLJWWo9m0KLLg1vg1usFm031kAYmkkawxcuN0nI6O079IC/IfuUhh" +
                "XYvq4augg9c0Fp6TaWXUFv+OR+LlaCm7TXdSLz3MfkV18Wj8AfDbmj2jNv6f4A+oCo7H+0DC79nnbCIw2CYURjqs7bsjD9vNdsHc" +
                "MtB4dBEdQj8Abz1m59gp8P0okLobaPlTwOMNtpKtB12/Ag8qBnMrC17znB2GsxuBNQkrjaqDFmzoPvQ2j/3KToJNyqDPUUvQVR67" +
                "xA4Co3rhTDc0EQ0HDarsPNj1P+Df+nCuH2oGen/HDrG/2BWwZEPUHjVHlYGfrwIfH2J3ADMl4KnWIF0Wuw96vAtMaUF14UxLsJAb" +
                "tPIcsKOCzNVQB/6/oJrgt2a2XxZ9gWag7egxsmKr0FZcJ46QikgbxB5itlBEYlIt6ye2ZNtya3+L03LEUt922T7E/s722qKIraVB" +
                "lpFSTbGK0A7vYZ8aY/WR+lXNru6Vp8i3whVC5YJrglpQD/YIWAOHA3n+3oHHga6hKeHi4d/Dg8KBUJNw2/BC+arSQK2kEdWrLlE3" +
                "KxXUfmqB+pe2X7PorbUD6h61j6qqO7Wq+i39gH5cP6vP0PsZI8hCdkDYKk21Lra57T7bVNsMe55zf+SP0UPjKiZsS3gar8Z2i9Vj" +
                "LsZWjf8tIT2paeLF+Iqxx6NKR5aN2Ozc67ztrO88bV9n9Yka+oVeNUoYUcYGfaVeTY/S5itqmIb2hdaEqoRfh6k8VFmqeJSgMkBt" +
                "pqZpZzSiR5NE+j2bhD7HFmGWUF/8QxwplbC4rbVtx2zd7Lvsa+0/2cvb79nWgFxNbU1sv1v9lj4WUUoTQ1gDOw1hZ6lGqOHXHfpb" +
                "NVdppvxP/lR+F54eLhJeExoeKhl6FXwULBP6MbQ/9CZ0PbQn1D5UEIwP7Q3poVnhneEZ8gL5eriCfDs0IXQxuCt4KTQiVDr8OlQv" +
                "1CxYJ/Cnv75/hz894PV/71/ve+lt658TuqJPxd/a7jvX2WqKLchC/Rs017Yk/l2xK6W/KDerVN2iBxLHxq6LZYnd0vsXv18yrniX" +
                "tGGJ5d19HQXiBtSMNaTH2FG0CHfBt9i/epvwdp+WX6agqu99qEB/QTeyTPpAuy3vCXeRK+lvWaT0h62G44Jjl+ONvbE90z7bvs7e" +
                "x7k6YqXrO/eqyM2RPZ2lrLOFkawr+wVtFMaLd4SB6D8jRq+ldlbfqifVUZqkTdUq6J2M00aWsV9fq/2ktdC7kurAQtfYabqLPDA6" +
                "G/v09nobrac6W5HD/4ZqBw/533iTPREFFQr2FngLzhR0LDidX6PgTf7D/KL59/L3en8MFAtNCzTwlsk/kXszd1xum5wtWZ0/VMtu" +
                "Fki2jEtIKrIp+d+43gkfUjalHU9fk7I5pSD9TKmDFUZU/KTs3xmzklZH3XVUsV2QXuHhZLXejCwD+8WoV305eQtzIwrq+kuHeit3" +
                "9c+QFVLUsH2B/bqtp62Bo27kvehhcaXjGsR9iDseVzlubtRwZxXrL+JAySPFW+4K1dEWckNfpbVTeoWbBCoFvgtOVwYZ7Wg6cMMx" +
                "PEisKb0W14q1pWOWBHsTxyP7EFtbSzfxDK6ARpEcLUPpJT+RZyuRSnp4YdAdfB0YH9jqn+Gr5UvwVfXn+ZcHNga6Bc75BwTGBGsF" +
                "uwZv+jdDq+hf7r1dIOWfzz2eszO7bnZW1pQPGe+XvCv1Nvvtruyfwv0c5eJbp45P+zqzX8WbFUtnChlr0xsUldOzM5aWOl46v3jb" +
                "Ip2TGsQluc7QV/5d+V3y5xfMKxhRkOM9FTBCRcMJcjl5R1gOXZQH0IClfUR399iYcUndilxN/SFpZdzOmGpR210VXEtdCa5yzhH2" +
                "I7ZsS1XcVxNDqq+nr7X3sSfD9ygQClfUvjSG0SAtzW6yU3iXNN1axPaNvX7E4sidkZGuLMcY2ylpHp7JPEZYc6qSEq10UDrKQ0N1" +
                "g28C1QP7/PN9xXx/+FL9xwM3gndClcLHQ+XD3cPHQj+FBgdu+3v6sf8L32NP44LO+X/kfsie9GHf2w6v3728/7z5q4tZn4f3W0Ou" +
                "Hon304eVrVKpR7nrJccWL5P+feqE5EOpP6d5imxKqZIaTA0ntHZdQOvUqqGfCxJy5A8tstML6vjOhWP0dPYLS2ctUXuhmthfmCoO" +
                "snd0T4rvn9QodXnav2nLU11J9+NaRibZmgpWiEEPyD59lr5W/8RYqmaHlwTDvnPeQ17VNzB4JFxZfUqOCc0sq62TrVbbNltz63eW" +
                "AvGimC3+Ko4Wvxctwn22nXh0qhaVZwUy/XH+HN+/vu2+g/7JgfmBc4GmgbKBC77hvpG+HN9m3xzfB1/I/21giP9P3zJP64ItuT9n" +
                "Z364+jbvdbt3D/N0xWX9ypmZ8CztYZn7FQKlL5V6VKxGkYKELbGO2IrRbyKz3BHxgYTeccz1t/2OtJlZ5FW+HnnjcnvnPSu4E/pB" +
                "zaJvhER7D9clV8Dpt023KTYNePmAw+u6Hb037kJi7URLwstoh2ut45LwlHRWEsI3w8/DJ+Ubyi7tkj7DSDNy9dvqRGW5fFDR1Ap6" +
                "BvmWXRSHWd7D3BdYrkifC/2QlRY1vtX+VCO1L7VobY1aTC2tpimx4b2Bs76Qt4on2rO84FrBLs8C72GfNdDdX8UX9N7wPCu4lHck" +
                "Z1G2Pbveh3vvz757lJfnX0bKWL93/pWUmf5V6S1lF2V2K/242PW0gkRXXGRUhHOM/ZLjTITF9Yeju+OxvZltt9ie+NTRwc/9sb6B" +
                "3sxA0+AstRjbZmnqjIkcGRUTOzhuVuy4aBb5KLJX5DBXiYhARKeIxxEVIjo7GjuuSRBR2UljjFZEHh46GBoWrigvlc8pKfpXxlNS" +
                "j/5HD9JHtDPbxDpA7uFCJfBh7MdxeBJ6zS7RoEG1CupkeXfoSPBaQPd/7l/mm+Lb6jvqPeF5UTCj4GU+yTNyu+RWzS3I6ZjTPOfL" +
                "nF+yt2QXfJiad8E3VtmNhostov9IapURU7pRJi5nLz2lxJP05UlXYv51XrAMghi/R9iGJwi9LXutdmtlURKKsN7GCnVdSAjVDsQE" +
                "j8qN9QXob2mbQ4yeGX8+kSStSXqZVDvhq5g1bp/zV3tbywkxURwsjMIKeoW74W9Rcxo2UgxVfSx3D/8X+hAKh66FzykNNcn4jmxn" +
                "w/A2/AIyu9bCOTwaLWHXaVdanNzR0yDmblZYeESoHuB/gddTkFjQKz+U+zZnY26n3Pk5+3PeZ33I/l92zIf1H469W5r9bX61wFg1" +
                "m4xxTInOTT1QbFfJQWVPlVle5kyJmGIXk+NjfBEWWwvpGdsImeJF9i9kcmH0lfAcMsjarJvxQnsqvwyLcin5C7WEHotslizHMHd8" +
                "bN9EX1J2spK0KWF13Gr30oiztrNiGu5B55HdpBrpTXRyly5jqawuPUC+M0brkdpyJUNZJa+SQ8pTdQwgdijdiWKFH4RrwhQhUyiO" +
                "e7L7xGVgrYXSJDwzuDZw3rfdO9kb66ngqVXwOv9+fv38xLyIXCn3Xfa2rGZZQ3Ma5c32zQo8U16gd5ZVkRdjaib9UkRLM9LLptdN" +
                "65ZcJr5/9OlI4jhgKSnNEJDYUcjGsvBOHCvuFB1iCWES+ooWI3v1jVontYTq1m8a/eksHGv51F7a1T3yUZQQ2zJmV/TQqG8ii0dM" +
                "tW2XbgjD0SKaQPYYs0km+ZzMojdoK/YZ62u+eUISjJH6L1qSVksV1AvqOK2bnmR8Q4pDNl8b3UFvUQuUyZ6RDsYt7Y3yIHw0NDJY" +
                "ITDA/x5YpoQ/whfrv+eL8q/3d/f97V8bKPCfgQgtqY2MTmQVHiQ1tv7oJBENIgdFl4qqFP08yhN5wX0yYqbziqOdbY7tmuU/qb+l" +
                "opQo7ZCOixHSOPGNUFS8iUfgnbDqSEe/MhdrjrzsIcrD6cJtcbT0XLpkWWctZm1t/cdSwpIntRXXg7XnoG+QhZ2nZ+ljEkur05fk" +
                "E3qLXCSf09lkBulLzhiJ5Aeji7HdmGLIBiI/ktq0KnijyDB7ThdSiW4mN41Gxv/0+1p/7ZJqU823nW8pD5XbylC1ktoXstXS2gy1" +
                "JMTvGcBgbYxf6Uy6lX2CT+K7Qh+pmLTWwiw7LZm2y5Z/rfOsfSyjrGMsRSzjLLOkLOmURMTDEN2Pim7pNmSQvwgKDuGquAl+h4ai" +
                "vWgqmoTOoK2oBnbB+mgNnoaP4v/wXPwXno0n40FYwHXwHTQLjYMVXTR6w86w62wO+44tZM1ZQ1jDJDEfDdC79DCdQw/RFXQu3UUv" +
                "0Fv0Ja3E2rBBwDdX+P/AdVjF7Gc/s6Ysm86jzekTMpXUI4px0phmLDBGGmONScZyY5VxzHhheAwXaUrGglc9IxIdRH+GCN2FrYG1" +
                "zXB0GlZjw/EenIeLCN2F8cICYZdwRDgunBHOCxeE3cIWYbUwQeggVBGwcBmvxuNwNZyDzqN5aAB/x7AzGoZ+hRXsS+QELTTAzXBj" +
                "+LbgV+hvNB1WK8mwvjzBtsC6aQ6bDWu2LbCyugpoNNfHMSiJ//UmFdZdJWCdWRFWOE1QD9QfjQD9jIS1U2NYFRWwP9l4lsFu0Om0" +
                "BvWSfYCCr0kr0o70IKPJInKAPCQCLUYb0z50DFhyEV1N99IzwM5BGs8asaEw0+uwKq4E0n6FRgO6hqIuqA6sfRmstMx3UK6yhyBP" +
                "KqwJx6H16DpSUCIuhxvh5jCbRvgTmHFZnImL49K4Eq6JPwM+6oJ7A2+2wrVwMtbRPfQnWgg9d0INUG3+xt4gNBOw8AgxlAl39cdj" +
                "8Xg8Bn+F20JfKbCSeISOoHWgw+H/V9NVgFdxtN2Z3b0SJx40lOJOgNK0SIECxYsX97YQWiguRVr4cGtwKMWluBUIbsEhENwtlACB" +
                "kFxbm5n/7NL/mWc2uzd7986Z933PzNw5Mxf1V5PEEQ9KsVSkiCQRQMln8na8PPw4G/a6i5yPcXMpe38la9+h9fCNE/CJi/wq/OQ+" +
                "z+YaD0XPpRb8o6sYLMaIaWKKmAyfGioGis6ii+goeqIWhqN/s01cF/kiDujbo7xTyCKylmxF+gcWPEmuYST5mhAaT6vQhsDYj/6E" +
                "kg+Gf3SnbVEbdZGSYeMWtBPG5lPpKnj1OXqfvqSPaCY9RTfhtb74fyz9QC6RTWQq6Q8PKEuCSBZGzYvF96KmkMVNvgk42vO6vDIv" +
                "wYsjV+S1eHPeiX/PU/hQPpqP5SP4eD4H9TADvr0QEbCfnwbS19whMNQRVUVdoLVUYK0xFukp+oiR4k+Mzvdi/Gx9Q1sao+x2ZDA+" +
                "fxpJJVvIbrIXfn4Ntf6KGEShhWgFIKxFG8OSzWDjZkDUmDZH6ki70P6w1RhE62K6lC4BpvX0T5zPwGs/0PbAX8TePeoibD6PDMEI" +
                "39pBKlucEavFRJSkjkgUQeI94vUWP8uP84M8Ddy0i2/jO+CV6/kavhZ5GV/F/8LZNr4T1jyC3sQlfo3ftdVuz/hz/oK/4wFORbAo" +
                "IsoBaXsxQExC9GxHPd5H5AsRhYipRZqR7mQYGY+SbATOk6j1m+QheQErvgTW5zhaerfnyP/i+h3JJflEQxbwcT+OAeIjHlzn4282" +
                "7rtLbpMMPCcNT/uLzCETyA/4hFbkS8Rmgv09nTVn+BhlOC8OoTSW5u13eNb3optoJhqLGqKyKAtfLAQeCxGCuwRHFHr5W9juKb8B" +
                "hLf4Y/4A+RXP5QY4Oxr3fiIqiWR4b1vxA3z2N/GX2CAOg+eewZqERIMZkm1NVEdwzo/ghtFA/D+yEGkZcO+xrXuIHCMXwEvp5Cw5" +
                "g+MZoDiNWM4g19Hu3UWM3iCZuL6A/5/H/cfwjiPw+73kANlJNpM1ZDmiYQb5FRzQG5/UDD5UxdYIWbPBr8UDsEQafGwF8I5FXHVF" +
                "eb8UFcWnIkFEouXRuI/nwHKW9e7zJ0jP+Ev+L14NcEWE464S8P56YPhOqK2fEZ3zEPGr4bNHxVmRYSvcLBWakxS0v2GrTRqBr9qB" +
                "CwciUkeREcA8HRZZQJaSFbDNSuDeh3yEHAUWC/VJ4DoHfBeB8op9vIarS0incc8hIN1ONpA/UWcLwU7jwYPWPhTNwXo1EaOFSSj4" +
                "8A0i6BL4eqtYjrZlIsrZU7RCqT8TFWwVs1tIwgAmP1B5kD7g3Av+EZzgf1EiRhSGNauK2qI+YrOrGIK6mir+EMts9eMBsNw18dCe" +
                "nSMkDLxn6RwtrE1Ia9IZLckgMhQt6AwyE2Vch1Z0N7CdAa7LsN8D+PAHeHEOks/uG6rECw+2jm/g8U/IHXjvTdTDXnjFElhzMtri" +
                "PnhuayAsRYqREFt1cB8ID4lNYqVYKMaDL/shur5B3FaHLRNhpyBh9SQCQJVn687fIPuRES7A6EQvpoQoJqqhThqJb+H3A8Uw+OxM" +
                "MNAW2PK8uIXWLdf+ht/SnpVCe9AALNgWcdQHnPQ/pOlkla053wcOOQ5sGbDfPTtWs4EwB4gM+8wHtJZm/6WN65Lt3Wlg63Xkb+Cb" +
                "hjQCDNsBnlKfJKEm422ljqXseSRuitOIoc1iFWp/OrhjqBgkuos2SC1hnTriczBoVcRqaVEGli0Cu5UFpmr2Xmp1gawJWg0Lm7V/" +
                "769oT+bDIyyV6CEgzMDzs9AHsb5lDwPGcuDAGqQh2r0OKM8glGo06j6VzEVMrUJk7SDbYJN98METwHva9sdj5DDawEOIvb/hlRvh" +
                "0YuRF6JupqLfMAY1Zelau6NNbQgPTcYnVAAPFLR1nKo9l/MYNX0BKNPAQ2vgYfPRv/gN/jYK9vhJ9Bc94H+dEW3fAnNrePG3ooOt" +
                "jW6Po6XM/w5skwIPHwkvmAiE88QSpKWI8X/EQXFOnBIXbSXlY1uRZO3SrdjzY5+AFyyftXabbQub9iE/IY0iv8Gyv9ueu9jmpjVk" +
                "Nay0HNG6DK/MR9wuRY0sILMRxVbLNMnuKw7Ce1OAtRtivSV65w3Rg6hAysBbP2oELG3RS/jsTZTmqK0c3mqvdJiNZK0yGA4EQ9A6" +
                "fA8/7ip6I15723+749qqhZ/EL6iR4bD/ODEBKOeinpaBd9ahV5UmTqLdOo14uGfvTmztiSzbc78J+Pzy6I0lgwVbAmVH9MV620rx" +
                "wWgThsPCk4FlHiJ1Fv7OR7aQpdrYPuZpqA3rziFAZ2mUe9gK5lakBfAlkcqwpTU7GWzPq1qKlOcoQ6a4ApSn/9vHeS18d4lIBUrL" +
                "rlNR+lFIP8JmvwDVUBxHoq8zAq+NtdNUMPMsWHEuInEV7LjF1i7vs21prQR5gPbkha0JCNh7PscBY3FS0tYQJ5OvbQ7qZOPsB5sM" +
                "QMmHwY8ngSl/Q5qDNA09pik4nww7T0JLMQoWHII7B+D+rqQL6qkDUkNEYx2gLG/v0FzQVutZM8KWpu25rc69jF7/QbDsPttzVwPp" +
                "YlhljphhY5iAPF6Mhq3GIVnXE5EnI08HvvlgLWuty5/IG21OtTTGx2DJTHDrA1sBnI326p2tUrPm1wvYuqHCYKIy4PkkcGEy2nML" +
                "bxNYozn5lnxnK8e7AXsvHPsBz/doZ/vDbik4+xmv9MHrHXBfW9z/NeLxS1v3XRI1aM0WJ9gqNKetT7BmoF6iHNfhVVdR82eRjthq" +
                "+Z1o1y1d/Fww0jzkGfDgP4BoLpDPBn/Ox3kqsM2B7ZYjrbL9dCM4bKvYgbqy1OpH8cRL4oatb34Ia+bYepGPGotIW3VqzVVXRM3X" +
                "tHXhli1aw4Pbg0esvc4thP2AqC+sbGnnB+KqP856wX6dgLELULZFjbRGamzzajKeZOnoy8Ff42z9bag9x/txd/zX4hWY8B5azRtA" +
                "e1ykgx2PwSaW/vtvW/ltrUhaCWtZVl6B89VAtdZeL2DtbL0dXpqGe/fBhifg+acRAVfwrIfImWDZV7Z+U7XVO9J/u5Mn2jPzlva+" +
                "Mqz5UX//FTzO4t+m6Le0tNXjzWHRdsDcwW5ZO8LS7Wx1fhtcd8CxJe5oAoR1gLAeMFr6/GqwaBlbo1bwP5WRNRus27Og1qx5Flji" +
                "IcZKtxBP1pqIEzZ7WCr9A0Bh7be+A4j+Ae7ddtprx5+FzVLE78PxmN2vS8dfy4oZsOJjW3n01v4dAe9/WvSPOn3rFxms3daLgHGt" +
                "3c4r2br+muQLO7rqkbrwxIa4qofUAHzc0E51kerh1br2yoDq8IUKSNXsVBH4LN23peVP/E9l9f/a9o86TY+tQMqx1ydYGkkL7QPk" +
                "67aS/vp/uC8in7F9Ox3pkq2xP28juoyUCW+4aavv7yPen9n6i6ewo6Vvfmerj/JsXQe1VRMff0vAUjckIllrFhJtK5S2d50vA8QV" +
                "bRQWR1m+WMPmZGtFxZe4+syO5dq4o6p9p2XHUvZTSuOJJW2Gs0bX8bYq+6Ouw4pS8Z+qz7AVEVko3b8o63179cANWOQhfPoOrjNt" +
                "3BlAZ61VuQbkV4DwvM01l+3d7DNtFckdnN2Av97De18B7ytbc/X6v1UWlmJMsWfsP64ECLO1jQm2FreYvY7D0paUQq4O1qxhH6v8" +
                "l2sAY3X4eQ17RUs1oKxsW7Qc/lfW/lvZjvjydq1F2inMVk1aqglmaww0e7XCe1v3+gzsZFn2EZK19/4NYLJ+FeAyEFnfBFyCVa/a" +
                "Nj4HTj1vR7S1XsZCmmn7/h1Y9xbuugI738NTntiK/zybi7z/qdks3ZzDVucWggVi7d8TibY1K4WBuQTKWwU2LG+3PuWAozTOLYtb" +
                "K1kaIYq/stfwNEQ0V8W1xUVW62St7OkG7voRfNWCWFp0q46qoIaagsMGoUVagP7WAYwADVKDJtHWNJ/ESSvoUamO3C1sV9Gi8Rvj" +
                "fk4IlWvkZr/Jypr/JDpnMokNDS+851MeVz8sX4p9W+L5tZfZz39+NVxPc30RWj96b8wYZ3mzSGDBm9/frvLV0F9KP0aPjl8QtTry" +
                "cpASaJ1b592d7JLvUrUOXJNDQn9w95Ya8198JGf020Pv53xwac3pQ6Wmc4vzBf3JnKJKns/ze/pPa1V5H/kz5z5HqhxJ+pufa4P9" +
                "tf2h2l4zQcTKXzq8crSUKnqaLfWz2q9atpEnIuQmymFXTVchx345VX5MitIguZOc6xjo3ho0N6h4cAP3PUeSY57yXEpWujvKOVTn" +
                "Xtci5xtHE6WH5KTD0UfpTYpLm6TFUkX5itSGniC7xCreCqPQOWI5iaS/SmOpH5HTnQ9nr1kC28tHw3dSxWGyWoSbhdgQ84MmmXfN" +
                "WWZj3ktU4u/M7eYNPVWrYKTrr/WdLJGH8I2sBhtttNGLG8e1bPUbvZpRQX9lNGEP9X/0PCNJd6oX1NWBRO9k/03/Ct8u/xj/e+9d" +
                "TeednZVDEyOWRQwLXxk1N7JZxN6IvwuUKBAZeSh6SNzZmK0RbcPuhJxzNXL0UTZJKWi/LtARVKKlSG3+hXnFbM8UrvOjYgkNUUo7" +
                "Yh2/Oooq0+Qa8m05Q7mkDFeeymOUYcobehP+EsensEtsKd/MiwD7VfNbM92orjfWA8YOsxs7LGZLJRxRygilqnJRSqfdSVs6n1Yg" +
                "nalLTpJkWlMUZaYRZzzUfzfuGLPMUJbPfuV/s5VmVyNEf6Vf0gsYX5phZnXWmi9iG80XRjv9C62qNkGvrm/XixjU3GF49F/0DuoJ" +
                "/0xftm+0PyZQVk1XG2p9tRZasJYS8Pg2+ALe6b4C/swAC6QE2gTK+aO9A/LGf+j9/kFO+5zYt/KbYW+a5jcmvQoMKfoqcWjJFyVq" +
                "Fs/4pEjhv+IfRF+O/CNmSkK3QrMT7hS47eoqBzMtUMoX8Fzz/eU/bL6WpslEaSpNpkukkspNJw8qH7o0Ijp6R/SCaHeBPcG1nRXk" +
                "7TSC1BXBvCybxVqxN8Z97WRglK+xb3qghF6IHREJstsZ62zr6KSky3Wl7YSI7+gsaS2dTCtLqbQBK2Zk6JX0gupk/SLbQopJi+Ri" +
                "jiypFu7tQteTULqKnpa2yHOV97KfHifJvDtrYNYwihvbjJdmCKtqJpuljafqHXWNmqkma4n6drMEM1hzXp+9Nsbp/XRNi9UOq83V" +
                "7MC/gWv+Yf5zvsfe1t5MTz9vuC/cN8Ib7ZvsO+n7xDfB08bzJp/k9fxw9/2p903e/fC2b86c102yM7PUF6Vynmqdw64XCirvrzn6" +
                "8zfVj5TvXM5ftHlE66D1Ic/DUoO3hcwNjXRVMgM+r2dl7tx3N3LrBm7xh85XYbsjN8ccj6Exs6PiortGj42uE/NJTFZ00QIFQw7L" +
                "vdkFNdobkt86f693kn+AesjU+GxejbcWb0hdqZ3jvvt/IaFhq8J7RKwP+yPovLJHRBiN1DaBu/79/jqBJuoi9XxgcWB9IAhHp+5n" +
                "a2g3JcTVMqhFcImQ2sHDQk6GbHY3ch6Wv5A70arsqvGTPl1NVE+pfbVJWrq6Qb9iVGHJvDCfJ5JJGn1Ko6Ub9DP6G8aSE8kQMY5n" +
                "mYPNXF3SLgU+9T/yJnglzwHPHc8uz06v6l3qH+4P9kcH5gUk7Z6mabWN19of6lf+0r50XyPfNK87b3tu43ef5vR70/l1i1fOVyte" +
                "Dn6V8qreq5SsiOx5/hJBCUUiSi6s8Wv937/aXmtehU/LSAl1Xd3lRGcFJU58KaJpXVbKm/Phcm6ZD996OquxUoXQsJiIQuWLFfok" +
                "PzGjULuCO+ODYgdFVY1YEnrc9ZIOMfv6k/M35XZ8J+dW8bQJZOkXeHd5oONrZ547PmR6SKvQXuGLIx6GvwwdEhzuLEn3szWGpJ/2" +
                "3/OU9Bz3FPcS79feNr5jvqFqmhHFl4v+0kMlw9nL9djVydlMWaqcl5tKt8VbvpGbZgfql06Rqvwr4z27r2/QE41Eo4IRw31iJt1E" +
                "I+kBWlpKU7LknXKcclfR5TXSOLKfjdSFeln7Ru0fmOvv6z/rv+ZPDwQHQgJL1YdauvErS2dfsTXmcfM3k+vttCnq20Cy2l7d5c/1" +
                "VvEcy++ePzf/97zM3B65fT+MyF+ddz73XU5uzqT3U/MXefL9s/yPPUc8Ps/V/HzPJaN0yKVCMxLnVuheI7NGk4pjii4t2DBSd+yT" +
                "UpWhruPKCNpCXkJmqTy/V/5Y/zhtJCvq2hDxKOZx/P8KRhXKjHsV9WNUYtSwyMyIimGVQuo6T4oYfZm3d56UOye3naeTP107Zk4R" +
                "FaT7kqxccvRz7wyeE3osfG5EfPjp4G3OqlIcO6b6fQe90V7Zu8/b1r9DDdYqaDvUfVoZ47lJiCklOUY6X7jWuP50/qD0lZ6T7mIM" +
                "X8vKssfmbCPEeKUNAa9l6IdZAxGsXHf3Cm7mnu1s5lyipNJjdLLUljakd6mPnhCHTKFv1F/o641zRmV2jfVmg83FxmJ9gzpfPapb" +
                "v8hznq3kl3ld9pN5wTitf6Kv15sbjY0p2tjAM1+W53PPjbzf8r/2rPFM9oX7M/0jfH19j2CX576EwD/qUTxlUqCK2tMX753oyfAM" +
                "9uj5m/PnejN8I73lPNc/DPAuZ47QPQnhiXfK7K2cUtksHVm8YdFtsWfCkyKiojoUuBzsdW10TOJ5/hH5KXnr8iK987SS5J7CnQlB" +
                "G0P2hH0TlhuWHHkkel1MldgTsa+j5oUmuQ5JRfhu7WBgVqBWoFDgt8C+AA1c8h3wjQsU0vebF8R+uZurZlBO0GfBoUGfugY53ivz" +
                "lK3yLGkK2ptv+F6jt9YucNBXzDfJd8V/ODBf7agvMOewU4zwwWIkUaTPlM2O75wRzhcOxdFTDpeE0HkKFyyNfcpOmE/099o+7bg2" +
                "QqtoDCc3lDHOUGcRx1ilorSFLqd1aAitSf+WoqRzYoJ5V1urqoG3gUPqLm2ZvlUXegd9kVopMCFQSeuoHzcCaBmrsTHsAavDgszC" +
                "5ncsm2WxP1kD1tZsb/QxthgVjbP69+bvbAj7jg3hP/NdvLSIxAi6FKlDf6ZL6VQ6mz5Gf26z6CfaiAf8NJ/CO3IXp5yxLWw4a8M+" +
                "YxVZDKvP+rE+7Az7nt8Xe+lauYdjmrO+65bT62jncDtuKSeUvx1nnYtcputH9x/uim6P66Lrimul6xfXfVcz90b3bfdB92T3HVcF" +
                "1xjnQ0cHx0GltxKr7JGLyKOlDzSZrsHY4KQYKb4Rg8RE8TtG2iORmojPRILw8If2b14M4bX4czYVPvzBPG2uMlebW8zd5knzsekx" +
                "C7MWbCHLZIV5P76Un+G3eRa/wQ/wObwbL80D7ALbhHcOwl1fAk9Z5LqsK5vGjjAPK8c78zE8lW/j+/gJvPMpV3mAv0X/6y7P5Lv5" +
                "GvwnDWy1RQziMaQRPSRak0+lPFqD7pHmyJukBHmvXFQOlevJGdIK6ZhUD/z+nI6kt8lqjOWzMKL+VVQXnK/mE3gLfhclGcS+YAoi" +
                "kbBHQHLPvGQeBJKz4KXtuHqHpJpRrCQryJJYe9aRDWZL2T6kDFj6FnuBMussiJfkFXhl3pg34Em8PM7L82Tek48A5u38LL8GHBov" +
                "IppiTE/RM1tA/iLbyQmSR+LpEOql06WGcqTyQBnvcDjHO/Odv7huuurCUrq7VdDCoINBa4N+CaoSdN+91t3eTd1prg6u985xTqdz" +
                "nOON0ld5JI+XY+U0qYP0ms6koXQG8YqeIpN/zTPZAPba/Nl0mjMNaqzTW+pUz9AOav9oe7QrWpYmtFJ6a32unq47jC7GX8Y7o645" +
                "2TxncjOZ/ci2A1slPp5f4WHwhcUYvRQjKWQ/MUlDOofepg6pljRIWihtlPZIR+2Z4K3Sb1InKVlySEfodNqOBtNMIP0FY5Fw8g6j" +
                "ozSxxv72aoGYh+MGWNHaT+OmvWbXY+uk/bayPke8wKdZmv/nGB/m2t+b3LPHzMfxjF14ykIxFlHSVFQRUSKXp8OeM3lf1Hk8J/wJ" +
                "O8ZWwEpz2STEWor9CzUdWCewZStEznA2hS1my9khdhj5NMuF/YrwCN4fUbaJlnG/V0bDR8+J/fwQ+xnWW8nL8hMsgc9gz82j7Bv+" +
                "HetlasZUYyBieoWxzKhj9DRCzFRziXmANSQ1pYFSjDRe6innyHeUxc6C7npBh4O7hEaEXQi9FhIcEh/yW0i9kIIhi4O9IVFhRvBb" +
                "d75rrPMLRVa+VtLkOHkVnSquMJ+xGf3fdfpVva2RZdQym5p7jNHGAaODOZ9FiVXkW/qIpmCUskgyqUGTpP9JD6Rb0gypIy1J2ot/" +
                "uMF+ZjPYajadNWLj0dPuZTzWPXph8wZri3q4y2L4PpbK4ngCsB3j1cUAIYtRvD389xO8MtxmHQ+L5/fAkn7zV7aH1xaryWa6jqbQ" +
                "STRR7ilvlDsqFxWH44YS5NilPJDXyF3krfKPiDpFGkdH0+WkM8breWKS+Es0Iz4xDkwyEzkAzosXzXgPXpXX40fw/E1mC7OmudYo" +
                "YSQZl/SyeiH9mvaHlqaeVDVVVXepvdRKqqTWUA+qn2l1tHHafG2rNkX7oLUyVhs5RgvzmNHDGK730v/V3+o78N4kY4IRrP2rTtXf" +
                "aOvUbDVCb2/s1FdqxPjBvGYGsxt8Pd3mTA+fHve04J8JTeNOxAyObRWfVrBRoUcJZ+Nbx02PnhZ+0H1Z6QIemSOPkWqLP3WHujQw" +
                "Sz2j9TMyWEAUkfJl2VHQ0dHxret48PgwT/jg8AOhfwcXDWru7uK6jpHRMOkKucqLma20BoHe/q3+bYEWagU1I3AmsERVtWfGLnZU" +
                "7KZV5KnKS2Wv0l85oix0FHHecuxVMvHOauIVu2oeNk7oRfUmWjs1NXDe/4+/AHpHZ7SD+nTjjTHWnMnO8lNiBClBs2iItIzWogr9" +
                "gUbQIvQ9DZdGkpG8F68oCoh6iFdN1BSzeFMRIfqIaDFVeMkA6qZnMf4vTRaBPevLf0jXqfWralfIKHJZtBT7+FB+kW0wHxlX9RQ9" +
                "Rw+gZg/oE/VzelX9iX5XX67LxlM91Cik39Uuaie0Mdp0TaimugU97UjtqOpXq2mG+qk2Up2mjlBr4zpbVbTXai+tok6MsvpPWjze" +
                "sVCrp08wippD9FJ6vlZO92mb9U4mIpc35O/It67WBb6LluOSE2Yn9IgtF+NM6FEormDT+M5xo2PWR+QEFXe/cF5U3tIqUkkpibfS" +
                "6moFjcPGbrMw6SY3kZ9JnzuOuw64BwSpIfnh68LnhVUMLRfSKuiwK8lV3PW1wy1d5g62wpimZapfaI20A+oldaR2ALxo6OlmFbGN" +
                "PKT7pT7wgCfyUKW8Y6CjiXJBPie9IBGiNP+cnTVOgTnra3cCMYHVGJs0DZRU/1W3aef0hkaUWZEN5D+J+iSF6vQ4HUCT6FGyg0wl" +
                "t8RXoirvzLqa14xpRinDZ3zNc3k9MU2URAS2EB+kHcoNx7+OLOWq4lV2KcMc7ZxtnQ0cm5XWygHpU5pKFpO75BT5S9QSZ9gh85D5" +
                "PWvMdbRL3CxiXjRmGyOMh4ZqvDb2gpee6k69mN5f/wOj6k76Uy1c26YOUVPVzuosNUVtqV5Fn6tKYGjgUaCLWl/za/P1cfoN7Zb2" +
                "QLutuRBPDYxNsPcO1OtfZry5D2ez9fN6Q7TvFc0exgFdMyay63wUHw7O3k4a0/NkA1kp/RP8IPpJ/J2E2vFnY4pGXohKTEgqvKLg" +
                "orgL0esLpIb2DhrvVp1BjvvyQzlR2s5uww80/RxLIfHSCdqS7CFZ0nhHOfeCoOjgSsFBwZODPK6HzsHODw7hOOUop2STxmyE8b2x" +
                "xHhmpBmZ+hBtkzpBS9WHGWXN2qwPnyS+hl8vIVfIInpU2ibPlydLOnpUXdBqtBCd+VDW1EwyVutT9Dj9quYG25zXU/GkBUawmci+" +
                "5V6eLr4iJYlCkshF8jk9hxovRDaKODGce9kCtCN+87652RxtSuY2Yzhayrakh1RHvodRkIf+IZ129HZNdE1wjlImy/eU1o5oxzL5" +
                "uOSWSlNKB5DPxRmMdk/gEyqIPLYOfPSMzeIO0RU99lBztzHdfGC+Nneatc265nljpXHU8OrZ+p9GOtqYWN2rfqXeDUxTX6pM3aPu" +
                "DZSG9dICKWpZ7an2EKxYU9+mFzBk87y5GryaZ1Qx35gXzTuIikcGNyhriNauJI9nG82rZncWxddzTRwR9dkRY57hNZ+I0/QSOc0r" +
                "Ma8xlam0rXtR6I3Q0mFlIw9G9SrwIGJm9MqY2dFlYpfGNo1qEfY8WA++EnI+eLv7mPOccpOeARetpFWon/QHCzWSupDn6JElO0o6" +
                "zjjeOVc7cuQN8iylrON3xwTHesdW+T3dgtagFRktTolPwHilmGpMNtYaM8z2ZsAsyNvCu/80803NXMJ+4aNEDVJftOUlxFTYprHY" +
                "JBLRwu9lxxkVzflmtB+bzBf6F8ZbI9N4o2cbm8GP281Rxl14q2A12TF+UVxjT8w6/P8AGb8VDQB7AAA="

        private val KO_SPEECH_GZIP_B64: String =
            "H4sIAE/W0GoC/2S6BZAcR9I23D3M3MO7K2a0ZFmMFjOuwGJmBosZV0wWWxYzWawVMzOvYLU4M83TTF/fe767P+Kfiu6umaqszsrM" +
                "ynyeiAEAWQEAEPjf5199Rfn39a+7rDZF+c8M5f8+/5olK/+Z9a9LVv49BgDS/82V/39y/17rP/L/Ww385ykr/5aSlP+t/L/3/U/2" +
                "Pz3wn9n/kf6PBsp/NfnfXmTlf+8D/k9z+b9ykvK/vcv/lZP/PyvJ/+gm/aPbv6Wk/44r//1d+Uf3f68q/WMD6b/ayf/0/mNZ5Z89" +
                "/28l8B8f/Ge1f30TlXwFVXDlhxJT76TaULWHKaLa49W7rJjVd1nVSwdYACvgA2xABNCpK9vVu1Fdywp41ScIaAFBnZ1U38Ir//om" +
                "qtJmIAyEgCjgBsqochDgUXtGdQxX9OpqksIqhaomZnVVUfmXvPf/RrWAHoj/8/bvSraqiaLqlqN8UvvflXfKK+Wl8lB5rZxTrihP" +
                "lUfKVeW88kD9/YOSpc6B1TmsahenqmcFoBxQHagLNFFbS6AR0BVoqD6rAV2AmkBTtd9abY2A8kBzoJU61lqd1QPoBXRUn+lAB6Ab" +
                "0BhoAVQBiqu7K6VqpgPylTzljdrOKaeUXcoOZYEyS+mi9FI6KC2VmkpVpZRSSamspCipSjG1V1OprdRRmioN1Dn9lbnKPFXmpHJP" +
                "1f2rauXSQFGglqrbSGAKsB3YCzwGzgJfgBxAB5KABywL1gHbgC3AoeAEcIZ6LQXXg7vA1WAGOB0cD3YFO4A/gcXAAuA5cAY4DswC" +
                "GqiWppRLqk7jlEaqc/Plu/J+eabcWa4tu+RC6at0W9ojrZBmSH2lflITqaM0ThojDZKWSIels9I3KVtyybXU2fPlvXKOLMh1lPHK" +
                "NdWbNYA5QDZQBFwEngHtmu6aixpaM0i7S5vQttb9pVN0VfV99Iv15/QX9W/1f+tX6Efq6+kN+vW68rq72h7a55rOmltgc/C86vfd" +
                "SmnlL9kgr5BwcbEoC3MFl7CT9/IXua4cyB1l+7J69gPzBzOa6cL8rLYuzFT1G8U0ZPexDi6Dg/hMvqNgFHeJ06TGclWlMlATHKY5" +
                "qJV0swzZxnLm3yzbrOdtufbHjkXOxq5HriLuue4T7hfuq+5e7vpuu7umq5YzxXHfdtJa1vLYpDE20j8BhyoVxIHcEHoiOQdzINfj" +
                "ROG3guz8LvnT8/0FDQrBeItEGnISPYDPJatTS+kHjIGrz9cXEsIEEZQqqdacJWVJmIRIrFRDnixfk2V5oVICIIG3YEntVN1XfXvj" +
                "HlNRy3Zr0tbKcdzpd4/1HPZ+8hn9NQJpwTvBZJAIvgy2CvYILICOeCo7IPNLXSlwq5hNX8Zh+HB8ROGkfCR3Wu6r3O75fQsXxjfD" +
                "19B7RJJ6w77hb4qd5TvKNOA0MA44phyUB0sm8U8+g9vG/sVk0o3oyfRxehq9jIaYLGY9G+LechRPClZpuBxTBoO85r7OZOxq7mld" +
                "ZaedjT0/+SL+d4HhoVnhhZGi0UPR+9Ez0frRxhFX+EHwnr+Up5Qt33BH+1UpwmeTI9AeideFG/L3587NyfyxMOdKXvdCXfxT4jp6" +
                "nGzH1OVPiB6lPujUPtJu1eKaZWA95bo4lT/KaphPyXfEVzwXz8L/xmfhFYnaZPWklOxMt2SasBW5Y3wT8b30q9IQ/KSdZyhnvm0d" +
                "5aBcLb01IE/gc7BruFRkXSQY3Rv9Fp0UPRWpErkeLh5uEloUGObdZF9gWq73aaqIv1C9sCHwjdin/La5m370zf6UfSWneP7lggmx" +
                "MnAHfDp1gu0gQLIFXKe16ivqv+pua62aucov0q/CMG4OvY5k8df4Jnwhdh6NYBPw3wgr6Ux2oKJ0beY9e48vLY6TBijtND/r8417" +
                "Lcfsh12tvX9AhwIZIXvkecQe7RU9Fn0YrRVtGKkX/hjShdCAJVDS/95jtZ8xNtU/1dSXIDqGFUVmxNsXrM+9+cOVXTbbnPMib0uB" +
                "M9Yr8QTrQ/HsKOGFlAEs0xr0n3U2XTHtMTCqLBGn8V/Z3+j55G78HE7iDfCuWBbWiRhJEqSJ6k5bmD+ZKGcVpotTpbi8DrykO2Vs" +
                "beFsQVdd7xloQ2BYKBrRREtFa0eXqr41RD+G2dCOUCj0U3Bj4LfAJl8bxwRT2NBPd17eQ6/BrsOV4svyN+ZMyZ72rde32dmbcy/k" +
                "Vyg8HJfRelR17puwThoOnNUO1mfqJmibaH4AQ+W7wiouwnann5AhohxRjLxHMHiIyCT3JZ8ke1C/0X/QmfRJZhEn8hZxilQayNGu" +
                "M9617LRXdANQ7WCrMBWZltIrtVVqQcqalC/RS5Gm4R9BJrAkAPuL+p9DAd8Hx1nTdn1p3WplODMM6wQfiF3K6/dj3PcZXyt9Lf/9" +
                "bM70/O6FDRPvMIkay2+TAkorsLxup76vro9mL1BTOSt+40YyX6jlyRqkkShD7CHbJ1+TvuRz6ol6SnrRfmYUY2B6MQg7lV8qpEvV" +
                "gaCujam0rZ0T9dQOHA2vix5K+Zw6Ou1e6uqUndGZkW+hqsHHfhT6G3oEPYWs/q3eCo7VptaGDN1D+SN1AzkXn1GwMKfb91+/1s8q" +
                "9uX373julgJjfAFSQNblVki3lTbAd/CZNks7EHwq3xcrCwfZQfSI5DlyM/mcXEz+lRxDj2AaM6uZoWxLNsRMpq/RDekJ1FtqDzOD" +
                "+1MoLVs1Xw1R22iXyZcbSETmp85M25E2N21o6rNoiUjv0LRABoT4Rvte+lpArf3jA2igrs/o0JkrGF3aDP4OdiD2I6979qivB7Pa" +
                "fl75yfX1XU7n2GL4KHYjWZFPyqfBm0Bd5YVaQ3dLDzmMmUM3pEaTVcnD5GjqFe1jk2wZYY7YSMzhB/ItuaX0omS5pDN5gRhCTEp2" +
                "ZFkhQ1mp1ZjrO7N84eDu8OKUPmnfUxunOKKdwpmBZdBM3zNvfe9Crx2K+38JVYm0T+mfMjjU2tPQChlrgx+Y54muuYe+/Zr16FPV" +
                "z30+j8iamp0aM+BTk9/oLWyq+FD+TbkovGFW02PpL8mi5B/ECaI5mUGhbHshLs6R3Wq9bgDkygFxHUtT08mTGIP8jg7DZuO65G4W" +
                "lEtrZeNw29+uMHQm+D60RD0ButCuAOCHfUu9jT0ZnrY+k79uoF5oXSQvZW3alCL30+KRy96plpjmnqSnjyca5lT5fuPrtK8Nvr/L" +
                "eZKnFDyCJ5GNmFSmM7WALJ2clXxFZqJWZC76jHiXLMPc5xRBJ/cEtmi2aRqDJuWBmM2X53T0AmI1dhtLxdvjeQRI67n5wmz5OVhE" +
                "bzJVt/xp7WC/7Fzodno+eOr57vvmQiP95qAu2COYFfSGH4SPh5eFc0I3QvNCKaFVgWfeXbYXunnKIrE8/QzJKniU9ywnPQfLzc93" +
                "FLwsYOLZaCqxgEjFF6CdsTRiIZFEx6Ot8Z3JOkwqX1L0SqL0l/IKOAco8jpxEN+b03Kf6FXJQ+TO5HBqON2TPc7TIqRcASvpNhse" +
                "m36zHLO+tJd32TxVvZk+q3+xv05geLB7qFboevBasGEIDRWE+oU6BjsHLwXPB895060XNWNkUODJOYlOeXVzP2e3zMnKIwo3FhKF" +
                "Y+EGeBnyAzYbHhU/knAi1+F4/A3ckPhCZ/KL5BXgWBXvLANfgZnKVcHNNqM5+i3dh4KTBP0H957fI2yX7CpqbQdcBKtqL+iWGrab" +
                "HloPOvd54r5A4GboSPh8qHuoR6hp6O9Alt8RuBeYFPot/D58P9wicjv8xnvGMhbcLj6jpiIpBUNyh+Ws+DEkr2x8FfISbguXRyZh" +
                "p9AqiTv5f+bVLfgRe5LYgWjI+0xN8YeSrhmtaav0EvfzY/kH7ET6U3IiPZfrKMwWHgkNRJtoE4bz+/gJ/Af+iNgDoHUXTLK1s7O/" +
                "d71/SKBvwB6oG6jlz/eNhu5DiwJcYFu4ZzSUuj71Y0qxlI2R+kHe4dTjYgY/jOqIzYw3TmxI8PHO8CnkKFov3rZQye9XeLzgYd6L" +
                "3Mf5zRJRbCRxMmlg73Bf+LrCMbG58JRdxixj1wqVxD9Eo5guWeSn4g1+OzuROUlVpSoyHfkfYm1lmybfoJi/WtfbLttHOO+4NJ5H" +
                "nklQo8Cw4KvgxzAUXRw5Gh4Z1kQahbNCDUN/hiqFFnut1maaXUqhgCX7Io0Tj+BY/FN8O5yCYonswgOFbWMvC8fnt8nzFOyIj0Oe" +
                "oB3wIVQZdgNbjFvCjxOWcE7OxtvEbHG0GFO1eiJJwgruMBtnqjGz6FlsTCgpM8oozWr9FeMQ0xxzxHrCvtlZyj3S6/MfD+4IPgtO" +
                "CxUPlw21CWYG24d3hl+GMyIno5Wi2z0aSzWwJFBT6J08jv+EjSYnoqfxMD4V3RQD87rn98h/m18x72j+jvgSZCDaHn2K7SRWEUeJ" +
                "W1RltoDrx70R7kkb5CpyDfGTeFAoKlzjcriQUJGvw7P8SOkXFS83VzqoGGmybr3BaT5kdTjauEp6HnvrQq2g+f5SgVnBLUFfaECk" +
                "VPRYyne1OmyKfor+7F9ki+s+gh00NoFlL1Gz6QtEP2wsTsC22NrclXmDClILfi5Yk185VjNeNd4sLsaHwxeQBVhtqiO7jyvNE3xZ" +
                "caJQTvwhaMWF4npxpjRExZVOaZiwUZgmtlFReh/lFNhbm9CtNlYxL7WUs52ws07Ctd7b3NcE+ug/EIgFW4YqRt5EotE70YEph1K6" +
                "Rrp5x9j2GUPGsrrTyjhpF3+AySY/kseJn5FlsSOxLfFesfkFH/Lb5DfOk/MeFWwu/F4IxAV4BbaL+DU5k4KYL0xNTuZjKt6VhKB0" +
                "UOol3ZFGy9Xld1KePB44ApQC72rq6/z6iOGi8Y1pvvmF5Tc1sjBH1AW7ZrgrehTPDG9R3w7fJV936BsUg36B3rjv2odZfOaOpsOG" +
                "Ffqe2l3gKqA1eAnUAn4Vg93kf+L3snnUBsKNuVEzOghpkFgfGx57ER8JL4M7JDJUPP4RlpHqaBidiX7B5hCz1ZqZJEuo2LwMu4S7" +
                "xncV30oxJR1cpnmoHapnDaAp1/SL5Zu1n723Q3SQztnuqt7tvpNQX3/ngBIcGQ5ExkQuh1cGa/gnQdehhtBHTwnnddsO627LD1Mb" +
                "vQx8ky6KZcS5HJH8hP2JLIMzE+9jYEEvFfmez5XypuQpOVdyruTWyT9ZsLzwY2G/GBu3IYfRGVhb/CZRP3mZ2su84MYKlaWeylJw" +
                "onaSPt242tTVvMQy0EbZXc6Y87LrshvyXvRlQ538H/01gs9Cjsj+SETFQBdCqwPD/XX9FLTAV8c9yLHLdts62VLOuFQjyn2l8yLN" +
                "N2S+EQq6D4kgGxL7Cp/lrcktm1cuH8kblvsu527ugvwLBWUKJxUOjyXjK+HtSCe0JLYXr0g2Srag9IyDKyEA0jF5GfBEE9RfNuwy" +
                "jjC9N9+wdrVXdWx3THYiro2ey95ffa98gH9HgA6+CLUMPw4fi9SJFAY7+etDZyCb/6x3v/Nv21RrKety8wL9dmCT5JE+iQP5y9QM" +
                "fBlaBp2HVE8wBX3zquX9kV+yYG7e25zKuUresoIihbrCFoWZsaMJDTIdyUHaqUh2L9knuZnaxLznzOITqZfSCDypZfXVjQeNmaYn" +
                "lhO2s/b6jvOOq85pbt7zh/eod6qvkv9b4EfwVzVLTQh3j3QLXw1Ugk76TkEZ0E+eRo7P1prWh5a5JljbXykvtZMyxABXK5mLHUB/" +
                "RvPg1vFGBRPyLPltC4CCmnmDc3vnlSqoU7i2cGdhkxgcPwMvRL4gh9CBOEVcJKcktbSWrcoXEY9I1ZQRIKZ9pZ9taGHUmaPW47ZM" +
                "+0+OK46A64c74F3uNflGQpUCW4PzQ2E1W42NfArfCHb2j4YQqJh/qveKs4HdY1toPWY+rQ+ADeSr0s9SfX4blcQbYRvQWUg00aaw" +
                "fn7//EkFUwpy8hx5y/LE/HKFxws/Fw6IXYgvgLchPvQd2gO/oUZ9k+QJagYzixspyOIC+SBQQovpsvVvDZtN+yz1bJ3ttH2iY7uz" +
                "sXuyh/Cs88K+Rf5PgdvBCSFfOCfcOFwq+BiSfaugLRDl6eeM2l22+dad5jf6GeAbuZ28W/qb/5X+RrTF12FD0SLwtNj2gqyCd4U7" +
                "CkMF/fNf5+cVPC2sEZsbqxTvm/gBFyKj0b7Ya/wd0ULlSvuoqUwrLo//VXwgOYERGl77TJfU/zCK5kXWYTbOtsX+2vG7a73b6Hnh" +
                "6ez7BrkDTOBM8FRoa3h9qEWgPPTNdw6aBPXzUI40+xKb2ZZlPqmfBfZXqit15Q98H7or6SEW40FsHvw8NqJQG3sTKxtbWlC+4HHB" +
                "cVWncbG7sQfxADwQMaIb0FvYr4SerEZqk7ep0yp2vsTfFTpK35SGmuPaUbrz+jtG2NzESlmn25rbHzrGuYa6C92FnqU+vb9CwBS8" +
                "H/wSQkLdg5X8uK82dBEq7tvpcjhQ233bCmtnk0F3D3imjFJqS1auDBUmexFL1OrzInEjtiE2Jr4yrou9KShbGIydVjV6HcuI2+H+" +
                "iANNR0tjx3E32Yv8g7yY3EMvZT9wmfwVIVPqp9pqrLaTzmxoagpYZMt8aynbant35wlXdfcDdwOvGZrq7xMwBkuG3oe+BF/4i0Nf" +
                "fCOgH764G3c0sufZ2ti+mO/rHZpBAK0slRn+MW1JfiV6EW+wZsiXuDP+Lb4ksTyeKFxTmB7rG58enxG/HM9JjEDGoHfRuthQXEfe" +
                "JrPIBUk9/Y3pzTXl3cI2UZGbgOc0M7VvdIRBMTWwvLK0sY6zlXFcd253adw6z3vvYuiyf0JgUFAJdgqe95eFDvnivsU+nWe0c5Md" +
                "s/WzlbRsNLi0LrAPEFRIwckWoe6SlcjT+BS0BvxT4mZiLvw80S5+NFY5XjaRnkhNdEjUhQ8ib9Bi2CAsHX9L3CINybnJY1Rb5hV7" +
                "mZvA3xaeS2WBfPCLJqizGyDTcnNXy1fLY+tQO+XgnH+6zrnHeqtBv/sbBH4LXgieCPj8FuimryNk9J11n3X2s0+2t7C5LVMMv2oX" +
                "gpcAl/JEKMlOpoonl6u88Cq6FX6dGA9TcHf4fbxv/El8cuJBYk7iTSILLqtqNERl3Jm4TFwlMXJV8hU1hrnHbuB68N8FRvIAk8El" +
                "GovOZ3CaOpvTLJcsz62j7I8dT5xzXY/ci7x1oEX+pYFaQTiwzD8IIn03fHd8zzybXacc2+xGe2/rTlMjfVyzAewFaOSn/CBmHtU3" +
                "2YRchv+K6pFtcD1kEvIHfC7xSyInUQ3WwabC0bFSSEVkBSpiFfC7uD3ZNtmWukeXZr3cI07gDgp1pZ8VGByv5oSN+pGmt6Zrlh02" +
                "h32DY4FzqSvuHuh97gsGegTrRKDQksA4qJYaXX08Jb3fHONso83DjVuNmTqj9pRqH1bsxm9hFtDFk/3IU0QTvDtWDGmCjkFA7DjG" +
                "4ClEIfY7XoiFiPHErWQxaiLVlhpK++mWdArziqnC9WXPcFlsUxUBAuJ9aY88CjgFarUW3WPDT6axlhbWmvZS9td2yLnJJXrWelf6" +
                "qwaQUONQVV9Lb3PXcF+WG3GNtVOm74Y87SvdIk0XAJOGiEu4bvQVcrDKOacSYeI12hItB/dFy6mV7T3xMTmG+pVcRfjJMnQJZiQ3" +
                "hM1jJtIgW5T7yt7jCrnKQgF7mq3FNuMu8iGpvfxasskVgDaa+drjhurmFdYMa4atvL3QblSjaKYfg0YGmgWzwhv90z3d3IT7rrdQ" +
                "PWfHDS90FFhHY9YMlFoKBJvP7CWLEpeIfslNhBu3oAuQGch7rA6Zr+LzzlQF5nlyabInbeFqCUk+hZ/GZrDl+IrieoHhP6mxE+Ca" +
                "st/Zenwjcbe4VNJIReS+QC1tK8Mc4wRzK8vf1tm26o6BrqSnJTTcPzxQLXAkPCSwyvPVed9V3kvaDlt4fW3de6Ak0A7oItq5o4yd" +
                "WUmWwDXEueQ1ohHeBX2FQthcYgi1LPmRWkgH2PH0XvoR+4OvI0bFS/xZDuZOCXmiKDQSxnGXOIY9w5blBvFxFYFuFWtKlFwGzNHq" +
                "jS1NRc2zLR5blj3m3OQZ6rsC+QKxABhOD6d4ajj/tO/y2h1PTH/rp2rTwdmiXZkhVGCzqEXMgOQt9Dn6JHk1+Qv+FB2AVSAeEuuY" +
                "zfQ5Zgzj5g9zQ5leXG/hiQyLcwSYG8tvEy6KjUST8Ih9ztbgOrMwc4vNFrYIz4UqIi0PAO9rCwz3TUvNOhWl6JwbXY88mdDf/jHB" +
                "acFykdaR6t4v9qR1gWeoo4nxi6YjmFD2caeFZZyfjpDHkzzZHZkIvyQaUDw+AZmPdVZjqD17lu3GRzmd0EnYz18VOkiIkiMfFZ/y" +
                "dYT+0hKpv9hNYNgQ+4mZyo5kHOwgnuM/8S2FLfJR8KG2qvGd6bj5ngWx33eZPId8xf0tVR4VDQsRc8pPvgP2TPM6V3GHw/AM0Cgp" +
                "Sj8aZirTUQpUEdhQsgpSEK+Id6KeEWEUw8pRB+hf+Z7cWXG+4Jeai7fFntIlSQ/sVqZKbv4FP1T6VeL5xfwG9iQzlX7DHKFJGmcX" +
                "8utVtnJQjoG/6yTDbpURRO0/O3/2RL0voVAgHOwRgsOzok9ShkCl7KVNkx0lnXX0PZQMsbLclHqd9CfTycnYeNxBPoRfxFqiKVTl" +
                "ZCVsHTaSGsNuEUqpLCgqrZb2iFtUpr1dcin9lQryJ47kACldusVf4zqxw5iq9BzmEZ1P72OjvMR/EHzKMzCoTzVVtcy0tnY8dT33" +
                "3PPC/oeBZPBBqGv0a7Rd6mnIaXcbf7N/c2Tp7sithCrSbHKqmlkKiC9oMXwh8Qj+ELuH7EgeTHbBR+AL6UK2iViFz5N6Sy+leeI4" +
                "6XelnXRV7iLvlEZw27gR4ipxF9+KO8ssoZdQefQ2ujOzi33PdRJWiQ2AQRqj4aNpk3WRrYwT8ICqf64GqodKhRpELkcDacdSF3h9" +
                "lp8MW528zagdL7hUPJ3Au+LP8dHEBXQrvhlfmnhQWBxrRkFUPppDdGIqCsXFY7xLXi4Nk/eLG+TvskZyySXkfWJPdgy3ReSFbG4z" +
                "85YWqGzqK/2V6sZ0YD/yu4TvYjvgqKaqMd1y1RZxtHRZvB18Lfw3g1hofLhTtEFqk7TX0d/dm8x9DCmu0tZ9mpp8qhhj92AGrAWu" +
                "EDI6Gj+AnohPi7lxN1WO+oGNTF5jpqgscZrQW06XH8gesZpcSd4lPZJqSBnCJTXnbhQhoSG3jH5Hz6cKqYvUVWo1M5mtzR/mdXIV" +
                "sL5uuIp7VtkGOpqoXOgv3zS/J0SFf4SLRjUpStqllNKe2aZBhoirj3Wz5i/uu7CZ3YGPQzV4deKuil102JZ4zdh87BT1N1UT/zXJ" +
                "M7+LqfwY4Zz0t/xE0kpH5QHSYrG9yly/CrfZ7nw/8TfhHPuCfkF/p9rRgymQdjIX2cZq1ZwiVwKr6WSj07Ldtt/xiyfpPe5zBaYH" +
                "rZHXYV2KP+VDkUPRm85Cw29GyF3P/AB8xr0XZ9EfsA6ojF8kShKlcQ3Kx1bH2+B/UFOpdfhiys5eEJ+w1wWjdEL+W3oizZAniCPE" +
                "LtIUieQncBgvCaP5p2yCGkwvpM7TZmokHWFesrv4JeIU5QRo1DtNP1viNrezmGeC9zq0M3AguCH8d3hvysWUCsVSQlftJ/QLrXmu" +
                "RkY9sIQ3yI+S/bBzyCbyJfmMmIV1RM7H6sJ9ibX0fLIOMZcOcgF+Ff1QqClNU2pIayROrCRapenS32p+e87nCTI/l9vN5NFpzG16" +
                "BT1MrRQt2QDfWngtPQS6as/q/aYrljv2bGdfT1XfGP+sABI8EoaiZVM6pRnSqnn3mG0GvWu8Ta/vKIQkI/+AuISY8V6ULWknFiI+" +
                "eFh8LAYmB9B38dSkiW7HZdMSM0FcIR+Qx4mFIi28E7eqTL66mCZUEwqEWTzD/sKcZQzsCqYv3YJ+zgb5owIv5itNNX/oNhq6m1fb" +
                "RGea+7ynmy/Xvzh4K/Qg8iaam1aYus73wHzbUN852dHYcF/aLh4VcLIbxmE3qI1UF+ImsjwxO7EMXUMWUqOIPKIfNYj9ru72gNBD" +
                "ni7rRYtYS/wk8lIDaZToEKuLL4QNvIXfyHZi57AT2UYMqWpykXvHXxIXKCaNVeURt4yTLFMcL1wVPd88U6Hugf6hqWEy8iXlUtq8" +
                "QA1bT4PHXtR1z1Qc+CgOl9rTX3EHPpP6RN8iJ6PPEl8S+YhMFKMc5Bb8OolRMDWFWsJFpVuSJPQRzgomqbScKYUln1QgthObCE35" +
                "HK4N9511su2ZPkxpbiH/STgpnVAeg6I2W7/NWN1613HW5Xdf97yBKgYfBE+EN0cHqOdni3exeZBpkrONc6GxIVBU9glDqNrElCRJ" +
                "j2E2EKnI1fhy+Dr2mGyWTMfvY32ImcllyUxqMTddrCjWE3ryl4RlEqR4ZFysLw2V+ol2geNBYSu/nDvJPme2sLt5h1hFsioC8FzT" +
                "TndZX2jca3U4G7oWuVp5ZkBUYHloYOS36KzUmyHSSRhbW5e43I4Xul+ArVIZHk+OS9I0yRSlB2C/wfMTA9GW+CmyC9FK5cXfcJE4" +
                "Sj6gvnC/Cwv4Q9w17rmwXfouT5Yy1FhJl0YIVYTK4m8iz5/hinBxluK+CM2kl3IGoNEUatP1TY1u8y5bD2cPl8ld2TvU3yq4NVQi" +
                "MiClf0oPqLj1mamN47UrYJmm0QGyuJS5SeJ0KrecDZGbkB8JFzIY+0L0JnOxg8ggtBs+j9hNnqEt/F5uG3uUXcAPEovLotRQOiI9" +
                "lZzSPKGvoBGfCnv5+ZyJW6fqeUa6Js8Gumgead36xQazub8tQ7XICtdeT0n/rOCIUKNIOLVd6nbonLWuubNzhbuJtZr2FuCRjjO3" +
                "yAnMMX4iV5k8Ba9LpCLpeElyM/kaiyE7kA+YmzhEjqQXcO/YNcxWBuYmiGOkVVI9KUV+J90QKwq7ha5q3t/FX+Y6cO+4HOGzinI/" +
                "KyS4XdtYn2cYbN5vjTh6ORe7qnmOQ7uCH0MvwnejNVNbBg2Oeybc1sfd1r5M1xf4U2rOtU1eoDK5MbyWOoC8io+C22EEMZ2cgn9E" +
                "4vB29Am+iXxIoewLbho7nSnCAWJQrqBWosdqY8UxwjwhVeSEAyqGwNgG3Hu+kZQlHwGsmpXaA7p9hvXmiva4M+Lu6fZ5z0EXgs/D" +
                "sWjVtBWpCchltZmXOsp48q01dU+V+0JNuiOZS/fnb3E3yFLwrZiQiGP25AZyIL4b2YTcRMsReWRzpjz/kJOZ7Ux5ziZiUiN5oNRf" +
                "+lmShaf8Ld4p3hQu8c25tyzJnudPi8vl+wCi+Vk3SF9gqGQuZVvqLOep5h3se+lvHOLCc6OT0u6ntQh4bTFTqqODu6Z1j7Y2wAo2" +
                "ugjRnC7OD+D6E0MTPvXEpGMfiFpkEbwyakePY2WJWsm+zM98BreTHc9e5YYJVaXF0m6pvvxAOicM4DE+JjwSXvKzVQ6wmS3Bu8Wv" +
                "0hngmiZTi+tA4z7zSts15xn3aU8nny+ABi2RP6PhtFdp50IWR5ppke1vd097Z9104KIo0Rl4MrmLkziWtMIdYoMTBehJ1S+j8UbI" +
                "BDgXDREZZIBuzFcQKnO3mDKcQWwp75PPqzh+gvy7WIRfzo8QT4t6AVJHZaYcb5Fuy72BmpoB2so6UX/aNNB239Xbc8id7VnuD4RH" +
                "RB5FD6UxRWqE3tgSxor2nh7ZXlKXAtwWqtBt8C1UNvece0p+T0RjmXEG3UlSZAW8DrIXvowWJShyo4ohU3mB6cIYuGvCOClN3ijd" +
                "k6ZKrNCIb81bxK5ijK/CNVMxr0NYq6IoA8CDJ7Rl9JWMNS23bLWd91yie6tX9EPh+pF+0fyUjWnfw3NcNc3drN+dxRxl9K2AniLL" +
                "dFd372EhTksdRv6I+xNhtBqRRdYmqqpMVUDNRHlSSNbjMgSCO8k0ZisIm6V5siQ5ZEXqKZbjc7npwm6xr/BGrTRWtjT/XMyWxwLV" +
                "NHFtNf16Qx2z177Z1ciz2jPC9ySwPXwucjv6JTWnyJFQlr2taai9v3ulbaXWC7wVytM2fFFyD3uALarGqRRrmmiOjiIekWvwbkh9" +
                "5D52lbhNNqAX8B0FlB3BjGd7CYekSfIK6ZyKBaYJYzgNjwhNxQv8HfY1M459xXNiRLYAKzRzdFn6FcYyFszW0XXTfdJzz3creDy8" +
                "PdI52j5tWpEp4ZgDNh233XZdtJXTbleeC3l0IRYjjzIm1kFOgK3xkgkR4fCZZB18IHIUroX5iFTyKNVIaC/msDqmOrdBLCsflD9J" +
                "teVt0kGB5GbxM8Rj4jz+ItuKPcWu4TtIT+RiwCRNV90lfcj4p7mTrZbrsmext7avf+BNuFG0bbRFWv+ic8Nb7C2N12y13E3tlzUP" +
                "5LJCG7ov1o90sM3YbkTHRKX4k8Q4tC5xl8zDUFiLBPCdhIccQk1Ud/2CNTPn2MNiBTkgt5J2yqw0SBjFjeMHiFfEc/wutjZr44ar" +
                "XvMo04A07Tfdd0MFk8vis09zn/CO8A2HbgWbRX6POlWkyKQ98/e0Gkyf7J1dR83vgQnSUu4t2QuzUw3ZK3RDLBa/EJ8OD8X05Hpy" +
                "AqZHshAb0Z/clCzHNhI5/gAzkDnCT5BS5WtSV2m4WoOb8Y+4/kK62ElI494yD5iv7G2hhjxX+QD01LbQrzOsNRVa67kWepv7jvtq" +
                "Bi6Eh0RtKT3SNhXbH63t+sv42PrSNdExW5crnxIQ+gQ2Cn9B12Q7kP0Sr2OTE3p0Kr6NrEhISDPkMVaaLJKcTt/g9dI4vjj7mh0h" +
                "LpEnyfMlTq1zM4VN6mnfJrwTmvPt2VuMU0UgHvG7FFamgiZdNcMx421zwHbaedhTFSrrfxJoEn4ebZwKpAFF90fqu68Yn1qaOcs4" +
                "bmg/Sd35NnQvbB/2jOrJ2MifEs545YQTLYUHk8uIfuhaZDBuTgrJrkxQGCK140uyWewlFaENl2+IohiQGgrbOYprI5iEr5yfrc+s" +
                "Ys5zmeJluTxAgTZdmmGEKdci2et5QOga9B7aHLwcOZs6Jk0ocjZ1PVTJ+t6EOuY6BdNYZaVYi9WTldGXZDZdguqAmOMb4jpkHfaO" +
                "iJO3sU9IUew0mZfcR+dzzaV0McL9zaq1RD4v/ylppApSZZHn5nFH+FqChSfYj8x75gXbW/hdOiXXA85rjuhqGNubaes1RzPPTt8O" +
                "qHPgQOh8NCN1flqNIgtTxvhSrVmmk451jgeGxlJjIcpOJSqhCjGd7pgEkAWx4fHGSBG8CfmIPIFBaEcsTm6h2jMIv1/KFLPYClxS" +
                "uKBsVjTyEBETfxLv8A+4Z3x1QeH6sluZBqzINhE8UlL+DrRRo6ONca8501rZucvzBPIEpgQfh0ZEpdRyRfAibCjD9cXU3HbTMdKK" +
                "gOWkesJAugz2Cu2VTFAViCfxrTF7Yj86F/9AcvgR9CL6O1GYPEN9ZOuL+dIL3swN5a9Jr5Xvcqa4QFRErTiOL8fv4uvzf3CV2P2M" +
                "j93BR6TGsixnAku0NQ0bzI8tfezVXB+8S3yV/COCSyN8yq9pVdMyU/8KuJxes9l+yYGYOigjhHn8y2QxzIyvTS5OQliPBBa/AC9A" +
                "m+IrySJEI2welk0Wo8fTi7igNF6qzWdzftGnxmhfqa14UxwrKvwlrjpv4otzw9mDbDkWYN38cbGHnFDWa4rofxi6mP02v9Ptsfiy" +
                "/S+DCyOXIh1S36tnpUvKNk8ny3FLoeutbau2qfBSCHOdiCLIW7wt9YE8Cf8WN8d7wH+h8wkjgcIA8hS7SVWmztCLeFaaLB3k26uV" +
                "4wCwBJgg95BKSm9UJtFb+MiP5TZzh7g13Bp2EVdHrCu/V9YCQzSzdF7jYnNT60jHa+cfnkbQ+kDloC40MWVc2rE0V7SFT7Y9Nwcd" +
                "S2xD9bukiLCBK5k8jvnwXsltybNokQQc34mkowo2gmDQMch+dAuJJodRk9m+giSWFu7yU8Tlyi7AI3ukjdJH6Tdxk6pJHf4dd53T" +
                "8UH+PT9frC7XB86Cf2jf6JeaapmLWT849nnGes2+fP+LUFLlL0LKsDQ5WNXZzRyzzXYVMWcCtcR60k1mCXEd9yddlAlfgPSDo4gb" +
                "GaPGRxh7Db9G2hJHkyPInyiKNYinhCk8JCaku0pHebFkl4epmWy12FpMqO/X8DP41byWHyZUkDRKc2Aj+FU7xrDAVMScZpMcY921" +
                "PCnQ88CyUMdw8ei41BJpl/wdHKmWiPOrs7cpFxgj1ZMN3FAqTa2le5KPcBt6EHmB7EfeIhcxGGkP14Z34R2TbvIsCVP7eZ4/zW/l" +
                "W0jDlEFyBWmrtE7urT6DUlKABB2PqlxTI/YXQ9IqpQW4WrNDe1rfyVTe7LdWsyvOyu4xnq8+S8Ae+hgaEmkWHRqo6dpj4W0Fju1W" +
                "STcU4JSO0mh+KjOIOU5XTV7CM/ASRHu8HLofeQm7kDXwE2Q2dhNfSfjIINWKCXH9OBf/WagvzZRmS2+kLXIHOaGi5YlSJamoVE2q" +
                "Iy9TWKUb0AQMaDfp1utXGSYaW5jqmNdYAPtpR0dnEWdX1xvPRd8G6Jt3p2uRraVlm2W5ZZf5rUHUebUeTX2wG4DJp8Q2ai6i2KJc" +
                "iK1KvydbEK/wF7iHuIhHiY0qXtdQo+kCeiPdht5Kj2a8bD21ppVkK7Aj2O8qJn3L+rizXAHfR9wn/S43UYwAAeg0OZqlWlY7QTdQ" +
                "P9vwyljc3MnS2nrENsMx3dXFs9A72nvAA3rKeFZ5pngCngPuRa7ZzmxHGYfd/tnywCjozNqO4AiFFivyh5jR1EQyTMzF1iEHEtXi" +
                "N2I94nPilviy2OHY2XgjuAOSh9RCh6Blscn4YyKazE6Oo/LoYVyh8EoyKX5gHdhTW0x/w+A3kaY8c11r2J7q7OQKuxt4AF/UXzn4" +
                "OSgFNvtnQFWgbtAXX3XfTc8hVyXnMsdV+8+2PuYV+kuacwCkNJUm8Em6aPIrvht7hy5C9iWQ2InCW4V/x8Lx17FesQmxxXE33Atp" +
                "it5AndgDjMC7kBuTxalN1Al6NDuWr6mynNnyI6UBKGs+6VobBhgHmFaaI9Z3ttqOts4JLtxd6H0NHQisDIqBJ/44dAvKgTZA+b4X" +
                "3kqes65jzkuOivY9lrLGKro0zRpgj3xbaMD2pfqQrMp316J+ZHeit2qTLfGEWotKxjvEGyYmwOWQxUgOMgGtgnnwr/gxojF5lbyc" +
                "7EH/YMpyjfntKp7tqmjAzRqXboqeNPQzvTWPV/UJOOY4y7gRzwaf058ZaBYaF/o9WC+w3D/b38j/B/TaV8tb3T3Pme6oY29rpYxL" +
                "dKU0h4GIskdszDno06SCF2BlMQO6CE5JTI/7EusTvybmx6vGs+JB+ANcC+mPLEAYREItOI73IeYSbcic5GZaZgiW5fYLe6XtyjzQ" +
                "r+2me6RfaexhxiwO23Oby3FHZRYLvQ5okN8T5ILvg2nBtoE7/sF+HHoGlYIaemu4zzjXOSrbH1v6Gc/qjmhcYHVli7iUu0G3TG4l" +
                "TuBnsHroQXh6YmHCBF9NyPF28cZxJn4v0R7eCC+D98HjkYvoE6wT3gI/jrvUrDiB/p1pyc7jegkjpDlKI3CyZru2qT7dOMp80ZJu" +
                "tdlW2Rc5x7mTnta+NZArcDTYLfQ9mBk45i/vb+ff72/iX+Gr6unimuQc65hla2aG9SO018GmQLo8U4BYD+UhefwX/DuahMcmjInl" +
                "ieeJpomG8ZOxT7Er8auJFvAvsB5Oh7cgtbDm+O/4MrwtIZGfqaZMSZZgy/FasaE8DBBAi7aBLmhoZDJZ3NY11o62s/YZzr5u2lPN" +
                "1xl66B8S/DUEB3cGxvhpyOpv6s+COvmqeZq4OAdir21rbjYb0rWLwLJAa3mtMICdSDVRPVcMF9CuSGaidOJooi5sgvm4N14hbk5E" +
                "4ELYhtyCb8A7kJ+wE3hJgsO7EufJg1RLpip7gh3N3eaviwn5DHAf3K5htGFDXdNRcwvLM8tX6wn7Bedk9wsP7C0KffafCXYOrQ8W" +
                "CbTyj/E/9bP+Xv6hvm6eTNcL59+OLbaoeb/+F+1psD9QTCb4K4yZakp2IJrgV9A38Aw1juzwQBhJvI5zsWrx9MQo2I6AyN8wD4Po" +
                "DOwCbiN+4ONU3nSVasd42GFsSW4Zv0j8Q64MpIIazXztH/qNxs+m9eYKlnrWmO2tY5hrlXu654p3LjQj8Dp4NJgR+N1f1j/Vv8Hf" +
                "0F8equUd7Y64qjoz7SWth40bdGs0YXCyIosk52COJ0uSlQkPPgI9DC9NTEikwv1gAK6VOBNn4w8SV+BOSDukMjIPmYdewW7g1Ykh" +
                "hEw0TWZT6cyv7BN2JneFXyuOkH8oFMCAA7X19Ubjb6bS5ixztqWbzeLY5Xzhuu0u8MzytfC/DiwJ+oMVArPUWB/th/yzoR6+ZZ4n" +
                "ro3OMY4/bbnm/QZBC4MVgTbyIyGHrUGDyVXEErwzdgwJwFXVqHqfkBNnE2US3+P1VOt9hvsjE5DhSD5Coy48Fx9BIMQD0kzNpO8y" +
                "O9kWXAW+s4CouP2+8gqwatZq6+kvGHKMF0zzzassgrWnfa/jlHOP6767pveOL+KHArsDiwKkv6O/EJoO/fAN8o3xPnTnOZs7RFs/" +
                "K21S9CW0LwFQqSwhfDv2HDWFJHEOo9BViJJwqm1NIivxIDE+MSyRndihZs4PSBIB0c3oSewWPoR4Q0wjJyXfUFWZWmyAy+W+qbhg" +
                "inRCPqmsB16AQ7SErqahgTFk8qj+7G6dYBtub+n4zbndhbmLeg2+975sKOkfG8D9X6Dvvrq+fO8eb66npBt1hOyoNdsimgKGd5ry" +
                "QFf5uSjyS9ibaqSdxH9gtdXM9RtsSlyOt0hgiQD8MLEuEUt8VlHKeHQlOhZ9gD7FihMRchH5nNyaNNPHGQP3ghvElxdaiUckVn6j" +
                "jACqqxm1ie6s/rChrREziqamlhnWUTbOtta+xPHKGXD7PXc8Y7ztfUuhmf4B/nQo7OvtNXnPeEp6sl2fHE9tI6xrLGXMmYa12mPA" +
                "DrmYtFkYy52hSyXrElXwrdgttB1yLpEfT0k8TtSCg/CzBJ64A7NIHtoLa4E1xQisGjGKLJUcnvyRpKjFTAmuM0/zo4UW4jwpS4aV" +
                "GUAucAb0aY/psvWnDE2MiJEz1VMrc4G1se2cbb79nOOTc7cr4D7knu1Z473i6w3JvmfebZ5P7sHuLFfEdczxzmawPjR/MM0xdtEv" +
                "0rxQbkpNxcv8cbYxTZPFiCi+GLuCzkKKw70TcxJReCu8DZ4In4E3IvdQDFuH78R34bWJAeT6ZF/qElWFDjDr2OJ8XyEibhZrS43k" +
                "hcoyQAZqgpngE00ZXU99ccMKg9uYbfSYi1luWTzWdCtuRWz59pWOpKOXM9VV193H09x71lvHW9/T2H3EVdk10DnXMc4et/axVDLX" +
                "NyGGj7pGmvsKJg0QX/J32MF0h+R2IgPPw0qrvr4KG+EK8D24GFIcgZAxyDT0BaYh7hIoYSFXk8eSz6jZ9D26BKNlB3IMX1d8K3aS" +
                "zHJIaQaYwHRwBHgEXKs5qV2nK6EfrX+nevyu8YiplLm+uY/5g3mrZbRVsRaxjbL57B5HUecPtcp/dpVwR93zXcudWxwn7ZxthU20" +
                "7rUIpoPGnYa9+rj2T7CZ8ou0UyjLj2Yz6EXJB8RRvD9+Cd0Md0q8ip9PHIOnIUuQ3sg+5D56Dn9MdqIOUo+pTfQ35jbbVOULZfmK" +
                "wnzxjJQh75fny4/kJ4oNzABXgc3APWBTjV17QPtZG9RN0K3Sm4y/mu6YbpnKmF2WqVabPeBY7GAcPvX8rPRu9Q2GKgVswQSU6Vpq" +
                "W2gr7uSd9+3LTWbNbXGOIElNgVzpKL0IfZC4gTzBjmI34VAMyfPnXy3kE23giYnGsBZ3Us/pE3QmM1f4rAzQHNTsAhcAU8Dxuh/G" +
                "AkM77Ujls1JG81C7WdNIaS8+56sK3cRjolPYxE5nGHYwn82X4r/wGUKmGJHWy9uBPzUOXW39HoNivGemrO/tkuOmA3fW8ezwdYMW" +
                "+OZAxsCr4LFgudCGYEnvJ3PU0MzawV3CmiH9nWxMPxZeilEOwePxfQXT402wI9iNWLlcT54cG5XomnAhI4hC0qn6uiVXWXkKztZA" +
                "mj/BQxqr4bSlofWyviIIahoaSxg/a6bLXaVc8SehOr+M+8IeoDMoMzWS+p06RZdRs3oLZiVzll+spILNgGVAC+1NwxHTc8sU6x3r" +
                "FetD+z1Xumes+2dXCc9BXzX/fm/YOxT6GJgfjPm2OdsZ9mo7mWL2TiaGrUQcSDYXqwtTKRDdDo+Cd6v4bTPWD9kEv0zkInZ0NfaF" +
                "Lso/5EyUzHSU0/WYXtG8A05o5uoPqeh2qqm3Lg8oD+BADHys7BOrM6nJ4snz9DMmkSyFr0d34qWp63Qppgd9jV7HDhOGyZWA72AN" +
                "jUZ7WjfImGY5bXXZTlg/WDs4tK6Qu79rpkvrnuae6jF6I95MzzGvxpcO7fWJnmfG6sBYrcM2xXZI3ExGiWsqvn3C9iR3IPdgC7IL" +
                "+0B0Iox4K2wHfg1DsCpcivxeekd3ZydLWdqHxo76GZoawGLNUH3IWNbo04aU2XIR+ZpQllsnnOdKUg70ANaHqsZgyVF4Sfw24aVj" +
                "3BshIJTkNglvlGaa7toszUEdoeuun6OvYGItsy1282hTBeta22h7RUeGvaZ1oJV1THD1d6U6h7k/eZq6T7tFyB1u4O2j/0XWmja6" +
                "BesP3o/3IBvwQ/kfTDUiFZ4er4WcwEP4PEyLdyYr4EuRWvhUfj9QR5aZHaxXDuuHGZsYd2h/ASBwue6w0agvC2Yoz+TnUoRrQxdn" +
                "T7J7mb/QXshDtAHN0D2piUR/Yir9i5AiM9Ig8ZvEKz00lKaP5qn2vfaKrrqmgu6tvqnpd+NSw3dDQ9NMc4H1uW2N/bB9rP2j0+3+" +
                "4Gvqe+HPhQYHfw7OCHdIbRe8Z6wjQrq39jFWDVcEccYHofn4RaIfUjd2LD+c3zJhxDjcQUyjd9HpZCdysrzJmGl8q6kODgM76m9b" +
                "cqxdzLs1F1Q7rwbLgnGxHjeVVeh5RFWsBd46OSzpIbToaOw7uZRtx3cUGnIoN1k6BWZr3qjMdoRGp3kIXgJPgjBIAv1lRqwk91O8" +
                "4F8aSDtDZQ4jjXUss23ZjlRXlqcFdDKQHqgfWB1ZG72ThkTglB+RZakvvIN1g5jxQlJTSqmM3/5R7Vub7zUKdfCr+On8cIFUGMJl" +
                "erzK1dZrUN04cAnAGk47Hjo1juvGfRqfslVLaS+B/YQ11GksiIfILHwB3lLdK4z+TkxK3qILGAtP8q1FQWwklVMua65qpwMllINS" +
                "JXmU0hwElIq8j01jd7AtuZGcnZWYexwkzpaKglX09U2VrG7HCJfXsxvqFbwXIoMPQ2uD7UM1QrNCOf7rnhPuD64v//eP3UL3XfAa" +
                "Vq8gBd5NdETHJbYVZiScSO1kF6ojXY9pz66SixjuqJXmre6UqZXBrDkFHNSW0HyUKou9WTO5Ar+Hy8R84jauqFzscJKkndwz7g8J" +
                "kUYrfwI9gZfKfrC2ZidQQ24gbFBZdGf+KeOiqOTBJJLUsNO4HtwNthOncA2Em3JpTXndLsNqw0f9SANnNttLOVFXrvuc65DjZ8cP" +
                "x0HnBle+S3JYnFb7Pscsx35XUc9H7yaowDcTSvVztonCB+Qw8UE4y/6UHJJoCh8o/AvlsaPkMJrmlsmFQEPdO/mLcAq4rt+qgZRR" +
                "SgVlAD+FB9j+yarkC2JEche5iJqMLyaczJ/8Z24Xf12ZCLzUvAYHAtXUyjhY4sXFIsa2px9R39gl9InkK2o7l8n3E+fIK+SScr68" +
                "SnEBx4BJQH2V4TXU8RpW28+gNdnN483PLB2sNscslS9dcYy3/+K84pjlqeeNu5d5Gnri0F0oPZAWeBEYHVwZmGb7iW+INIwXw2bB" +
                "RnQI3BQ5EXMgQawv0YapINfW3zHftb41ZIGHgNLgR2mgPFlh5TnsbqoFfjfeM9YifgRpS9joMfRHhua/CnZRB7zUgcYUUxvjEU1U" +
                "Ksd7mZrUHvpn9grdj+7E4JQhOYUaz3j4FTKsKaGtC9ZVror5wi7RKldX1oMJrUv3UHtL+0G7X9/LvM221iE57e4Jrp+cqGO8o9A5" +
                "0TsGUoGrf0DgYOC+/zq0wD/UPzMYDXV0NxKjhS2yfTlzcxsVNIYJfH/yKJvFTmQ2MEHxvKa1dbT7jP26caHWoJyjs7CX2FzsGT6b" +
                "rERGsPREt1iT2ES0UrIBu0nhjEcsbU0eY0/dQzAVnAZslPuJlfgZ9HxiBZ6JbEhY0VfJuexsvrb0TFqmPADygBbAcaAuMFBhlc7K" +
                "NbGWsEU8KxuA8ZqA/qRhqumTeZ1ZNpttt+16Z3H3Qs80z1ev1d86UN2fDa0JLAsMCF4OEIEegetQxD8JCrnKK4fi1u9Dvw/73uT7" +
                "6fxzWDnWKw0DiwJl5W9yNjhaJ5q6WvINuO5vjUe4gFQtrFE4uGBvQc9ECIOIhsnrFEU1pfszITGpWWXyWp5aqlmfmKboxgqHidbw" +
                "sMQGBEa6EdeTTZgm7BY+R+Velbib4kiwu/azTtSC2kyAEnqxeqYoO1mor6Ca0obJpgxLU+sSywPzdEtP2w1HcfdW9033Lq/gW+o7" +
                "5s3yzvNVhUr7x/trBwohSPXNdKgN9Ng7xNqWh2OW3Izv3b4M+DIhL4O4IrTQ/tC/0vu1MzUAcEzN4bNlI7AVaAgs5ieTCtIpsbug" +
                "V87W3PeJueR85ghfUd6gbFJswBRlsZwpPVNeAyNVFvuXcJLV0dWoi8Qj/AqhYXpzZ3m9+EDi5O4yKf0i9ZY80jK5vtJVKanMAx6D" +
                "pTTvQKcmrG2hu6Sfbcg2IuYtNtRex/nEucL12NXCleXq5x7pFbxXfQ/8qwNZgROBpf4mvg9evYrRX3u3u5ear3IT4Bl5Pb/V+rzo" +
                "44JsS2Ih11+zxzTH2t1+05KpuSZ52DPUNTKVslEaSkufpu8mu2GD48MLN8WHwQa0D96PKSOX0JK6abqpWpOmKVhbEtnmdILWsj7W" +
                "w45iX3FlhGZ8CTbIHGMP8TuEdGG69BRI0xq0fcAFylC5oXxBaiMZ5FSwqP6maa3luRWw/2Uvb79rHWKJWI5ad9gaOSKuJR495FAr" +
                "xWL/J/89qLa/GJTp2+0d46sGUdASaKZ9D8iQf8HTc5pnfXlfPGtKXgqKcitBty3VNd6daj6vsctN2Ib4pMSkhB7eg7mSe2knXfn/" +
                "cXQO0HHsbwMez87aG9W2m7q9tW3btm3bqW3c+ta6NW5tJ8vZsWe++X/JyZ5szibn3ff34nlyFtx35iG9h9wXHR19Gt9Md+FLqevB" +
                "LPyYRcdSkHzAeO23cIHpmJxKd2FB4ZRUS6sNbABuA5p+W3ssxyRaeiH3UhWdBUV4Fjrb5PkMdA0CwIehqfArVMUvEB3tJV27TA9q" +
                "6+npznR3czV3/ut0u0t5D/veBmYFb6fkSmVCTwOr/NP9/X1v3Ussb/VOXEXycs79H6W+VvjxJXt1fBG7TKmIbCZK2F5jZZGrwCy1" +
                "IDci4Y31iX2Jh0iSGsSW5qsIzfjRTKfk50TLBEReT35g30mVjMNwfWwztgrNi3Q0LkuH2KLMUFqnz7ILxM3aNNCCnEUWIw8hGliv" +
                "PZWPSK+kX/Lq/z3CBx6EPbZMM7vKYftunWG9RhQlLhL3rbXsQx0NXbS7mfeV95o323vMO8ULeHeavaJ6B/iyfYf9CwMNQnsDk1w3" +
                "0EfaKTPGdX/Sfxz8sfd33/D3RF4e1CshubHCaG+kArRLyxSE5MT4odik2P34nuRN5j1fUCorfRJsfA+mBrU50S6eRjanWe6n9Fhb" +
                "DjZE5iDHoa1GlspKx4QOXCOun9BBLqs9MbZDP5FDaE2kPZQK+PXC6ky5onxa2an/AduhPywD7IVd9U2qO+sa6rxjP24rastlS7GH" +
                "HUXdE72ZfmewUOhxcH7gpu+Nd5ynlXuRy+Ve5/nsgmzl0V5AAcXFbUlYo6Uj58IfImvjb+in4nvzrB8Am6D85p6oI9ZgTyYrJh5F" +
                "e8T2xOcls+nf3GNhhbRS6mdusvvMHDqL+krlpZuyfr61VExFdVJfYAw0BP2Y9pfKKmHlidJfvaEdNU6Bl6Em8Ev4KPwNGg+WBEYb" +
                "S4wPxidgLrwQ+4+YYT/l3OlO9Vb1nvZ8cEuuHq7LTs3R23nWVdpTwrfAjwdzh4aEkNCqYJfAEf90t0yMhgdoMTGD+Ub+F/8dX5Mo" +
                "kbzD1ONbygmZU6epZ9UUZZN4mdtM96NukX+TB8nuVDO6LXuWWyEMEqoLNXmRbW1a+VD6X2oLbWeqcqDwTJwlP1JOq0n1qlZV666d" +
                "V/NpI7QX2k29KlATLAb54HNwZeQLvBROQllQBWgf1Ah+ipzEzlpOWAfZmzr/dVk9pT2DPGU8X92b3cvdZTyCW/EM9773XfcXCbwI" +
                "dAz4g4UCM7ypjo/YGnCcOkDIYX4n35HNySVJnW7BvREmin/LpySPcke8KDxjZ9Ofki9Ilgwmd1Oz6adse36I2FG8JzYTDvLt2K3M" +
                "aPoa1ZmqSO2i5zBXOJ6PiQF5l7JZbazt0DppT7WwVkN/r281jgCrwbXQ3/ADZD56Gc1Ce6KNzK/ZaAb2FnuIy5ZW1ojtsqO0q6bb" +
                "4fnjWeTd5T3nrevt7BU8Pm8N70vvFt93X+fA4sAD/xX3GdsbvDr8Ub8k7+f/YjvRQXoN/ZJh2KZ8eZET5kqE+ESowdkYMjmMfJiY" +
                "kUgjX5FJagj7lt8gPBBVsaG0QXjD5+W6s8/pHVRPahP1jM5hxnIf+YfiFbmC+kltY0Ze1bSDFH283tnIAxhATegmXBPtg6nYRDwH" +
                "X2YJW1ACIXBiJfGHuGa9aUtxlHS2cK11f/DYfEd8v3yc74Pvh2+tr4bvtJfy3vJFfV88dx1vrB0sY9Fd0H96UmklFRPLCjxXn7vH" +
                "juH+Ya+xm9gz7HtmMh1L9kqCyZOklyxMpifPUBsZjC34/4+x3cn34kvyDr4vN8e85TbmK1OPnczKrMxNE3CJkGXZr5bV9mtnNULf" +
                "q7cza3slcA70w5uQiWgTrBo+17KdKGI62TfrQNsnWyX7SXtrxz1HaafhTHPn9lTxPvbW9830vfbl97f3F/XzvkH+T/6QH/Asc7S0" +
                "sZaKeHNkMvhFv6PKCi8tFc/wq7kd7FtaowBqbDKTPBt3xm/G8sXHx8/FdyZmkcOpMTTBZDE+9hK7jQW4ClwNrj3XjbvJ3eZUrgU/" +
                "nH/D1xLyi7mlbVJPuajiV0+p3bQC+iddNkJgBbOyeyCT0KZYBi7jqiXF+sM631bGDjoEx0tnb9cqF+x+ZE6R9l7At9oX9lX2D/ef" +
                "8n/0a+aeWhBoGijqm+E6aT9rXWvBsSzos5Gt3VY7KXWlDOEFl8ndYCrSJ5PvSQt5MD459j56J6pG28Qex/bHe5IFqYL0dboXU5Rd" +
                "xf7DiuwPFuTGcADv5DP4sfwvvoNwXlglzpX6ybq8T+mpGuoOLaSvMStlGUCCLtiBlEJbYw3wkEW2gFbGesnW3m512J2Sc47rlgt3" +
                "R9xpXtE7xHfdV8x/xl86YA3c9mf5f/iXBGoHOnvvObvYJ5tRn0PHQjhQX/+ltlBaSgOFsnwRrhKzhzqRJMm3CSP2Obowuj36LSpG" +
                "+8f6xjPIpcnOlI/+SV9kVKYhe4X1cB25tdw7Ljd/gK8jzBHOCHXFBlI9+Zc8VSmjCuocs0bW60UNyKzuCPgV8iBd0X6YF3+Mf7FE" +
                "iVfWSbbK9oqOes5GLpt7o7ugR/GEvWt8pc0c6/4OgQuBZ4FbgTWBKwE4mOEf6W7h6Gg7RgzGUxAUfKpP1oKqV14swHxB7iBziWqd" +
                "XEFuTBSO74g+iPwT6RK9FH0S7RfLju8gDyZzU2FqNV2I2csE2GbsL/YqV4Fvzu/guwu4mCluF3tKfeShSgl1v1pBA/WregVjjzEc" +
                "2AtehcbBLZER6HKsOy7g5yzHiUfWs7a59imOZc4JroLuXe6unsHeGb6C/oV+wb/S5MquwezA3UBK8GvwS6Ckt4Ezn/2X1UH8gxaD" +
                "Dhqj9b5ae6W+GOSnc1PYirTfrOdFib7xK9GpEXvkRmRRdEd0W/R77FuiSHJwMpk8Rc2lGzP3GYMZwi7gOvF/8T34ZkJxcaJ4Rmwv" +
                "VZVrKAXU8yqm3TFpcrPx2zgEWKBaMIg8R16hb8yJt8hiIQ4RF63nbU3sYbvhaOEq4N7jHmpWc0dfRf9nk4OHB8KBRsF0k+xDwbvB" +
                "kwHUu8153N7I1pqojD2A8gNL9PXabGWAiPLlOC+rU2WT6eSf+LnYwGjeyO5w24gvCkRt0WaxMolhJJ68n2xK/TEN/Sizi+nAfueO" +
                "81P4a/wLYYX4QmwqPZR6yyeVXNoUbY62Wv9jdAa2AZvBz9Av+CtyGu2AlcW7WPIQFYnrhGqlbWvsExwbnZ1cfnfYvdpTzZwZmf4b" +
                "fso/OPA5oASAYKFg92CfYGl/Y/cgx0dbF+tdvCkyEZxhFNMT6iS5nrCKe8wa9JXkSjIrUSx+08zwsHCtcK3ItUiPCBX5HpucqEVi" +
                "ycvJi8m11Fu6pjlBgtwUfhk/mh8r9BYPinUli9xPbqtU1GrrufUtOmdcAzAQhUbAPZH+6EKsBJ6Fn7Y8J+KE01rcNs7eysE41jpL" +
                "u1gXaNoG4F3vfem75Ff8uQPtAnsC/wbA4Prg9OAPn+YS7OtsPay/8JFII9BplNQltbYs8e241axO68nFZMNENHYr2iHyM2dpzupw" +
                "MPI8XDeCxLbENyQ2k/6kM5lBZdFVmX7MUbYUP5/P4ucJmeL/HiMyX74uH1Zea1bjpV7TOAEMBl+DUciG3EOs2DrcbalpGUjMs66w" +
                "frTqtnf2To4W5i5Z50pz9/CU8M73lvSVN/3ovX9+ICMYCzwPCIFCwcKBIt5Dztz269Y2xBYMg+eZpPxFiyuoNJpvwhVm29GZyd2J" +
                "yfGxMSnSPjwxBw7vC28MZ4WHRyLRCfH+iX8TZUgk2YBKUMvpW0xRLsFN5iP8MWG7OFFSpb7yWKWUWQnVjRvGUuAQeAKaApdFyqO7" +
                "sKd4lqURQROHrDVtm22Sba/9rSNgGlBd9wf3Dk8bb3HfN19xf1+/PXA8gATDga8BPTA8uDzw2dPXMddW2vY30QLbCSHAPv2bJip9" +
                "xAmclRvOIvQysm+iedwf2xbBwh+zB+UcCS8IL89pFW4QPRpbH6+QqJKYlHAkP1Nr6fLMSnYv94YbyV8SuotXxMvSE3mdUkJrY5QC" +
                "igPp4B5oM9wR2YMewGrjBy0u6wTrFetV21b73/Y0Ry2n2/W3a4u7iKeVB/Gm+Zz+Zv6pfsM/LhAPTAr2DQLBnsFxwZO+Sq5K9va2" +
                "dtZ0yxWkN4gZmfo4rak8m9fZnua+yzE7rXaci9aIiuF2OV+yD+ZgYSBcKvwpnBPpFjsQfxq/FneQd5KXqXn0MmYxe5SbyUNCf8El" +
                "Zkq15H/lvcoIrbRRD8gN5oUawkuRr+g1bC6+xfKJ4K2zbUkz1voO2jHXWcxlMeeu7tngDfgW+Nr5ab8/UC6wN1A6OCN4I3gxGAse" +
                "C372XXdNsTe2bbFOtYSRCWBcJ7Xl2nf5Pj+ZncScp8tRlciU+Ibo00jRCB6+n/0ou37Og5wC4bnhv8w5fCM2KL7NrIYf5CKqH12O" +
                "sbLduPI8yY8UlghtxdvSe3m6sk3truvGZHAptBieiXxAq+L9LRUIgciwabZ69ov2Lo6o456Tct11T/T08/b12fw1/Rv9ZQORQM/g" +
                "0uDh4P1grtCZEJhCBXJ5Rzlb2FfYmlpr4Mvg2QBsLNG92mDJxVdiGzBT6PvJMolP0UeRP+HaYWdO1+zD2RdzssKOSPnI9YgRnR5/" +
                "FT8Sl+JjyN6USN9kSrA6W5CfKpCCZE4xTuqprFKztNu6G5hubrMIfB9pilF4LeIjMd060TbcXsixzkE6Bjh3uqp5HN4R3kfeq76r" +
                "/tH/3/8DzBivh5jQv6FYaF8o7q/uyXaUtte3XSEOYZXgAUAuY5k+TU2THHwudj0zyCSH7/EJ0V4RKZwIu8M9c4bnjAk/jYyILolu" +
                "iE6NrYkPT4TIUeSG5DrqK32bmcvm5xTuhemO282sjpcuyX+UbDWs9TNAcIfJxFuQk2hdnLIQZv/PtrW1d3ekOG86C7gU10v3Tc9s" +
                "r8X3yffQ/zZQL7jK3F2jQrVSvqaUTnWljgz+9rZxdXF0sZ0mtmKV4aHASHAuVBuMaX+Jw7lu9CwyFkMijXMuZEs5HyJXIgcjLcK4" +
                "ee6fIv9FtkXuRxfEz5FnqFP0KqYN+5P9wo3nn/EthJLiSTEu/pbuylnKCTW/dlw7o/cCHoAEHEJKoj+xV5Zi1lK2k7aA/aP9mKOI" +
                "s5zzunOqK7/b7Tlkzta63ufelr79vtm+Yb5ZPtnn9w/0L/Y1dj+0y8QUSxm8CUbC18BUYLYx3qild1MOiNl8ff4TN5EtSb8nI4lX" +
                "CTvZLuGOV4j9iVVKVCX95I7Eg8RGcqc5q4pTRajt1CNaZl6wmdwLbhLvFl4Ks8TyUrpcVslQrVqmPtu4AFw3a3U+/B4Zgl3FF1oA" +
                "4rg5rXbYStln2QX7FYfdFXBfdBfzPPLc9D71jfEfNolxdCAzuC34MkgHVvhKeGq4ijr7OWB7K+IJdhrZAi+BOgGFtfpyGdEj3OUe" +
                "Ma+SwxOfY5Vjf5s0syD8dw4QDkVSo0Ojc6LHorNiN+IfEwPJz+TM5HkKYeqa/HiYK8Xjgk+sJFWSmyqN1IbaDP0/gwdsUBx6Cn9A" +
                "SmBj8VmW2sRLYqG1mw22r7MTjtGOYs5CrrBrpzufZ6ent7eCT/Nd83cPfAi0DX4O1g228uPeou5Srm1O3DHK+gV/jV5BBsMw+F0L" +
                "Kp2kJuIHfhE7iLKR++IfY9+iSyNFww9zpoVXRvzRgubcehFlY3DiYCIvuY/clxQonPnO7GdXc2P48cJgsYJUX56gzFb7aZ30Rca/" +
                "QBR8BV2CbyMa2hQfakkhzpod1cWWy77BfsdexqTxz84trupmTgeZ9FLZ99zX2mSBRYHqwSvBBYHFviWeQu7NroPOBfbTREv8Dcoj" +
                "Q+FdwFitvPJTuiYKvMEISS7xKV49zkdHR0Lh5znLw6MjHyNQtFm0SKx1fFiiJHmHrJpMoYrSmUxVtiVXhqf534IgpsodlP89262j" +
                "vt4QgRJmNlfDzZHu6FJsG97N8tSyjBhgLW/7zxay97KT9psms7hdY1x3XE3dTs9FTyXvGW8p3wtfb/8z/1NfY+9C93DXGedjxxdb" +
                "X6IWfhU9g0DwLGCvdkhpKleXlgofWC9tSb5LwIn3sXHRBpGqkbuRrVEjWjI2KtYz3ibRlCTJocmwSYbb6RFMZ7YuFzIn6SvhvvhG" +
                "whRaea0e0a7oXw0X+BScCLWE+yEH0CfYWjy/5bflK3HPOt322lbCvsXex+F1bnQ+deZyrXU1c/s8+z2aZ5j3k7er75oP8K3w8K63" +
                "zlLOoY6HtulEZ/wHeh9ZBrcD/9WPq3UUlzxbBPjWTAXqO/kpcTJeK/YqMjHyPbIuisdCsbGxlmY2B5ouvy1ZkapJN2cqswh3nTvD" +
                "fxG+iz8kXq6iDtP26jeNY8ABc+evhhsjf5Ct6HSsA27g40znnUaErM+tpW3DbVdttexXTavJdDRwLHWIjmnOG87fTodrlOuRq4Hb" +
                "4y7hsjhzO9bY47Zn1iDRFP+CPkFuwmkQbWjaXnWG4pbLiQRfkj1G76FmJpuRRrxoPBED4n/Fd8fxRI/En8QVckrSTu2gytDP6c3M" +
                "AnYc14gvbjqBVSov91YmqJu0Q/pT89zTIRy+Dy9EOpuG68GP460tPy2TCNi60frdWsA20HbclmPz24vaa9hb2Gfbd9sPmTuLtmc4" +
                "6jpWOr47pjtO27/b6pr7E7bKlrb4V9SPlkGKwLvBO0ZlvbDGKVtlQfQJG7hd7GKmL/0XBSRrkH+RHcnWZFdyBvkPOTaZnzpD1aJP" +
                "mj5AsBH2krntCwpRIVtUJFxJUyFN0hAjF1AAdEGvod3wcKQWimDLsfdYOzzHNHKA6EWsIR4SiLWKtYO1h7WrSSzrrBesPls5Wwlb" +
                "G9sksy6c9hvmNcUkxCSxniCIAhYLzqN/ofORPnAJ6Ccw3ehlOldpdYj8RzwkLOWXcCvZqcwc+iHVnfqL8lJvk2SyOXXCPPNrdDXm" +
                "ANOE9XE3uB5mPn8Jc8RGUl1zfoZMJ/yq3dN3mRY7DUyD/oa6mL6yCslA56Kv0WrYIQzFm+Lz8O94Vcs4yzozy34igxhG7CRmEaPN" +
                "e7CBmE7sIe4Sj4grxFyiCZFGLLOkWD7ig/HO2FY0hgSQdLg7VB9UjbDu0gHtjXJBviCdFR8IMT7AV+TGsnFmN9OXacA0ZtoyQ5hb" +
                "TCl2I8uyszg/v5/vKGjCOjGv9FhaLXdRguoHdZvWWQ8a740pAAoeANtDHDQHRpAhyC2kGnrIzHAzbBF2BeNNCpyCr8Vv4HGcxz2W" +
                "XBbUQuNBi4DfwSM4ZlHwgpZ2FpdlK14Dv4u1xW6j3dEXSG2zp3SoFrQDfAQ8NUi9td5Pm6nuUA7IJ6UHIiuUFsbyD7l5XAsO5MKs" +
                "xGpsXm4R94erxm/kCWGZkCEeEItJf0ul5XvyQnPiq+rfWkOd0tcaTYAwMBYMg22g21AleAb8Em6BbEOiyBT0JHoCFdFyWCOsPTYU" +
                "G4W1w9KwfFhfbCl2BPtsVrYN/2Den1PYNuwwthjLiz1Eb6Bj0ELoG6QZshymoLrQFlAHcgOVjB16Cf2AJqq4WkvZJGPyYOmOiIpT" +
                "hbICLLznz/Cz+BX8R76JcEGQhVxiB/GR2E96IqXIw2REWaEUV4+p5bXTZryifsioAIwDGHOj1oeeQ7XhkfAFuCAyGFmN7ENuICxS" +
                "Cm2JlkCTSA5SBh2LbjQjeoiSKIGJ6A90P7rHvJZt3q/T6Fm0l9lXN5GJSBnkFbwUrgW/h1BoG5gBzgX+NVS9rX5Wa6eF1ZbqBOWx" +
                "PF5mpdmSXTopDhbziphICj+E5wIslhWbijvFhNhY2iaJUnf5hlxCyTLrtrv6nzpSI7WZerpxxEgH7pgclA8aC/U3c1wE7gZvgd1I" +
                "D2S6GfN7JB0lUK/ZOc3RFegx9DZ6HN2AbkHnoMPQ3mgXtCyKo6koi/yD9EIykRdwc7MKHkProJZQEpwPFgS/AYsBFBhiZOvd9H+1" +
                "jto2lVUmKw0VQV4s++Xz0m6prVRfqiiVkSpLjaTp0iJphDRUOizxUqZ8Wk5Rhilfle4qqq3SUvRl+he9pDHXcALdgUnANdNc9oMK" +
                "uAoqA12ENkJf4JXGLTHT2InfssDgWl2CbLgF+cfwwV2sQ21/sIbQbKgMdhS7gFXBPQRgIfDR6EYER18ibrQjeg7Ni1aCw+AwaDPw" +
                "jxyXBaWGNJvP5juIX7lTdB3aw63iu5tOUlWkpRviAO46D0v3pIbSZfmOulKpqTRQliu/lJKqT/9XLw7MAvsaWUAfkAanQmWRDugG" +
                "zG2ZjK+3XLIUJYbaWtqLOXY5cju6OF86G7jd7kHuG+6Qq4h7vLudZ7E7y1PNu8Oz3uG2VSeGYK+QGvBKqLFBqpT4R5jJLqHXJv9L" +
                "WqkYuTIxN14s9i68LMcbcyf7xm5SO5medOVkp8Q+0qBus6d5lzYCyGPyzWXtsjpLuiqE5ONqG62B2kR7rw1WCqor1IfGADAIt0bG" +
                "YfXxBdh3fLx1taO/66ynTsAZqp7CBh8G+4ReplgzXuTKmy9v3kL5khnXfCNt/bHPaB/gt3FQ/Shup3MSU6PLs4/+fvx71J8+2TWy" +
                "r/zq/7PBz12/q/w+E14Re5Z4lsS5wdJXYZP8Tk0aXw0vlBeuBhUGs4FaxhytlxpUXJJqul4utiZTjPmL7cVs4EaKg+Rj6lWjNtwS" +
                "pS257IzrhndOsE7qlIxzuQvn3ZRvf4HxRYYVbVZsTbF1xUsX31Hwc8jlKItVhQco4/k6DEhh8d6R3JFu2Sd+QT/pnzt+f/tT6U/8" +
                "z9lIzUQ/chTl507zB4WScgezmlKAleAseAU8HXKAa4wBxkvtgQLIj6Xy4li+AjeCrcEMpqszJ9jT3Aa+vJiQ/9OyjS/gAuRfvLz1" +
                "hV10XnU38672NTaNPRiaH3of+phSL7Vw6pKUbimzU1Z6phKdodZAezWT38lUpTvQpZJSsm5yEsnFe8S3xE+Ts5MTk0HminBaHitX" +
                "UPdpf+kntKPGWPAGuA7qDWfCz6FDYC+jhdZbNeR6YlvhGz+W/8w+Z7+zW9iT7C2O4leI8+VVal9jPJRAGmKlCMrGOrq5SHcu7yav" +
                "w0/6MwK1AulBKdgutCy0JjQ8uCaUK+B3BNCzxhR9mXCNmUYtpKpSA6j99DWqG1U1OYD8Qn6m+lMV6JncT3mbUlMtqG83ShsxrZvR" +
                "zAgA6cBuoA1wCKhgdNXCyjglR6zER7nL3AaOZv5ho+xLrg0/VtgnVpWzlWPaGGAyvAetgje3FnIUcb12pXsGm8Q7yPvadMmzvnMm" +
                "nYeDTUO7Q0uDSih/YK19G/JQ366d5OM0RZYzI+tMuxiE+ZeeT/2VnJAsSL9JlqQVdrlcQdmpLtTPAFnARv2HHtLj+mczd2u17eaM" +
                "KaKdV4oq08V73CoWYknGTb+jrzHfuBN8dZMruyi71J56QeAkdAlZjo0j+thLukLubR7Bc99bwUt5PN6S3hteuw8O1A/WDy0MTg2l" +
                "hxp6AOIhuFyvIe1iLpPDyePJS9T/XpV6GdeFq8v+oT8yp2kfHWQqCq+k4so1rQNQGRKAHkA+460+VXOp/8nNFEIdoFQzP+srSbGy" +
                "8MzcgQ0ZymSgw8w5rqdwXkpXT+lJYwh4G9qKDEDPYO8t822vHP1cH9yfPHFvLW8B73DPHc9iD+hd4+vpjwSeBiqlpAdnuQXLE+iJ" +
                "/lPoQOdKVEssSqxN2ugk25KfKZTlD3FDuPzMALozvZdrz9eVPikvjdUgA/4Gy4NbgZp6YXWL9EtyS8ukYdJb+Y8Mya3MTmT5YuxQ" +
                "egm1nr7HnORqi9+Vr/pZ8DG8EY1hNfCylnJEO1tHO+6c7prouebt4/tu+ssPX1/fHt8ln91fwP/GLwZ83qv2AVgpeKL2nO9BLUvM" +
                "iZeJH0osS25mjnLtBUQ4LC4RbPxqdjR3n3vCucQtcgV9unEAbAT1hedBS4De2lAlIAfEusIDPiIsEFaJk8Uf4ieTIdK5eexedid7" +
                "jfspZCgvtTJgAuaxZRbcylod9pjd4ljo+Oqo6Xrv+ux+6Hnpveir6f/PnydQOnDAL/gJfxfXdWISMgx8rLTmLXSBZF0ymXhLzqA+" +
                "MMe5iXyUzxRHmjR4imvOW/iOvLkGpIHqUq2LsRXgwdngE2O91lP9IWeLY4UvPCic5+sLi4Va4mXBKazk3/EZJi/cEBZI/yjftJnA" +
                "ALgVVsuSy/reluWY77zk3OmMOKu5SrtWuAq40z3PPMV94/2bApcDbOBQcISfdwqWUggOlJR+M8OSs8nHCTFxPlmRuc1VF3YIC8VP" +
                "YjvhK6ezebiOLMNu5RGph9JIm2rkAkubXHDWOK9lqg1lXLzBT+Ab883NP5dj8l9/0SmGxFZiVfGDsFosLofUfPoXoCRCYwOIRbbp" +
                "jqRzu6uPC3CpzrvO585dzoOuKu64p4vvlv9kICNIBbcGz3jzODbiNeDXWqZQlOlsTog5iRXkAcrK9uW3myczSCLFGkIevinXl23G" +
                "NGU/cr8ETB6rCvpHQAZrgjeMOvoctaD8RdjAX+Y6c1ncRn6V0FfcJ2LSRumLtEBqLwXlkLJI7aQ7QRI+gzUnntrijlauL66rruEu" +
                "j6uGU3W0dOY4p7reue0+a+Br4G3wW+hkSPWud1gtf8OVtX/59XSbJEzS8XGkhdbY7bxH7CnNlj1SIwHj/2PbMX6apmex+/hT4iJl" +
                "r+4AObAr2BKwGc/VpLkZ6/O5uV7sQ/YDhwpvzDxtNn93vdxCHiXjyiFloTpJjwNz4QAmWD7Y8juvu4a497onuG+5XjnHOLs4lznD" +
                "zpHu2d400+FnBBuknA9d9rVzliDeI8X0G0JbBqPspBzvRxaiz7FT+LomQcTlMxIoDuVF9jddlDpMfaSPsCP4VZLLnP4J4AewDKhm" +
                "YFpl2S8W4zezN5h0tgk3hF8i/BAPy6eUz8oHkzmHqj1VWu2jdwMmQT6UsLhsRxxnXJx7lKe6Z6z7p6uE65CzndPvmmhuKNq3N1Am" +
                "dDXlV+ibL9WFW/9C7XoPkWMC1LzE2/gK8gfViE3jN4seOSqPkuqJs/ldbFl6VzIf9ZmawEzn+kjbVY8xGXCAMWCm0V6bKpcWH3FN" +
                "2eqmGc1kd3M8f0Y8Kh9Q2qjLVFz7qt4xeXiUOc9egIOQifgKa3HHZBfl3ud57ZnqqeiZY0Y3yzzddR7At9vvD3pSdqUOSRnn/+KK" +
                "WuehffXHYnc2msybWGduyAfUTybKLRM1kwdp8Y7wkvuLOZrsR0bI/skMeh97VHyg1NVxIBu4Ahj6WRWTrwl9uNNMNt2JKcCO4coI" +
                "HaX8CqReVItpk7VULaYO12brUYMFLyBh/Ld1lwNy9/c4vYj3oaeNZ7ZbdSmu8p473kn+qsFSKT9Sc9IWpHT0F3a1tvZCTmjvxc3s" +
                "AiqD9CSj1H4G4ALCv6JPqiru5T+xbWhbEk0k4lkJkmxKJ7jbEqzm15sZ+40Kegu1vPxLeM7VZ58xVtbGVeT/EyZKoHJYSVerq4DJ" +
                "8KnKFrPO0kx2zAVXxZoQde0fnUvdMfcn9wb3JPdd10zXGddj93zvHj8QiqWMSTuQ/jC9e8p8H+kYSDSAW+slZaswjC3CYJyFdwg5" +
                "PCw+ERT+F3uUzkp2TdSOT479GxcTAeoXc0TQpDxKYXW8SiuZMiXWFlrzs7lv3Ci+mtBF7CVNkxNyaeWkPNz0oOfSRanD/7+mjWpM" +
                "g2qji/E3xDkb5mjtGO644vjkqOB84Ay7SnhSfEMCS0NFU2ukGWll032pZQIH3Dftpy3l0drQVOOYeko25N+yIclCMQHmn7J5GBfV" +
                "gMyKV4vnicfiHRLLyerUD2YlV8h0LY+wXmgpzBcsgsanCk7xrHhJfCsmzX7WxOfiTNO9cOm+dFh+r3TWfEYAnAn9gkuja7DulnVE" +
                "Q6tqLWif7pjhfOcq69nm9fgfBcaHRqbUT62ZhqdXT60RfOut4m7hWGEdgv+GGwGL9e76Yu1fxSq3lgaLPYRW3GP6PQknlNiymBBt" +
                "G02J2RL1krupS/QkphxLsavNbTWR283V54cKd4XSYmNxqTjOvCwg5hbni5VNw+qm7FM9+nsjHdTAu9B2+DoCYmvx7ZbjRFfbYEc+" +
                "V5ab8Db1ffIvDJY2SbFI2pT0uhl50melDAnM89Kua46TVhy/Cg+AvoPvgDm6rpyTHglp/GnmQHJlvErsaPR3dEO0STQzujxWJlGR" +
                "/EWmJ0tRh+nd7FQuydXjI3xjob4QEFbxo/k95vdvhaPiOjNXExW7ulT937NrfabtFQHXQ9WQYWbGDhNLrZj9gKOM67K7oXeLr0pg" +
                "bmhi6pW0celzMvLnsmRMTx0T3OQLeea4CjjOEwCGIvehEJhPL60METvxRbnXzDBqVSJ3vFfMEgOilSP9wp7IhejY+IJED/JGciFd" +
                "n63Ktecqcis4gO9gOvROfitvE44ILrG5+Fu8IW2X+5qu30Bbo4eNDNAASWgEUgfDLIOI3dbc9m+OE64FHsEb9bUO9A11SN2Wtjx9" +
                "Z8aiXD8zomlUiPIT3jbu6w7cehdtBKvgMmCz/kPJI63nr3Bz2JH0DPJpvFNsQbRGdF5kXzgSjkXLxfclFpHDkkHqFd2FvcnG2Orc" +
                "CW4xV4Vfzm/nL/BDhBXCLqGX2F0qL2fLuLpefaFVMrYDo8BtkAZvRefjVYjF1s22Po5vzp3uUt6tvgX+LsGeKWRqvfSNGVdyvczV" +
                "LePf1J/B8v7fHtFV2f4SnwtPB28b+fUzSheJ4jtyMnuCGUQ9SZyKl48tihaIbohkh3NHq8WXJQ6Ru5N7qXp0B7Y/15ojuJ3cHG4G" +
                "t5gP8+PMvA0QagoHhLBol/OZnZRHjZqe+d1ggSOgH76ErMaGW0Zb59vu2tc5i7r3e2r42vuLB7yhAqkj0g6mH854kKtvro3pyZRx" +
                "wb9939zLncetQ7AWEAk4jJPqD4kUxnCvmFfMCfoF+U+8aHxMNCvSJVo5OiJCR92JhYkhZA9qCL2FrswW5Pqw+9mKfEv+HPeAryV0" +
                "4ffyp4V5wnghKX6Tdkgb5NZqJa2/Xh0YCNaCPsIgdg5vSmA2xbbO3OYRdzfvR995f/5g65R4KpjuzlAyvLkb5vqQdj70LbDHO8c1" +
                "wD7NMh4pCM4H3mq6uftKCjbuGzOZmU0NTUBxLJ4WvRL5HoGisyNZsZmJeKJU8iS9g1nAVDDPMD+3hysvjOGXcnf4ngIk/OCzhdUC" +
                "LO6V7ssT5VzKE5XVXuhFgGlgYegO/BIN45lE0ppmZxz7XTbPSO8G32lzEw1K6ZPaLW1A+qmMvrl+5oqm1gh99Dv92c4M+2vsOdwB" +
                "sABevaLUip/AtebfMbOYnGS9xMfY0kTv+K9I/0jx6M9oh/hachaZl2SpauwLNsJeZ3NYnbOZlFiOb8fdMIlpiPCBXy9MFoaKm+SC" +
                "yjg5S+mt7zCmAnPAunAGUhgrRlSznrR+sr1wbnfX8LT0PvXlDVQODkxF0gal/ZdWOtf53LbcjVOw4Gt/fv8Hx1yrhkyCjhsrgREa" +
                "zb9if3B3hC7MFXo9eTc+Nf4q+VfCGnkapmN1Eg8To5LdyMuJ0/Q1fqW5Rc8z2ex9brpAiHfYt0xQaCZdFyhO4/MLmFhSLqtMk0hp" +
                "r9YOmA0oQD34JjIUU4g9tlI2h32Ua6h3nqeTt6n/ZsAZPJK6Jr1H2ra0rbk25/2ea1KwROCY/7gPtRcjpkEdgZJGKtBW/W1yQip3" +
                "WizEfKIexofGx5LnqFeJWuGUSMe4n8Kpp8m/E6XI2WxV0Sp4GQtTiPsg5Ige8SS7hEkXjkr/iI24+twX/q7YXn4nH5GOSuU1GbgC" +
                "rgd6QpXRWvgjIo+9nD3bttv5zNvQ18g7wTcqsCTYOfVk+rj0PGnNMgrl6Z5vWcp9r+Z5a+7yenh/qJ4+UjdUxKjA4cnCVFGTmscm" +
                "KyQ8sV2JYdQHukGsZ87b6FzqlWmPU5IVyNl0F1ETv3MzqA3MPX6KtFzcyC0w8/dCLCZJfDfTxBVhmTRcbiYD8izFYmwCF4F+6DUy" +
                "As9rsux0x0FHyFnd88UX8h30EYFxITWkp01OH5W+Kz1X7mn5bOkhz133Kn8rb3PsJfBaZbQK6hRti+BJqIlRHCcVofbFT0U3Jb5T" +
                "1xmNJCK1o1nJI9wD9id1nVSo6UKm1FWwsKdokU0IWdJPPpsRmC18T3Gi8IiT2HV8E7GbnCUfNC10nTYf+Ae8Bj1H3mEhSxtrJ8cd" +
                "00qquVZ6UV8e37SAGLwX9IbotJSM6+mz0p9lTM3TN3TL9ce1wVfd+Q3sqLdTaA2TKitzmfKJd4k4t0coSn6Kl4zD1F2KpBuRm2Pf" +
                "4mXoiWxt9il9mF7H7BQQcYQwmo+xAP+bPyENE1rwp9nxfAuhg1BDKMnf5IsLReTjynyVUbZp14D10Cn4EjoNByzlrJcdhKuSs4+z" +
                "j7eer4oP9UeC/wVdKbnTrBl30uemT8v4nLtaSkkH67jlaeOeA7RRBkkx9bxpI5/YQrE58WcMLh0jP8RLxLXkU5NhN1FjTTKrR7fk" +
                "vrCn2c6Mje0ofBCPCrn5b6b3zeMNsZ70ml/F7uJqC6v5HH6BsFeYxW8TZ6hftTraHe0mMAVqalrTV8t8y0DinOOLa6mrsXOKZ6bX" +
                "5XvubxRcGVwdzEh7nZaS3jgNyyiS+2LKFPtz6wXXT3d1qIP0i5flOeItAeKrx3Oib5MhCWN2JmbGByZxZg5zimmTnJucS9m5I6a7" +
                "vWBt7F9CRGgkbBbyi6/4W2wb/oe4WJzBV+KOcz+5JkJDsa3YUmgvPJPfm5uzk14QaA09hw+Yk2MBkY+oZy/hcrlvOU+4i/ueeNv4" +
                "cH/1UK6UnNQCqf3M2VYmfWae0+mfHE0wxfbA2wJvIjdjfvEHhZniVqF9/N9wicQeYSD7PdmDfEba6ASzkKOoodRe5hV7lDvMXzOt" +
                "PSjcFH6b/JMq8uJATmG/8m/EfcJdrg8f4xzCb7Nbr4lhoaucW88wuul5gfdQHG6FcriPqEWMsW1ybnQWNWsul3e9t53vus/wtw1U" +
                "CfnS9qfuS2mbUj8NytUyyFsorI7joHMeMFKYxeQR33LFxAHMgtjw6GWK5xfSLagTZGV6PqNyjdmSTBrbk+vC3eMTgl+YwU0V5vHj" +
                "OCd/UXjPr2ZbcFv4H4Lb3J6L+HX8ST4sVpE1qbnUQZG0JgZnpEIFkFbIN2wtEbB9tk5yyM7HrrBTdG/zxr2dTTdpEnAF56ccTs1I" +
                "XZVyKmWceboHnAOxdVg95xIsKVdiurJluc/sNeEEuTKCxc5xce4DvYK6nSzB7OBAkWLHMxPZSvw44bHJsdmmJa0XivHvuI7CVmE6" +
                "144dzl3jdwjlTf7ew+Xj00SbXEK5IFWVI2oXAAKPgJ3hlUg2utKyzzrNKtuKOJe6Is7CrvOe8r4aftnL+iYHoJQ9KZGUvqlWM8L9" +
                "ab99O/CyaI6tkq2t9g/L0mO5vGxh4S3NRJaFT1G5+SJMY6YHs41uz2wVqoqdza35UTgozOE7ihskSGwjnOYlrim3la8odOHeME/N" +
                "ydHFZI91fF6+EF9MfCFblA7yc+WHSR57zFPNhw5Hf2O1iTnWkjbNUdjd3oU6h7v2eRt49/hG+AcENvqLBy+ktk0Lpn4P9k7rmDbb" +
                "6UUnYdfsDvSUvIe5QPf63/s5CBPI/JGjUZkLc05mFLWW/sbCQjFxB9eIu8JrYmtJF0eLhvBdbCbt5w9xRfnG4nE+gyXpd+xhfiw/" +
                "jLvAVuE68gUlU7Clc9IY9bzhAqYDBvQNWYoS+GDiD3HHetxx01XGCTnmurK9Nb2lvEd8CX9R/9tghdQBaUdDt1M6pWIZwz3ZWE3E" +
                "Zg1Y1qhruCXJBLU7+Zv/hyoV/RCmkp3Y/vRDtqVJP325jeJQ4QUviOXlPWJfYZf4QzwpFpSPiiRn5RaZBlWLX8Xt4PrzI7kePM+H" +
                "hLb8F2GdVFfOo/ytbTSGGU2BinB/9DFaG99nqUvcInLsaa67ju92v7Oxp6bnjPcvbxFvM2+P4PmU0ilDAx8DidDL1N6h3kR5aDVS" +
                "3WaHKHEjFaE6UHvZifzQeLHImHhVPky/YO6wC7kgt06YJl0z2ee7FJOPCReFb+IDcZr0RLzHHWdg/oM4WbjE9mT3c5k8w78SCggR" +
                "7hX/ROqnVFRopbY+8X/v6gnuQw6hBTAG70FQxCXbG6fkHOUoYD/kbOU56t3s/eCp7Snrv5pyL7VEqFCgYIhOuZA20pWJHAcO4Qr6" +
                "XDvAVkjeIN8nM4WBVDDaJvKb/shcZZ9yN9kMZje/T+4pVObmiJh6WBphRuaUqki9lJ5yPd7KnTTPGRNEriX/i/PxAYEXGJ4Q6krt" +
                "lDzKFUXWfhu0kQ+cAm9CiqBb8BWEZLlFqPa9zpaOEo5LzoeuZ+5W3sq+r56ot3SwX6hZ8GrAEyodKptyxJcfl4EdyDKiCbBc8FAV" +
                "kvmTZbmzfK/468iYRIx9Rc/hFvDNuIfcGvEvsSTbQRyljlL6C9uEBUJMqKrcV27wY5hSfHHJKpbgOa4ql8WNEgaKqDCZj4gpyjWl" +
                "nSKoN/RlQB1wN4QhRxAJ6048JDKJ7bYqzp7OWuYWQ10LXPm8J3xrfau9JX27AhuDW0Nvg20CuwPp/pPEblCCF+MlwJfiOKZBsh05" +
                "mB0jdE+WjnZLXGEGMxCfIxzh1nK9pSqyn58nzFEGKG9ND14uFhYGStvkguIt9jy3WxgjHOBT+ITpqAzvFFUhU/gm/Gdm97zySn2n" +
                "nzYw4AOYH/mOCKjPkkbwljfWO44fjqqO8s6Zroauje5/vPW9Iz3tfZWDF8y4woFwsGGwbaimszxSF6pnwdBe6hjhDW0kKiWXi1XY" +
                "UHxvzMu8o1twc6VM4Rg9gL+ifBc/C4Z8URkhTpBRtbp0VNgp95XesuPZz3xb/hDLsQfYWUxFVjH3R5D38n3ERrJPGaYK2lb9mI6C" +
                "bWEAmYWcx5pYbljqW22OVGemA3VmOb+5mrvD3opexb3YJLhYoFtgYvDfwOJAntD6oEA0hMYh4ywPwFnqVLEVlTf+hdkmraK+x/3J" +
                "w1Qzsj/PSIfYZfQY4ZqosV1Ft/JCHCAMV2xKOZGSlqtj5D8CKc6XDoq3xdbieT4/39u8JSfs4hfyNumKvEDRlOdajvZDHwBmwteg" +
                "fnAYDeIGlmmpaH1inWwrbF9oP24/51jjeueC3adcI91ZHsnb0VfTN8B3x6t4rb4STg0dhfTF1iHHdVIZJ3iYBM0K27lxyc/Jwswh" +
                "6jdV0HSCXqyPfylUZA8yV8SFsi7YxXKSYtI4pDQ187lS2CaNk86Lc6QiUinxrjBHeiHmEWuLTeWw3FaJK6KaS+9jDAOnQeXg/+Cy" +
                "2Cx8r2UH0dH20lbSPtuexzHCcdrxyGV3/nDed3R3HXZ18Uxwl3V3cm1w93IXcp+xT0erwBIcxCoA3bWLYlXeze4VJ4sCk0qXZFA2" +
                "P33BNORyXIQ9L3ziejGTeEzaL3YWy8rfhS18LWmx+l5uLdWXo1J1qYL8SUmRRfGNtFQOSq2kk/Jd+bpcSr2trdDa6W6gMwhBI+Gf" +
                "yBk0jD21PCWixBprMVtb22zbRdsd23Fb3HbUlttWxRayYbYbthv28fZ0e6p9vj3T7rMVxH1IXQRFV8FdgTHaK2ml2FWepowWt3Mr" +
                "uDPsLtZmkpth0u9BjmcvMVbTn7dwDYV80ifxnjBOLqAWUKuq5bQ82le1qz5Fz68VV0tpLbVTaop6SOmhYOpIzarjekW9hjEKmAAO" +
                "h8rDUZhHOmL3sKr4JByzFLc0seh4KxzDa+JZ+BN8EB7FJmIPsco4bpllmWhpaellOWnJTahEHmIA3hQBkeOwDHeGbgFbtLhyUymq" +
                "cnIt6ag4V1jG/8W/4HO4o9wa/hG/jXvLtRNOCoJpYfnl49JG+ZqyV6msONWBai31kNpM26/WUwMmiaZoHbR5WkXtb+2i3smgDBlY" +
                "BSIQAVNwFWQ7shh9iu5FKbQedhrdhaJYDmogNdD56BR0Orrc/MlyFMFwbCa2DauO98Yv4R3w8+ZlBt4JG4zkg49B3eCXYCcgR++q" +
                "lVRHqmvUDfJ6qb1UU5ogfhYdUlDcK1QTB4uUcEncJI2SaklOubKMyN/khYpLYeU7iu1/r/ZnxltXnaoW1SxaLu2SNlo/qk82AKAg" +
                "EDfnTwNoIPQHqg8vht/C/ZFuSDpSB+mBdER6IVeQ8UgHZATCIOuQpcgnREUk5DPSEu2LjkafoFnoNzSJzsC6YmnmfeuMPkS+wvNg" +
                "N9QQjAFNgMn6UK2KtlLNVENqltJRdsp75KbyCSlNrmjOn25SpvxKqivlMSuxptxVLqN8l+fLo5V6Kql8UKaq29RValWN0m5rmXpx" +
                "o5lB6WsMBCgBbAUKgnXAkWZ+90LPoOdQAG4N94VXwyw8G54GT4Xvw3vhr/BRuC3cCf4Mq/B3uBpSD5mALEaeIbuRB8hV5DWiITnI" +
                "PWQcAiCH4FbwM6gCtBXcA8SM1obPWKJLWmdto/pOeaB8Maf0WKWsMl/Obcb2WN4rz5Cny//7f89aOUfeIqcqrZRUpYzCKpPUkyqk" +
                "NdXGa3b9mn5Rj+tLjG+GE1gA/AMcBADwNhiA8kONIAgKQO2gBDQPbgc3g4vDFeEolB9Og6vA083cL4RfQunwQdiNNEVOI9URDa6A" +
                "tEaKmzTcH2mCFEMIZAnsh29BQ6F74GxQAQ4aT/RleiN9hzZDC2kb1EqqrGxQqinjlE1KV4U3rXyeXEbuLkfkP/Jz+aX8Xt4n282K" +
                "f6LoyiD1iLpHba8h+gL9hO43qhp+Y5ERMiuiMLAR+AyMBv8Df4HLwRdgHegQVBo+DI+Aa8Ob4FXwDPgazJl5vmpetkEuwEHkILLX" +
                "rJAKaA30MpKJlkD/Rj4is9HK6BqkFDIGLgqfga+b/ZYGHjF66zazlztrd1RUzavkkmEzl18kQCbkT9Ie6a70Wson75DvmzG7ZFZK" +
                "lY/LdZT5yhLljJJQFin/KH+ri7Wu+nF9hU5rXv203tTsthIAY9QCguA+sAB0B0Lgi/AIZB9yBnmM1EQXo+/RvNghk+w+oqUwP9YC" +
                "+wdD8ZF4YXwgfhr7atJeOl7LchyfgPssFssGy1nsC1INfGHIentjtR5V+8lNTGNfyXcRZP4C34K/w5cTsvhKwi/BIfWTGksN5Wti" +
                "Q2mgMsnM9QGllLxI+m4S7znJJw+Qt8qdzWqpoRRUv6hPtLtaba2wfkR/Y5BAG1AEL0Fd4HSkGloeK4afwRtaDlpyEW2J00RHoipR" +
                "gKhB3CE04rG5ySz2f20j7KPsu+yUvY6jtrOMc5VzkP0nxhhdtGbaVO2ZclN6wrah5lG76HnU+eRXOptJssf4D8JIvptQUc6nDJYX" +
                "y0/lKdJqaas8Tq4mDZGuiA+EdGGSMID/yE8R/xbviQfFVpIqNpOzlG1qllbV7LT2gBfsBbWHDyPpGIJ3x6fgqZarFoZ4ZL1iy7Kx" +
                "1pNWr+2V7bctYd/uUJ1ezwxPcY/hru9d5Bvo6+mr4nvoLWA/DA2U14qVxGZCc65akohdiJyIZ8UnJJpTnxmSqyDVV0soy+Q16lnt" +
                "tMoqX+WqkslvUoK3cLlYJ9uSOcE8ZzmmAVeSO8f3EIZIxeURskMpqbRV62jFjevAcqgKPA7GYB7ugJxAD2AH8Up4Xfw+TuL1ifHE" +
                "Qeto63PbRgfvmuAZ6ZnmEbwL/WWC0dDy1Fjq4dDb0O6AGCTd5+Bh3HGyHhUiJyXWRbHwjewT0VskQvZlekg79FXGPag+9Nx4rq8D" +
                "rxvbtaWSUyzJbWS7M8WSYTKFHJ7MJkP0FKogM5D/KZVRxqmzTSrZpVXUV+obdVIfbcAgC1QBPxodgUxgG9ALnAilwh3hyUgMiSOr" +
                "0BV4FoHYStkRx0NnA09DL+tJ8wre0771ATC4ITDEE/Vccz/2EPYUoxdVKPorNiQxOVmY3BU/GXtO/eb6iuflu+BPdB4Rs84iPsGj" +
                "wA7gemOzspnDmMLJNWTDuC0ejlKxFeQUdpxwTpwmy+pp4yP4Hb4BP4AWgy2BzfpOtbbSXbGot9W26gzVqf3SZP2j0RMoBFQDp0PZ" +
                "8FFkOiqhA/Ca1rP2Uc5zzsHOG87u7u9ep6+3u6krzd3N3cPdxLPY3d6Z1zHfudI5G8X5ZGx5uGV8B9mWO0jXousy7eWFKqbvAo6h" +
                "b/HS1uUEh2Tph+V90lueYm4k9Fid2PD4qPjPxInkS7oLv1eRddkoBzQAT0LvkOdwOyhs1NGzFFaqIlYRlgpWeZLax4xzvl7OeG3M" +
                "AVtAT6A0uC9SAN2NFcI2YsPxbOKLjbKfs9ex/2db5ujhvOs64LI6xzibOR87Rbvf3cfdzbPEpNdC1rHyf/FSOdFIrmhruhtfi+/L" +
                "UoJN2aE2N1LwB4TLdpFogX0wnMpgaRq3gtYSzeMvolB8QexGdC/Zl5sqzdEKgi+gzWAl+B0MwfcgENyj31d88lJhPXeLTxWqSdcU" +
                "Vp9h7DXeARvMSF9DHHQbXAc2gnZAYXMCrITPIz8xnvjPNtQRcGyx/7QXds5z1XZddjocN2ynbHfsL53LTLf76f7jXO6E7CJwnSr1" +
                "5+KfieFKydYCKrHmx2d5rvEZsGK1rIa1NVHP0hSqIg8X3nO/2JvJgwkgejZcILoyViRRnFkqiupT4xQ8Fp4CzYc/wbmQR9BtYIQ2" +
                "TP4qjOa78ylCZyGfvFKtYZzSWxt/DAb48b/HPEE2sD+gAjvAkvAj+AFSGJtp0a3v7Ixjshkz6JzrXOp67JxvP2I9QNy1lrA/dlXy" +
                "dPN2cxdx+125nQuQCgz/O/ev7ZF7yapiaak6V4zZKNUFspGR+H/WRVbQOsjyHn6pPuT0ZLfELbOyUyN/h+tEe8byky6OlZ9ql8GJ" +
                "qA3fj/rQ8fAHMABmGg3VH6JusuV0tiE/k98pLlB669uNTGAPMB8ggeHQU+gcOBNoB2wHvoAA/BHG0WI4S+D2l44tjir2tba59qS9" +
                "g+O8w+ZsaE/aJpk/z+2+6R7lXeMZ5v3qfumagx8TPmWf+z49u1v8EbdaDEmvmDZiLb0EfBKpRxSwXSXa4QPhdtomvjq7g+pK3o8R" +
                "sYEROfIxtjhRhXkmVtVxcCCSgmagoElPGhQF9hhnld3CKbYT+4X5we7j7eIxebgmGIOBgcBIoCd4FTwMngVGGoreyqhtElluOA35" +
                "vw6uOzyKaoufe2d7302hV0PvkQ5SjYIPCSBFqjSBPFCqBOm9d5AiIk3pPSAloPSOGKSDQBCBhJBsm52Znbn3vOv7Y+ePnW/uvXvO" +
                "+bVv99sulpe2Ys5jriae+p6w+7Rw3Eedi1xTXFZPCXdp9zq31VPFZw+kJvgDc/z7fBv8t91zyNDCWy/+fJGS74iM0B6oyyN9wmXi" +
                "bf/9HZq5o+O1PdO6wjSDn1G+iS6XD8imiKewU/6EvBoF2wrrR94qlfRu7Gc4TdcLBzCUDjIPM90l0/lbrVjsS5FdSyv9Yyzm0h7F" +
                "V+tpxio2mF/CDJJIB0FlXo13xPkwjmTQNtJE02PzB7ZZjs4upyfNm+F96U51OhxrnL1cuW70tPfU82zyOH2XfMmB4/6vEz4M2AIO" +
                "32pHdWN/QbN/Fr9RCmvIKconct8iOdhZiekIVS0VHZNtdyxtaLp+XWBqZtyrv69dj7Ys6l1wruDKu+3BHdFNSrrWUG9mzNObsl3w" +
                "XOos3YTGeMXIibeMjxEKfUm5q67Q0jSn2kbrqjdj1XAyfIolWRqrgGehKv1YyjUtNmdYtlg729s457kr+xL9t3333JudFVzD3TM8" +
                "nd09PDudA5w93Jt88329/PsTFgdyfEN8vQPoaM4+D0bz/sg/XdQ41lpeFsx41zeYJX8R74fNTeUsDkvItIREjA/0fcwEBq+rjQsP" +
                "eTf57cmCYKERLBnOjlhjmcppJVX9wgjCV6bNUmsykp80BhhPhEOqqe1XBqmz1drKemW7mhGfpx/ghXy2yEaH+KH//ztrgWQyBUyL" +
                "zDnWX+0vnEc9r30HfV94yrpPOEe5UtyNfN/5znnqu3Y4F7qWu/f5BvujCQcCPROy/Z7AQl8FxwLDGyossBVVCK2Rz4cHvpub/23h" +
                "3+H82D7jITlCs0RdFkBbY6Oxg1vxNSP63dis8DchZ6hS+GI4L3I5miHvjo6Jno711+fyqXAaxrK4cEGl+Du2lm00LHqWtkh4u43a" +
                "5/Ep+nZDM5YbI/UP9QfGPp4KHchMUoPkEr9prGWKLdfRyr3SU8+reJN9xbw5nkveSv4LvmbeLFcrZ8iZ4h7qeeyt473nKfC+8W72" +
                "/u3ZGKjrGU5bKjXDXUNdI5PUfTFP8Oe3+wvvFqaGZyq7jGUsym0wzJC1Ir0RnuBB3t14obWTL0VuRq7JZ2P5ckO5WLRB5KtIm9gr" +
                "keLX6R/odo2rA+ML9ClGH96EVRX1XywS6HFjKP+RD+N/8ZEsS99s5PC7PIov4T70hwtEkc6Yi9kWOJu6l3uSvWFvM+8r7zb/8sCY" +
                "wEFfsmeb+7xnoc8biHk+9pZzX/Q89KwUSpThLe5ZJ53XPNELshJbpvXRakQSCi+96xPqFLonP4qf1zvED+s2bZzIyHP4p+yePkNP" +
                "1mjMHFsVeymXjw1Qj8R6R49H5kXKRMvFNqrV1ObqE/WgWlLdp6Xp+XpJVsSOGmAMZQ95FfwNt8MgfM4l3IApWA3WkGmkDtlM+tAu" +
                "0h3TRut9+1znVFddd9wF7uGi6umBB/5a/mL+T/wX/Q/8n/u7+077HnorCv30exZ4Trr3u7aZxrM9Skutgzo8/ly9I38Yehz8NFwq" +
                "FIkujmnq7FgJLRBrEtsQOxxP1W5rVeJttRrKR8pxpU/sO6WTelzZKP8ol4i2jT6P7oxtjP2gPFM6KSdjI5W26k/aKX22sda4b0xm" +
                "V5jO+8FAco/0ox9QM60inP09aaVJNVHzfVMHM1i6WUfYxtrrOnIdNudC5xhXkfsXzzzvau9Nz3bPTm8DX4rP4Svv2+qZ7l7idrj6" +
                "uSq5Lrt/snmkCjiMjTOyjaVsXHyVckB2yWdDS8PZ0btyonxM3hu5HaoeniejzOVHIvvvVk7FTiqXY5WVZtq+eENtt6oo5dUXynPV" +
                "Hi+tbdLC2t/aHJUId70l3kIfaLQ36hvlWDeehinQliyllaVpItltFnobkqZbHprzzMfNe6TaphfmslZmybTdMlewLLW0s2RZq9iO" +
                "2G/bvrJ1t1ezvbJ+Z79vH+N46OjkaGy7bXFby9rqWIdbj1jGmXNNN6Q/aSPpGTYnTuiOI/h6VpNN0JV/mTG+QlsWT45PUUeqebFT" +
                "il1xqQH1ZcykNFeGKauUp8oB5W/lW7WmVk+LqLe109o7rYU+Ui+pn9NzBTJnGNeNXsK/tuZbeQ9eCjdiPp9JFOgjFWAjqYAcIk5p" +
                "G11Ch9G/TO1gksRgiuCIZqbd0mg6y3SPvjCdo/Utb2hEypea2M5Jr+hy+sRc2Zoo9PtLa1fTFmm7eZSptPmUKVNqYf6DRKVRUhua" +
                "aBqM2ZIH69DheJLU4L2gFe+C7bExnuQ/MJnVY7dYJ5Zv3DV2sv3xASLBJusHjHf6vXgt9km8pbEmjvrP+n1WOp5l2I00Vpwf0EvC" +
                "TaML9+JlZsGleIGPxod4Fj+jafxDSeGDqBNWg4O0hhhOI23EtSE0Il0hF66QvmjQsXyQqYCXp0OkLvi+eRa8MFUEm7kHpJumwzTT" +
                "cBhrOkI3047kR/qUMNoX9kr96ZdCOzugDtXICjxPpkNTsXJTTglj6SSPzcAiTMe2bDxfa2hYwziMV/lN4wmvwqYylf1ptME3Rl9e" +
                "lvfhV40z3M93wTS9PknDq+wpfsbt5B5+x05QCz7FJuQ6byndNS6QfvAIrNhK+J52wq09gp60LS8pmcXnHEazjTitC/vRTctjnFyB" +
                "n0l1eCIl0Xc4wvQTnCcPyHU4K/XErXQAVCV9RC2ugZPWwo70AVzgjJyBTXwRucOvYwVci8NxA/ucmPl2thLKMTvpzTL5Kcxh19hM" +
                "UpZls1Xi3K/YK/Y7LjaO4hVeCaKskgTMTbJ5CVgMGWwSvMQlbBEZyxjpwQ7RangP5uBUkkrH4TnK+R0yz/iGOtlwuoxvJ0CWg46b" +
                "8AmpTytDbdGFHFqBvMPJpLJweDXJcGkNK2uabNwxNebXsLGJ6z+aLhkB0p+0ZP8xXYeS0IM+ZT7SkTekI5EB8mmkKhby3/AWS5FG" +
                "6/PpXe7monpGPtyP/wV9eSPRmXbsDf+S1YCJfAc6+Xky2xgEQeMR/AFXjLFSA2MCnuV7RaUXwGusQfN4EKtDKWSiHw9My7ANDCVb" +
                "YDfeoxpfK5XljyEAF/k39KXxSMrEO6Q8ycGrxESKQyfyQirOZFM5o71UEu1Qi85lBo0yn0g6g2EJKOQhX0sr4l1yEc+wDNN/jHUw" +
                "ESbgQ7jK/8YzcI3lCHrifD42ZFPITtYFBvHVrCJ8wFXDCqfZp+QjvIFnSHXjW6k6W4yd4S7/DhirQDbxXN4a68BSzIZsZsBHWIpL" +
                "1MReSfm8NrSFOPsB61Mnn0h+hI95LhRgPVKOtIRDhPLqpt+NZ8SDTyGX1sODMJKkcZk0gfGiv5MFutpDbz6aODGRluGXYQZOgMF8" +
                "AS3JL5OnfBXk43fQAqfSf1giXYS9YTr4oQHpjFOxBNbiLTAMHVgZ6TCrLNVn7WgJ5iVtDZmG9c9JCjsPr7kVBkNrqEfW8zLwHGvD" +
                "PMyHPOB4CP5g/yWv+WZW2zTSeEyf8pU4k6/Ft/AbPBTuWid7jDF0MJ8Dq0l5yJQCfBptJPbryefQVWwzGc+fwPfMRG6xc/Q3Po88" +
                "x/4knW0Qk9IYCuEmPpAS+UTQDTtdrfcnj8hK3gct0JhNRRvMxwrSQN4FNsEbxtn7pAs0wr1kG08h04w71GC1oTUWSbpRZDqhr6Rp" +
                "xmK6GU2kAXjZYfKYFcKPcAOD5ATJBRttwnvSv1ldWozMJXewNknFLfALS6Eu/IAgZHAf7cG/5R+KZEP5O7KHZAiWrE1P4u/YWzrF" +
                "hpBJrCU5LeaxA1exFfbDmmgSnVqtL5Ki8SypDmNwC7vzZzCXrxLP34YotEUXf0hZvBScwLIQxu5IWEVUWAn+X9IJh5Bdxh2YzH6B" +
                "mfQ+bqdBvpJ2Bg+bKEX1f3l/NpnPV5MbzExmQQm6kiTiFPoWP+VpNIEzKZVcJcdhN5zhV/ABuQQ2kgMLoQm5qa8nAbDT7niW1KaZ" +
                "BuJkwWgdwSlW+R7CbAgcZpXgGBEv/Bg+xEzMNppJKSITNYWacJPNFTPpwo68Lh6kafo02M26C65MwaGkDH7FvocBtC8TvE1GQRbb" +
                "L3bsyXPYx4LZo8KzDaDP8WtUMUDfY3foKBLB+mIqH/Mq/Al5yYP0P4LvduI6LII08px2AYmkkUwxuV6B2cbwGFL5SMGTHixHpsB7" +
                "dALfKRg1mZeDyziGduXL+REyh2eyHJpJLrLi5DBM4Pd4SyhNVmMyu0kO80VGU3KSnOFl8RJuxffQC9thD06FTDITvsHe3I0x/Iq3" +
                "pBXJXDZT9G8dloGZpDoZiulQHd7weVhc8GItoQTt+e84DEuQxtJEeC12CHEbKQlzYAt5DJtQRiKSRmf8CEfTM3CMW/Awn4N7YR9x" +
                "kGp8Dd8KyZDHXwjEFkEtUpl0hl7MKfj/BjaA5rgZe/As/pJ78D53QxBfcSe/wr8kxwRb18VV/GOUyN8wCW7jj7gDirAG34o/Qwda" +
                "h2RiQ5zLX0N9uhISYTh2FwrbF5vxN/wzskmozQJelVcW6Eqm1+EfzOUfw3ukBCzBXBxGGtLb5JDoxUW+GFrT/qQsnMddeAB3CqTX" +
                "wBC2JiGYin4xpxuhDrGTisQs9GERPMQV/CgfARaiwTlczNexOHeKmjWAWfhW3DELJHC+FAPkL4iiQyhAfbwoeHohpGAdHCdqVRr2" +
                "ine34afQTHB3AY6An4CISv2JPfGtYKU4nIBP4Fu8glbIEfeqQnlymUykKnkDPmglVjhCBtBGZL7wYem8K7Ygv9PmdAPc4Al8EU4i" +
                "U2gH0gK24TE2m53mBwHIZchBZD8Y9XgBLKVnSTPwckM/aezCQliLqXw/62e4jIqCgU7hRIxifd6d5fDmordNoD+W4O+zr/gMqAOL" +
                "8D4Og1IwBC/gDNgB80UV55N06Ex60DFSN+lr6T65CzXIFlpSYvR3Sadj6F9kOu1Gy9HJ9BidRo+Rd6QY2QpPRU2IyAX9STmBOQ/2" +
                "gEPwTExUWX6K/cr/YDNZT/Yz68fWClf4rXFKCwrPZegT9Fn6kPgWNV87G++pjVaJXqTv0rqqU7Wh8S3xysY+ncS/1IYYvdnPxgqj" +
                "DL/BU3EOmUQXily53rzX3Macb65qrWb/yvGPvb31hKWf7azjN2cVh9PRytHPmeVc6Ux0bnLWct12jHP0sp20nbO7XIpjj+222W75" +
                "1Rq3zrM8lNbSz3lHI894pdeQU2INYsdDwcIBIW9wQsGkYNfQF4Xl34VDM4tMRUdDv0Tcwc7BvPDM8KHg4/Cm6MJITqSH3DpWRa6k" +
                "1NF2aXW1yfEl+ls9z8jin8FQwa15pnmWXdZb9ilOzTlSZIux3iYiH50N6IHtgVuB+QnFk24mXy02sdiqpOWJC5Kyk9sUCyQ/Syjr" +
                "/9Xz3AWu3abWOMc4ycrHr6lTY89C7cJnIs/k7ODIyKrwzcjUyIBYfqRi+Jo8KPaR3Cfik7+PEuVPpbiih2aFOkceyOeijkhKKCN4" +
                "ObwvWj72ZbS2LHKI9kJbEk/TPzOy2Ud4TWBzIe4jJaUsOl6abJpitlhL2OvbP7N9YE9yrnJec33inuJWXIp7gu+g3x5YkZCamJKw" +
                "J7Aj4U5ietIvSQKISZmBve5tlovQBfLJcnyt/ho5W/Sk8LuQLfpZZG/wTahJuHfkdHSS8rX6q6Zq+P8cdF5bpq/QFVWXtWh1kZjq" +
                "RPMiz8Mvw93DC8N6uEq0VeyEMkPdrtTShsTbs6XCnw3A9dgVVpBeYur+/X5rvWmmqZHZZ95h3mGpZAvZvrYnO4hjlP2Ww+ye7V3j" +
                "8/hL+//0TfS/n3g5qVlyVtKxhA6BfYEuSW+TKvhbW4qzDGMss+tJajXZUeh926KwXShS2DSUHhsnn4wwub+6Ur1l3MDbRnl1iWrV" +
                "1xnbjW767+pB+W40Ks+P9YqOCv8ULghuDWZEbsXeRHdGsmLb1GfaCP0MH81u8iNYl46Eo2Q/vUrHk2zYCWPhIvHS3nQ0dCTjaTvz" +
                "VHMpS7q1in2So53rhfuhZ4dP8ZdO8AQaJ1RNzE0KJFdPtiSuC7zxt01ITKidcNKzU5qlTlNGx4fpOeqH0ecF3d6UKvwzIsm/RZ/E" +
                "zsdKxe4qnrjPyGUZONZYqVJ5ubLPuMBS9enKLnl/+Fl0j7pX2y8Ho13lSPSeXEy7Gc/UfopfiPfTh+thvg7GkME4QrDjevweq0Jt" +
                "oUp9mYOtZtONmkzi23h1HsdR5H36QBpvqWYf6HjoXOXKc831/OGPivPF/Q8CrsTaCQsDXRJowtbApIDm2+B566rkeeQoRlPVXkpY" +
                "/TqepZSMji7MLugWWh67GJsRjcUStQfaDW29XkM/o09nC9kGbWJsjBrUmxrvtCR1sWyLLpRnqE8Uh5wuO5UZSl5so6KImXgSv2yk" +
                "sm56onFLMGRdbrAs/h7PYvXEScPGb8Y9ls7T2ESmcQPnQyL9xjTd7LSutVtdX7i8Aoep/nqB8wnZCXMSNiW0ThyeuCZxQ8LAhCoJ" +
                "3F/Lv8xH/bt9N+w9WLXoR3JfdY1WSnGFy+e3fnO78K08UdmvHNWGKH/FmmgdGOX/xQSsawxTfpVnqgP19fpedXhsSHhsOC7/D12G" +
                "cw8AmwAA"
    }
}
