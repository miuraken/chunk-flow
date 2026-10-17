package com.miuraken.chunkflow.spike.textai

/**
 * 動作確認用の、作った例文の文字起こし（実際の教材ではない。AGENTS.md §10）。
 *
 * 校正（C・D）の確認のため、韓国語の文に誤りをわざと 2 か所入れてある（「반갑슴니다」「얼마에요」）。
 * 本物の教材での評価は、本体で書き出したファイルを「文字起こしファイルを開く」で読む。
 */
internal object SampleTranscript {
    /** 形式の判定に使う名前（画面には出さない）。 */
    const val FILE_NAME: String = "sample-ja-ko.chunkflow.json"

    private val JSON: String =
        """
        {
          "format": "chunkflow-transcript",
          "version": 1,
          "audio": {
            "fileName": "sample.mp3",
            "sha256": "0000000000000000000000000000000000000000000000000000000000c0ffee",
            "durationMs": 30000
          },
          "languages": { "target": "ko", "base": "ja" },
          "generator": {
            "tool": "spike-textai",
            "engine": "sample",
            "model": "sample",
            "createdAt": "2026-10-17T00:00:00Z"
          },
          "segments": [
            { "startMs": 1200, "endMs": 3400, "lang": "ja", "langProb": 0.99, "text": "では、聞いてみましょう。" },
            { "startMs": 4100, "endMs": 6900, "lang": "ko", "langProb": 0.98, "text": "안녕하세요. 만나서 반갑슴니다." },
            { "startMs": 7800, "endMs": 9600, "lang": "ja", "langProb": 0.96, "text": "「これはいくらですか」と聞いてみましょう。" },
            { "startMs": 10300, "endMs": 13100, "lang": "ko", "langProb": 0.97, "text": "이건 얼마에요?" },
            { "startMs": 14000, "endMs": 16500, "lang": "ko", "langProb": 0.95, "text": "저는 학생이에요." }
          ]
        }
        """.trimIndent()

    /** UTF-8 のバイト列（本物のファイルと同じ経路で `core:transcript` に解析させる）。 */
    fun bytes(): ByteArray = JSON.encodeToByteArray()
}
