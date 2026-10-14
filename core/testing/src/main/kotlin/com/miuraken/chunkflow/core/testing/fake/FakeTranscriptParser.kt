package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.transcript.TranscriptError
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import com.miuraken.chunkflow.core.transcript.TranscriptParser

/**
 * テスト用の [TranscriptParser]。結果は [result] で決める（既定は「形式を判定できない」）。
 */
class FakeTranscriptParser(
    var result: (bytes: ByteArray, fileName: String) -> TranscriptParseResult = { _, _ ->
        TranscriptParseResult.Error(TranscriptError.UNKNOWN_FORMAT, "FakeTranscriptParser の既定の結果")
    },
) : TranscriptParser {
    /** [parse] に渡されたファイル名（呼ばれた順）。 */
    val parsedFileNames: MutableList<String> = mutableListOf()

    override fun parse(bytes: ByteArray, fileName: String): TranscriptParseResult {
        parsedFileNames += fileName
        return result(bytes, fileName)
    }
}
