package com.miuraken.chunkflow.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 文字起こしのエンジンの ID から画面に出す名前を決める処理（[transcriptEngineLabelRes]）のテスト。 */
class TranscriptEngineLabelTest {

    @Test
    fun `クラウドのエンジンはモデルが変わっても前方一致で同じ名前にする`() {
        assertEquals(
            R.string.player_detailed_transcribe_engine_openai_api,
            transcriptEngineLabelRes("cloud-openai-gpt-4o-mini-transcribe"),
        )
        assertEquals(
            R.string.player_detailed_transcribe_engine_groq_api,
            transcriptEngineLabelRes("cloud-groq-whisper-large-v3-turbo"),
        )
        // いまの ID と、このあと切り替える ID のどちらでも「Gemini API」
        for (id in listOf("cloud-gemini-2.5-flash", "cloud-gemini-3.8-flash", "cloud-gemini-flash-latest")) {
            assertEquals(R.string.player_detailed_transcribe_engine_gemini_api, transcriptEngineLabelRes(id), id)
        }
    }

    @Test
    fun `端末内のエンジンはIDごとの名前にし知らないIDはnull`() {
        assertEquals(R.string.player_detailed_transcribe_engine_mlkit, transcriptEngineLabelRes("mlkit-genai-speech"))
        assertEquals(R.string.player_detailed_transcribe_engine_os, transcriptEngineLabelRes("android-speech-recognizer"))
        assertEquals(
            R.string.player_detailed_transcribe_engine_sherpa_sense_voice,
            transcriptEngineLabelRes("sherpa-onnx-sense-voice-small"),
        )
        assertNull(transcriptEngineLabelRes("cloud-error"))
        assertNull(transcriptEngineLabelRes("unknown-engine"))
    }
}
