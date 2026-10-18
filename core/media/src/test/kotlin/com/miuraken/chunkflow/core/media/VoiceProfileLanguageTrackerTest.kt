package com.miuraken.chunkflow.core.media

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceProfileLanguageTrackerTest {

    private val dimension = 64
    private val random = Random(20261018L)

    /** すべてのベクトルに共通する成分（実際のエンコーダ出力にも大きな共通成分がある）。 */
    private val common = randomUnitVector(scale = 3.0f)

    private fun randomUnitVector(scale: Float = 1.0f): FloatArray {
        val v = FloatArray(dimension) { random.nextGaussian().toFloat() }
        var norm = 0f
        for (x in v) norm += x * x
        val inv = scale / sqrt(norm)
        for (i in v.indices) v[i] *= inv
        return v
    }

    /** 話者（声）の中心 [center] の周りの 1 チャンク分のベクトル（雑音はベクトル全体で長さ約 [noise]）。 */
    private fun sample(center: FloatArray, noise: Float = 0.5f): FloatArray = FloatArray(dimension) { i ->
        common[i] + center[i] + noise * random.nextGaussian().toFloat() / sqrt(dimension.toFloat())
    }

    private fun whisper(targetProbability: Float, pairMass: Float): LanguagePairPrediction {
        val base = 1f - targetProbability
        return LanguagePairPrediction(
            selectedTag = if (targetProbability >= base) "hi" else "en",
            fallbackTag = if (targetProbability >= base) "en" else "hi",
            selectedProbability = maxOf(targetProbability, base),
            targetProbability = targetProbability,
            baseProbability = base,
            inferenceTimeMs = 0L,
            pairMass = pairMass,
        )
    }

    @Test
    fun `predictLanguageFromVoiceProfile_各言語のアンカーが3件未満のうちはnullを返す`() {
        val tracker = VoiceProfileLanguageTracker()
        val narrator = randomUnitVector()
        val native = randomUnitVector()
        tracker.recordSpeakerObservation("/a.mp3", 1L, "en", sample(narrator))
        tracker.recordSpeakerObservation("/a.mp3", 2L, "en", sample(narrator))
        tracker.recordSpeakerObservation("/a.mp3", 3L, "en", sample(narrator))
        tracker.recordSpeakerObservation("/a.mp3", 4L, "hi", sample(native))
        tracker.recordSpeakerObservation("/a.mp3", 5L, "hi", sample(native))

        assertNull(tracker.predictLanguageFromVoiceProfile("/a.mp3", sample(native), "hi", "en"))

        tracker.recordSpeakerObservation("/a.mp3", 6L, "hi", sample(native))
        assertNotNull(tracker.predictLanguageFromVoiceProfile("/a.mp3", sample(native), "hi", "en"))
    }

    @Test
    fun `predictLanguageFromVoiceProfile_対象言語の話者が複数でも各話者に近いチャンクを対象言語と判定する`() {
        val tracker = VoiceProfileLanguageTracker()
        val narrator = randomUnitVector()
        val natives = List(3) { randomUnitVector() }
        var key = 0L
        repeat(10) { tracker.recordSpeakerObservation("/lesson.mp3", key++, "en-US", sample(narrator)) }
        for (native in natives) {
            repeat(4) { tracker.recordSpeakerObservation("/lesson.mp3", key++, "hi-IN", sample(native)) }
        }

        for (native in natives) {
            val prediction = assertNotNull(
                tracker.predictLanguageFromVoiceProfile("/lesson.mp3", sample(native), "hi-IN", "en-US"),
            )
            assertTrue(prediction.targetProbability > 0.8f, "target=${prediction.targetProbability}")
            assertEquals(12, prediction.targetAnchorCount)
            assertEquals(10, prediction.baseAnchorCount)
        }
        val narratorPrediction = assertNotNull(
            tracker.predictLanguageFromVoiceProfile("/lesson.mp3", sample(narrator), "hi-IN", "en-US"),
        )
        assertTrue(narratorPrediction.baseProbability > 0.8f, "base=${narratorPrediction.baseProbability}")
    }

    @Test
    fun `predictLanguageFromVoiceProfile_声が言語で分かれていない教材では校正で影響がほぼ消える`() {
        val tracker = VoiceProfileLanguageTracker()
        // 1 人の話者が両方の言語を話す教材: どちらの言語のアンカーも同じ声の分布から来る
        val teacher = randomUnitVector()
        var key = 0L
        repeat(20) {
            tracker.recordSpeakerObservation("/self.mp3", key++, "hi", sample(teacher))
            tracker.recordSpeakerObservation("/self.mp3", key++, "en", sample(teacher))
        }

        repeat(5) {
            val prediction = assertNotNull(
                tracker.predictLanguageFromVoiceProfile("/self.mp3", sample(teacher), "hi", "en"),
            )
            assertTrue(abs(prediction.logOdds) < 1.0f, "logOdds=${prediction.logOdds}")
        }
    }

    @Test
    fun `recordSpeakerObservation_同じチャンクを別の言語で登録し直すと前の登録を置き換える`() {
        val tracker = VoiceProfileLanguageTracker()
        val narrator = randomUnitVector()
        val native = randomUnitVector()
        for (key in 1L..4L) tracker.recordSpeakerObservation("/a.mp3", key, "hi", sample(native))
        for (key in 5L..7L) tracker.recordSpeakerObservation("/a.mp3", key, "en", sample(narrator))

        // チャンク 1 の言語が後から en に確定した
        tracker.recordSpeakerObservation("/a.mp3", 1L, "en", sample(narrator))

        val prediction = assertNotNull(tracker.predictLanguageFromVoiceProfile("/a.mp3", sample(native), "hi", "en"))
        assertEquals(3, prediction.targetAnchorCount)
        assertEquals(4, prediction.baseAnchorCount)
    }

    @Test
    fun `clearScope_ファイルごとに学習内容を分けて消せる`() {
        val tracker = VoiceProfileLanguageTracker()
        val narrator = randomUnitVector()
        val native = randomUnitVector()
        var key = 0L
        repeat(4) {
            tracker.recordSpeakerObservation("/a.mp3", key++, "hi", sample(native))
            tracker.recordSpeakerObservation("/a.mp3", key++, "en", sample(narrator))
        }
        // 別のファイルには影響しない
        assertNull(tracker.predictLanguageFromVoiceProfile("/b.mp3", sample(native), "hi", "en"))
        assertNotNull(tracker.predictLanguageFromVoiceProfile("/a.mp3", sample(native), "hi", "en"))

        tracker.clearScope("/a.mp3")
        assertNull(tracker.predictLanguageFromVoiceProfile("/a.mp3", sample(native), "hi", "en"))
    }

    @Test
    fun `combineWithWhisperPrediction_ペア外に確率が流れた短いチャンクではWhisperを割り引いてファイル内の判定で補正する`() {
        val tracker = VoiceProfileLanguageTracker()
        // Whisper は 0.9 で en と言うが、99 言語の確率の 8% しか hi / en に集まっていない短いチャンク
        val combined = tracker.combineWithWhisperPrediction(
            whisperPrediction = whisper(targetProbability = 0.1f, pairMass = 0.08f),
            speakerPrediction = VoiceProfileLanguageTracker.SpeakerLanguagePrediction(
                targetProbability = 0.82f,
                baseProbability = 0.18f,
                logOdds = 1.5f,
                targetAnchorCount = 12,
                baseAnchorCount = 10,
            ),
            targetTag = "hi",
            baseTag = "en",
            analyzedDurationMs = 900L,
        )
        assertEquals("hi", combined.selectedTag)
        assertEquals(0.08f, combined.pairMass)
    }

    @Test
    fun `combineWithWhisperPrediction_十分な長さで2言語に確率が集まったWhisperの高確信はファイル内の弱い判定では覆らない`() {
        val tracker = VoiceProfileLanguageTracker()
        val combined = tracker.combineWithWhisperPrediction(
            whisperPrediction = whisper(targetProbability = 0.01f, pairMass = 0.95f),
            speakerPrediction = VoiceProfileLanguageTracker.SpeakerLanguagePrediction(
                targetProbability = 0.88f,
                baseProbability = 0.12f,
                logOdds = 2.0f,
                targetAnchorCount = 12,
                baseAnchorCount = 10,
            ),
            targetTag = "hi",
            baseTag = "en",
            analyzedDurationMs = 2_500L,
        )
        assertEquals("en", combined.selectedTag)
    }

    @Test
    fun `combineWithWhisperPrediction_ファイル内の判定がないときは選ぶ言語を変えずに過信だけを割り引く`() {
        val tracker = VoiceProfileLanguageTracker()
        val raw = whisper(targetProbability = 0.9f, pairMass = 0.2f)
        val combined = tracker.combineWithWhisperPrediction(
            whisperPrediction = raw,
            speakerPrediction = null,
            targetTag = "hi",
            baseTag = "en",
            analyzedDurationMs = 1_000L,
        )
        assertEquals("hi", combined.selectedTag)
        assertTrue(combined.selectedProbability in 0.5f..0.9f, "prob=${combined.selectedProbability}")
        assertTrue(combined.selectedProbability < raw.selectedProbability)

        // 2 言語に確率が集まった十分な長さの判定は割り引かない
        val confident = whisper(targetProbability = 0.9f, pairMass = 0.95f)
        val unchanged = tracker.combineWithWhisperPrediction(confident, null, "hi", "en", analyzedDurationMs = 2_000L)
        assertEquals(0.9f, unchanged.selectedProbability, 1e-4f)
    }

    @Test
    fun `isAnchorEligible_Whisper単体の確信度と2言語マスの両方を満たすときだけtrue`() {
        assertTrue(VoiceProfileLanguageTracker.isAnchorEligible(whisper(targetProbability = 0.97f, pairMass = 0.9f)))
        assertTrue(VoiceProfileLanguageTracker.isAnchorEligible(whisper(targetProbability = 0.02f, pairMass = 0.75f)))
        assertFalse(VoiceProfileLanguageTracker.isAnchorEligible(whisper(targetProbability = 0.9f, pairMass = 0.9f)))
        assertFalse(VoiceProfileLanguageTracker.isAnchorEligible(whisper(targetProbability = 0.99f, pairMass = 0.3f)))
    }
}
