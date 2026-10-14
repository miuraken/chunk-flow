package com.miuraken.chunkflow.spike.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.inspector.MediaExtractorCompat

/**
 * D6: MP3 のヘッダを隠す経路（ADR 0005 / 0006 の案）。ExoPlayer と MediaExtractorCompat に、同じ抽出器の設定
 * （`FLAG_ENABLE_INDEX_SEEKING`）と同じ DataSource（[IndexSeekDataSource]）を渡す。
 *
 * Media3 1.11.1 の `MediaExtractorCompat(ExtractorsFactory, DataSource.Factory)` を使う（DataSource を渡せる公開の API）。
 */
@OptIn(UnstableApi::class)
object HiddenHeaderMedia {
    private fun extractors() = DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)

    private fun dataSource(context: Context) = IndexSeekDataSource.Factory(DefaultDataSource.Factory(context))

    /** ヘッダを隠して読む MediaExtractorCompat。 */
    fun extractorCompat(context: Context): MediaExtractorCompat = MediaExtractorCompat(extractors(), dataSource(context))

    /** ヘッダを隠して再生する ExoPlayer の MediaSource.Factory。 */
    fun mediaSourceFactory(context: Context): MediaSource.Factory = DefaultMediaSourceFactory(dataSource(context), extractors())
}
