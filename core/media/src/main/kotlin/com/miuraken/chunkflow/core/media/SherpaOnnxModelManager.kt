package com.miuraken.chunkflow.core.media

import android.content.Context
import android.util.Log
import com.miuraken.chunkflow.core.drive.ModelArtifactDownloadSpec
import com.miuraken.chunkflow.core.drive.ModelArtifactDownloader
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `sherpa-onnx`（Whisper Tiny int8 多言語モデル）のローカルチェックアウト（ダウンロード・検証・削除）を管理するクラス（Stage 2、F6.2）。
 *
 * - モデル実体（`tiny-encoder.int8.onnx` / `tiny-decoder.int8.onnx` / `tiny-tokens.txt`）は多言語共通（99 言語対応）であるため
 *   `noBackupFilesDir/stt_models/sherpa-onnx-whisper-tiny/` 配下に 1 セット保持し、言語ごとの利用有効化状態を
 *   マーカーファイル（`lang_<code>.ready`）で管理する。
 * - いずれかの言語でローカルモデルをダウンロードすると共通モデル一式を取得・検証し、対象言語のマーカーを作成する。
 * - すべての言語のマーカーが削除された時点で、共通モデルファイル一式もディスクから削除して容量を解放する。
 */
@Singleton
class SherpaOnnxModelManager internal constructor(
    private val modelsRootDir: File,
    private val downloader: ModelArtifactDownloader,
    private val artifacts: List<ArtifactEntry> = DEFAULT_WHISPER_TINY_ARTIFACTS,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        downloader: ModelArtifactDownloader,
    ) : this(
        modelsRootDir = File(context.noBackupFilesDir, MODELS_ROOT_REL_PATH),
        downloader = downloader,
        artifacts = DEFAULT_WHISPER_TINY_ARTIFACTS,
    )

    internal data class ArtifactEntry(
        val fileName: String,
        val url: String,
        val expectedSizeBytes: Long,
        val expectedSha256Hex: String?,
    )

    internal data class ModelPaths(
        val encoderFile: File,
        val decoderFile: File,
        val tokensFile: File,
    )

    private val downloadMutex = Mutex()
    private val statusByLanguageKey = ConcurrentHashMap<String, OnDeviceLanguageModelStatus>()

    /** モデル一式が配置されるディレクトリ。 */
    internal val modelDir: File
        get() = File(modelsRootDir, MODEL_ID)

    /** モデル全体の合計サイズ（バイト）。 */
    val totalModelSizeBytes: Long
        get() = artifacts.sumOf { it.expectedSizeBytes }

    /**
     * 共通モデルファイル一式（encoder / decoder / tokens）がディスク上に揃っており、サイズも一致しているかどうか。
     */
    fun areCoreModelFilesPresent(): Boolean {
        val dir = modelDir
        if (!dir.isDirectory) return false
        return artifacts.all { artifact ->
            val file = File(dir, artifact.fileName)
            file.isFile && (artifact.expectedSizeBytes <= 0L || file.length() == artifact.expectedSizeBytes)
        }
    }

    /**
     * 指定した言語 [language] で `sherpa-onnx` ローカルモデルがチェックアウト済み（利用可能）かどうかを返す。
     */
    fun isInstalledForLanguage(language: LanguageTag): Boolean {
        if (!isLanguageSupportedByWhisper(language)) return false
        if (!areCoreModelFilesPresent()) return false
        return languageMarkerFile(language).isFile
    }

    /**
     * 共通モデルファイルが既にディスク上にある場合は、そのモデルファイル群のパスを返す。
     * いずれかのファイルが欠けている場合は `null` を返す。
     */
    internal fun resolveInstalledModelPaths(): ModelPaths? {
        if (!areCoreModelFilesPresent()) return null
        val dir = modelDir
        return ModelPaths(
            encoderFile = File(dir, ENCODER_FILE_NAME),
            decoderFile = File(dir, DECODER_FILE_NAME),
            tokensFile = File(dir, TOKENS_FILE_NAME),
        )
    }

    /**
     * 指定した言語 [language] に対する `sherpa-onnx` ローカルモデルの状態を返す。
     */
    fun getLanguageModelStatus(language: LanguageTag): OnDeviceLanguageModelStatus {
        if (!isLanguageSupportedByWhisper(language)) {
            return OnDeviceLanguageModelStatus.UNSUPPORTED
        }
        val key = normalizeLanguageKey(language)
        val transient = statusByLanguageKey[key]
        if (transient == OnDeviceLanguageModelStatus.DOWNLOADING) {
            return OnDeviceLanguageModelStatus.DOWNLOADING
        }
        if (isInstalledForLanguage(language)) {
            statusByLanguageKey.remove(key)
            return OnDeviceLanguageModelStatus.INSTALLED
        }
        if (transient == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED) {
            return OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
        }
        return OnDeviceLanguageModelStatus.NOT_INSTALLED
    }

    /**
     * 指定した言語 [language] 向けに `sherpa-onnx` ローカルモデルをダウンロード・検証して有効化する。
     *
     * 既に別の言語で共通モデルファイル一式がダウンロード済みの場合は、再ダウンロードせず即座に有効化する。
     *
     * @return ダウンロードおよび検証が成功して利用可能になった場合は `true`。
     */
    suspend fun downloadModelForLanguage(language: LanguageTag): Boolean {
        if (!isLanguageSupportedByWhisper(language)) return false
        val key = normalizeLanguageKey(language)
        statusByLanguageKey[key] = OnDeviceLanguageModelStatus.DOWNLOADING

        return try {
            downloadMutex.withLock {
                statusByLanguageKey[key] = OnDeviceLanguageModelStatus.DOWNLOADING
                val dir = modelDir
                if (!dir.exists() && !dir.mkdirs()) {
                    statusByLanguageKey[key] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    return@withLock false
                }

                for (artifact in artifacts) {
                    val targetFile = File(dir, artifact.fileName)
                    val alreadyValid = targetFile.isFile &&
                        (artifact.expectedSizeBytes <= 0L || targetFile.length() == artifact.expectedSizeBytes)
                    if (alreadyValid) continue

                    logInfo("downloading sherpa-onnx artifact=${artifact.fileName} lang=$key")
                    val downloaded = downloader.downloadToFile(
                        spec = ModelArtifactDownloadSpec(
                            url = artifact.url,
                            expectedSizeBytes = artifact.expectedSizeBytes,
                            expectedSha256Hex = artifact.expectedSha256Hex,
                        ),
                        destinationFile = targetFile,
                    )
                    if (!downloaded) {
                        logInfo("failed to download sherpa-onnx artifact=${artifact.fileName} lang=$key")
                        statusByLanguageKey[key] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                        return@withLock false
                    }
                }

                val marker = languageMarkerFile(language)
                marker.parentFile?.mkdirs()
                runCatching { marker.writeText(MODEL_ID) }.onFailure {
                    statusByLanguageKey[key] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    return@withLock false
                }
                statusByLanguageKey.remove(key)
                logInfo("sherpa-onnx model ready for lang=$key")
                true
            }
        } catch (ce: CancellationException) {
            statusByLanguageKey.remove(key)
            throw ce
        } catch (_: Exception) {
            statusByLanguageKey[key] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            false
        }
    }

    /**
     * 指定した言語 [language] の `sherpa-onnx` ローカルモデル登録を解除し、
     * 他のどの言語でも使われていなければ共通モデルファイル一式もディスクから削除する。
     */
    suspend fun deleteModelForLanguage(language: LanguageTag): Boolean = downloadMutex.withLock {
        val key = normalizeLanguageKey(language)
        statusByLanguageKey.remove(key)
        val marker = languageMarkerFile(language)
        if (marker.exists() && !marker.delete()) {
            return@withLock false
        }
        val remainingMarkers = modelDir.listFiles { file ->
            file.isFile && file.name.startsWith(MARKER_PREFIX) && file.name.endsWith(MARKER_SUFFIX)
        }.orEmpty()
        if (remainingMarkers.isEmpty()) {
            deleteAllCoreFilesInternal()
        }
        true
    }

    /**
     * すべての言語マーカーおよび共通モデルファイル一式を完全に削除する。
     */
    suspend fun deleteAllModels(): Boolean = downloadMutex.withLock {
        statusByLanguageKey.clear()
        deleteAllCoreFilesInternal()
    }

    private fun deleteAllCoreFilesInternal(): Boolean {
        val dir = modelDir
        if (!dir.exists()) return true
        var allDeleted = true
        dir.listFiles()?.forEach { child ->
            if (!child.deleteRecursively()) {
                allDeleted = false
            }
        }
        return allDeleted
    }

    private fun languageMarkerFile(language: LanguageTag): File {
        val key = normalizeLanguageKey(language)
        return File(modelDir, "$MARKER_PREFIX$key$MARKER_SUFFIX")
    }

    companion object {
        const val MODEL_ID: String = "sherpa-onnx-whisper-tiny"
        const val ENCODER_FILE_NAME: String = "tiny-encoder.int8.onnx"
        const val DECODER_FILE_NAME: String = "tiny-decoder.int8.onnx"
        const val TOKENS_FILE_NAME: String = "tiny-tokens.txt"

        private const val MODELS_ROOT_REL_PATH: String = "stt_models"
        private const val MARKER_PREFIX: String = "lang_"
        private const val MARKER_SUFFIX: String = ".ready"
        private const val LOG_TAG: String = "ChunkFlowSherpa"

        private const val HF_BASE_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"

        internal val DEFAULT_WHISPER_TINY_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = ENCODER_FILE_NAME,
                url = "$HF_BASE_URL/$ENCODER_FILE_NAME",
                expectedSizeBytes = 12_937_772L,
                expectedSha256Hex = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            ),
            ArtifactEntry(
                fileName = DECODER_FILE_NAME,
                url = "$HF_BASE_URL/$DECODER_FILE_NAME",
                expectedSizeBytes = 89_855_401L,
                expectedSha256Hex = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            ),
            ArtifactEntry(
                fileName = TOKENS_FILE_NAME,
                url = "$HF_BASE_URL/$TOKENS_FILE_NAME",
                expectedSizeBytes = 816_730L,
                expectedSha256Hex = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        )

        /**
         * 言語タグ [language] を Whisper の正規化言語キー（`"zh"`, `"yue"`, `"ja"` 等）に変換する。
         */
        internal fun normalizeLanguageKey(language: LanguageTag): String {
            val raw = language.value.trim().replace('_', '-').lowercase(Locale.ROOT)
            if (raw.isEmpty()) return "en"
            return when {
                raw == "cmn" || raw.startsWith("cmn-") || raw.startsWith("zh-") -> "zh"
                raw.startsWith("yue-") -> "yue"
                else -> raw.substringBefore('-')
            }
        }

        /**
         * 指定された言語 [language] が `sherpa-onnx` Whisper 多言語モデルでサポートされているかどうかを返す。
         */
        fun isLanguageSupportedByWhisper(language: LanguageTag): Boolean {
            val key = normalizeLanguageKey(language)
            return WhisperTinyLanguageDetector.languageIndexForTag(key) != null
        }

        private fun logInfo(message: String) {
            runCatching {
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, message)
                }
            }
        }
    }
}
