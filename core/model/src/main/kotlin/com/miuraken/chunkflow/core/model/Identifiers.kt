package com.miuraken.chunkflow.core.model

/**
 * 教材（音声ファイル）の識別子。値は UUID 文字列。
 *
 * ファイル名（`filesDir/media/<MediaId>.<ext>`）にも使うため、利用者が入力した文字列を入れてはならない（§6.4、§8.2）。
 */
@JvmInline
value class MediaId(val value: String)

/** チャンクの識別子（Room の主キー）。 */
@JvmInline
value class ChunkId(val value: Long)

/**
 * 文字起こしの出どころ。
 *
 * 例: `"whisper-large-v3-turbo"`（Chunk Flow JSON の `generator.model`）、`"srt"`、`"manual"`。
 */
@JvmInline
value class EngineId(val value: String)
