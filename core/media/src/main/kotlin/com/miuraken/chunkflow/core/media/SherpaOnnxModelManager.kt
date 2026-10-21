package com.miuraken.chunkflow.core.media

import android.content.Context
import android.util.Log
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.DownloadableSttModelState
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
 * オンデバイス ONNX 音声認識モデル（`sherpa-onnx` Whisper Tiny / Base / Small、SenseVoice Small、
 * Moonshine ONNX Tiny / Base）のローカルチェックアウト（ダウンロード・検証・削除）を管理するクラス（Stage 2、F6.2）。
 *
 * - 各モデルは `noBackupFilesDir/stt_models/<modelId>/` 配下に保持する。
 * - 全体設定の「文字起こし」タブのモデル管理セクションから一括ダウンロードした際は `global.ready` マーカーを作成し、
 *   そのモデルが対応するすべての言語で利用可能とする。
 * - 言語ごとの個別チェックアウト（`lang_<code>.ready`）にも対応し、パージ（削除）時はディレクトリ内の全ファイルとマーカーを削除する。
 */
@Singleton
class SherpaOnnxModelManager internal constructor(
    private val modelsRootDir: File,
    private val downloader: ModelArtifactDownloader,
    private val artifacts: List<ArtifactEntry> = DEFAULT_WHISPER_TINY_ARTIFACTS,
    private val customArtifactsByModel: Map<DownloadableSttModel, List<ArtifactEntry>> = emptyMap(),
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

    internal data class SenseVoiceModelPaths(
        val modelFile: File,
        val tokensFile: File,
    )

    internal data class MoonshineModelPaths(
        val preprocessFile: File,
        val encodeFile: File,
        val uncachedDecodeFile: File,
        val cachedDecodeFile: File,
        val tokensFile: File,
    )

    private val downloadMutex = Mutex()
    private val statusByLanguageKey = ConcurrentHashMap<String, OnDeviceLanguageModelStatus>()
    private val statusByModel = ConcurrentHashMap<DownloadableSttModel, OnDeviceLanguageModelStatus>()

    private fun artifactsFor(model: DownloadableSttModel): List<ArtifactEntry> {
        customArtifactsByModel[model]?.let { return it }
        return when (model) {
            DownloadableSttModel.SHERPA_WHISPER_TINY -> artifacts
            DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL -> DEFAULT_SENSE_VOICE_SMALL_ARTIFACTS
            DownloadableSttModel.SHERPA_WHISPER_BASE -> DEFAULT_WHISPER_BASE_ARTIFACTS
            DownloadableSttModel.SHERPA_WHISPER_SMALL -> DEFAULT_WHISPER_SMALL_ARTIFACTS
            DownloadableSttModel.MOONSHINE_TINY -> DEFAULT_MOONSHINE_TINY_ARTIFACTS
            DownloadableSttModel.MOONSHINE_BASE -> DEFAULT_MOONSHINE_BASE_ARTIFACTS
        }
    }

    /** Whisper Tiny モデル一式が配置されるディレクトリ。 */
    internal val modelDir: File
        get() = modelDir(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /** 指定した [model] のファイル一式が配置されるディレクトリ。 */
    internal fun modelDir(model: DownloadableSttModel): File = File(modelsRootDir, model.modelId)

    /** Whisper Tiny モデル全体の合計サイズ（バイト）。 */
    val totalModelSizeBytes: Long
        get() = totalModelSizeBytes(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /** 指定した [model] の合計サイズ（バイト）。 */
    fun totalModelSizeBytes(model: DownloadableSttModel): Long = artifactsFor(model).sumOf { it.expectedSizeBytes }

    /**
     * Whisper Tiny の共通モデルファイル一式（encoder / decoder / tokens）がディスク上に揃っており、サイズも一致しているかどうか。
     */
    fun areCoreModelFilesPresent(): Boolean = areCoreModelFilesPresent(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /**
     * 指定した [model] の共通モデルファイル一式がディスク上に揃っており、サイズも一致しているかどうか。
     */
    fun areCoreModelFilesPresent(model: DownloadableSttModel): Boolean {
        val dir = modelDir(model)
        if (!dir.isDirectory) return false
        return artifactsFor(model).all { artifact ->
            val file = File(dir, artifact.fileName)
            file.isFile && (artifact.expectedSizeBytes <= 0L || file.length() == artifact.expectedSizeBytes)
        }
    }

    /**
     * 指定した [model] がダウンロード済み（グローバルマーカーまたはいずれかの言語マーカーが存在し、コアファイルが揃っている）かどうか。
     */
    fun isModelDownloaded(model: DownloadableSttModel): Boolean {
        if (!areCoreModelFilesPresent(model)) return false
        if (globalMarkerFile(model).isFile) return true
        val dir = modelDir(model)
        val hasAnyLangMarker = dir.listFiles { file ->
            file.isFile && file.name.startsWith(MARKER_PREFIX) && file.name.endsWith(MARKER_SUFFIX)
        }?.isNotEmpty() == true
        return hasAnyLangMarker
    }

    /**
     * 指定した言語 [language] で `sherpa-onnx`（Whisper Tiny）ローカルモデルがチェックアウト済み（利用可能）かどうかを返す。
     */
    fun isInstalledForLanguage(language: LanguageTag): Boolean = isInstalledForLanguage(DownloadableSttModel.SHERPA_WHISPER_TINY, language)

    /**
     * 指定した [model] および言語 [language] でモデルが利用可能かどうかを返す。
     */
    fun isInstalledForLanguage(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): Boolean {
        if (!model.isLanguageSupported(language)) return false
        if (!areCoreModelFilesPresent(model)) return false
        return globalMarkerFile(model).isFile || languageMarkerFile(model, language).isFile
    }

    /**
     * 指定した主要言語サブタグ [primarySubtag] および [model] でモデルが利用可能かどうかを返す。
     */
    fun isModelAvailableForLanguage(
        primarySubtag: String,
        model: DownloadableSttModel,
    ): Boolean = isInstalledForLanguage(model, LanguageTag(primarySubtag))

    /**
     * Whisper Tiny の共通モデルファイルが既にディスク上にある場合は、そのモデルファイル群のパスを返す。
     */
    internal fun resolveInstalledModelPaths(): ModelPaths? = resolveWhisperModelPaths(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /**
     * Whisper 系モデル（[DownloadableSttModel.SHERPA_WHISPER_TINY] / [DownloadableSttModel.SHERPA_WHISPER_BASE] /
     * [DownloadableSttModel.SHERPA_WHISPER_SMALL]）のパスを解決する。
     */
    internal fun resolveWhisperModelPaths(model: DownloadableSttModel): ModelPaths? {
        if (!areCoreModelFilesPresent(model)) return null
        val dir = modelDir(model)
        return when (model) {
            DownloadableSttModel.SHERPA_WHISPER_TINY -> ModelPaths(
                encoderFile = File(dir, ENCODER_FILE_NAME),
                decoderFile = File(dir, DECODER_FILE_NAME),
                tokensFile = File(dir, TOKENS_FILE_NAME),
            )

            DownloadableSttModel.SHERPA_WHISPER_BASE -> ModelPaths(
                encoderFile = File(dir, BASE_ENCODER_FILE_NAME),
                decoderFile = File(dir, BASE_DECODER_FILE_NAME),
                tokensFile = File(dir, BASE_TOKENS_FILE_NAME),
            )

            DownloadableSttModel.SHERPA_WHISPER_SMALL -> ModelPaths(
                encoderFile = File(dir, SMALL_ENCODER_FILE_NAME),
                decoderFile = File(dir, SMALL_DECODER_FILE_NAME),
                tokensFile = File(dir, SMALL_TOKENS_FILE_NAME),
            )

            else -> null
        }
    }

    /**
     * SenseVoice Small モデルのファイルパスを解決する。
     */
    internal fun resolveSenseVoiceModelPaths(): SenseVoiceModelPaths? {
        val model = DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL
        if (!areCoreModelFilesPresent(model)) return null
        val dir = modelDir(model)
        return SenseVoiceModelPaths(
            modelFile = File(dir, SENSE_VOICE_MODEL_FILE_NAME),
            tokensFile = File(dir, SENSE_VOICE_TOKENS_FILE_NAME),
        )
    }

    /**
     * Moonshine ONNX モデル（[DownloadableSttModel.MOONSHINE_TINY] / [DownloadableSttModel.MOONSHINE_BASE]）のパスを解決する。
     */
    internal fun resolveMoonshineModelPaths(model: DownloadableSttModel): MoonshineModelPaths? {
        if (model != DownloadableSttModel.MOONSHINE_TINY && model != DownloadableSttModel.MOONSHINE_BASE) {
            return null
        }
        if (!areCoreModelFilesPresent(model)) return null
        val dir = modelDir(model)
        return MoonshineModelPaths(
            preprocessFile = File(dir, MOONSHINE_PREPROCESS_FILE_NAME),
            encodeFile = File(dir, MOONSHINE_ENCODE_FILE_NAME),
            uncachedDecodeFile = File(dir, MOONSHINE_UNCACHED_DECODE_FILE_NAME),
            cachedDecodeFile = File(dir, MOONSHINE_CACHED_DECODE_FILE_NAME),
            tokensFile = File(dir, MOONSHINE_TOKENS_FILE_NAME),
        )
    }

    /**
     * 指定した [model] のグローバルなダウンロード状態を返す（設定画面のモデル管理セクション用）。
     */
    fun getModelStatus(model: DownloadableSttModel): OnDeviceLanguageModelStatus {
        val transient = statusByModel[model]
        if (transient == OnDeviceLanguageModelStatus.DOWNLOADING) {
            return OnDeviceLanguageModelStatus.DOWNLOADING
        }
        if (isModelDownloaded(model)) {
            statusByModel.remove(model)
            return OnDeviceLanguageModelStatus.INSTALLED
        }
        if (transient == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED) {
            return OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
        }
        return OnDeviceLanguageModelStatus.NOT_INSTALLED
    }

    /**
     * ダウンロード可能なすべての ONNX モデルの状態一覧を返す。
     */
    fun queryDownloadableModelStates(): List<DownloadableSttModelState> = DownloadableSttModel.entries.map { model ->
        val status = getModelStatus(model)
        DownloadableSttModelState(
            model = model,
            status = status,
            sizeBytes = totalModelSizeBytes(model),
            canPurge = status == OnDeviceLanguageModelStatus.INSTALLED,
        )
    }

    /**
     * ダウンロード可能なすべての ONNX モデルの状態一覧を返す。
     */
    fun queryAllDownloadableModelStates(): List<DownloadableSttModelState> = queryDownloadableModelStates()

    /**
     * 指定した言語 [language] に対する `sherpa-onnx`（Whisper Tiny）ローカルモデルの状態を返す。
     */
    fun getLanguageModelStatus(language: LanguageTag): OnDeviceLanguageModelStatus = getLanguageModelStatus(DownloadableSttModel.SHERPA_WHISPER_TINY, language)

    /**
     * 指定した言語 [language] および [model] に対するローカルモデルの状態を返す。
     */
    fun getLanguageModelStatus(
        language: LanguageTag,
        model: DownloadableSttModel,
    ): OnDeviceLanguageModelStatus = getLanguageModelStatus(model, language)

    /**
     * 指定した [model] および言語 [language] に対するローカルモデルの状態を返す。
     */
    fun getLanguageModelStatus(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): OnDeviceLanguageModelStatus {
        if (!model.isLanguageSupported(language)) {
            return OnDeviceLanguageModelStatus.UNSUPPORTED
        }
        val langKey = statusMapKey(model, language)
        val transientLang = statusByLanguageKey[langKey]
        val transientModel = statusByModel[model]
        if (transientLang == OnDeviceLanguageModelStatus.DOWNLOADING ||
            transientModel == OnDeviceLanguageModelStatus.DOWNLOADING
        ) {
            return OnDeviceLanguageModelStatus.DOWNLOADING
        }
        if (isInstalledForLanguage(model, language) || isModelDownloaded(model)) {
            statusByLanguageKey.remove(langKey)
            return OnDeviceLanguageModelStatus.INSTALLED
        }
        if (transientLang == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED ||
            transientModel == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
        ) {
            return OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
        }
        return OnDeviceLanguageModelStatus.NOT_INSTALLED
    }

    /**
     * 指定した [model] を全体設定からダウンロード・検証して全対応言語向けに有効化する。
     */
    suspend fun downloadModel(model: DownloadableSttModel): Boolean {
        statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOADING
        return try {
            downloadMutex.withLock {
                statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOADING
                val ok = downloadArtifactsLocked(model)
                if (!ok) {
                    statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    return@withLock false
                }
                val marker = globalMarkerFile(model)
                marker.parentFile?.mkdirs()
                runCatching { marker.writeText(model.modelId) }.onFailure {
                    statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    return@withLock false
                }
                statusByModel.remove(model)
                logInfo("onnx model ready globally model=${model.modelId}")
                true
            }
        } catch (ce: CancellationException) {
            statusByModel.remove(model)
            throw ce
        } catch (_: Exception) {
            statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            false
        }
    }

    /**
     * 指定した [model] のファイルおよびマーカーをすべて削除（パージ）してディスク容量を解放する。
     */
    suspend fun purgeModel(model: DownloadableSttModel): Boolean = downloadMutex.withLock {
        statusByModel.remove(model)
        val prefix = "${model.modelId}:"
        statusByLanguageKey.keys.removeIf { it.startsWith(prefix) }
        deleteAllCoreFilesInternal(model)
    }

    /**
     * 指定した言語 [language] 向けに `sherpa-onnx`（Whisper Tiny）ローカルモデルをダウンロード・検証して有効化する。
     */
    suspend fun downloadModelForLanguage(language: LanguageTag): Boolean = downloadModelForLanguage(DownloadableSttModel.SHERPA_WHISPER_TINY, language)

    /**
     * 指定した言語 [language] および [model] 向けにダウンロード・検証して有効化する。
     */
    suspend fun downloadModelForLanguage(
        language: LanguageTag,
        model: DownloadableSttModel,
    ): Boolean = downloadModelForLanguage(model, language)

    /**
     * 指定した [model] を言語 [language] 向けにダウンロード・検証して有効化する。
     */
    suspend fun downloadModelForLanguage(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): Boolean {
        if (!model.isLanguageSupported(language)) return false
        val mapKey = statusMapKey(model, language)
        statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOADING
        statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOADING

        return try {
            downloadMutex.withLock {
                statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOADING
                statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOADING
                val ok = downloadArtifactsLocked(model)
                if (!ok) {
                    statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    return@withLock false
                }

                val marker = languageMarkerFile(model, language)
                marker.parentFile?.mkdirs()
                runCatching { marker.writeText(model.modelId) }.onFailure {
                    statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    return@withLock false
                }
                statusByLanguageKey.remove(mapKey)
                statusByModel.remove(model)
                logInfo("onnx model ready model=${model.modelId} lang=${normalizeLanguageKey(language)}")
                true
            }
        } catch (ce: CancellationException) {
            statusByLanguageKey.remove(mapKey)
            statusByModel.remove(model)
            throw ce
        } catch (_: Exception) {
            statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            false
        }
    }

    private suspend fun downloadArtifactsLocked(model: DownloadableSttModel): Boolean {
        val dir = modelDir(model)
        if (!dir.exists() && !dir.mkdirs()) {
            return false
        }
        for (artifact in artifactsFor(model)) {
            val targetFile = File(dir, artifact.fileName)
            val alreadyValid = targetFile.isFile &&
                (artifact.expectedSizeBytes <= 0L || targetFile.length() == artifact.expectedSizeBytes)
            if (alreadyValid) continue

            logInfo("downloading onnx artifact=${artifact.fileName} model=${model.modelId}")
            val downloaded = downloader.downloadToFile(
                spec = ModelArtifactDownloadSpec(
                    url = artifact.url,
                    expectedSizeBytes = artifact.expectedSizeBytes,
                    expectedSha256Hex = artifact.expectedSha256Hex,
                ),
                destinationFile = targetFile,
            )
            if (!downloaded) {
                logInfo("failed to download onnx artifact=${artifact.fileName} model=${model.modelId}")
                return false
            }
        }
        return true
    }

    /**
     * 指定した言語 [language] の `sherpa-onnx`（Whisper Tiny）ローカルモデル登録を解除し、
     * 他のどの言語でも使われていなければ共通モデルファイル一式もディスクから削除する。
     */
    suspend fun deleteModelForLanguage(language: LanguageTag): Boolean = deleteModelForLanguage(DownloadableSttModel.SHERPA_WHISPER_TINY, language)

    /**
     * 指定した [model] の言語 [language] 向けマーカーを解除し、残りのマーカーがなければモデルファイルも削除する。
     */
    suspend fun deleteModelForLanguage(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): Boolean = downloadMutex.withLock {
        val mapKey = statusMapKey(model, language)
        statusByLanguageKey.remove(mapKey)
        val marker = languageMarkerFile(model, language)
        if (marker.exists() && !marker.delete()) {
            return@withLock false
        }
        val globalMarker = globalMarkerFile(model)
        if (globalMarker.exists()) {
            globalMarker.delete()
        }
        val remainingMarkers = modelDir(model).listFiles { file ->
            file.isFile && file.name.startsWith(MARKER_PREFIX) && file.name.endsWith(MARKER_SUFFIX)
        }.orEmpty()
        if (remainingMarkers.isEmpty()) {
            deleteAllCoreFilesInternal(model)
        }
        true
    }

    /**
     * すべてのモデルの言語マーカーおよび共通モデルファイル一式を完全に削除する。
     */
    suspend fun deleteAllModels(): Boolean = downloadMutex.withLock {
        statusByLanguageKey.clear()
        statusByModel.clear()
        var allOk = true
        for (model in DownloadableSttModel.entries) {
            if (!deleteAllCoreFilesInternal(model)) {
                allOk = false
            }
        }
        allOk
    }

    private fun deleteAllCoreFilesInternal(model: DownloadableSttModel): Boolean {
        val dir = modelDir(model)
        if (!dir.exists()) return true
        var allDeleted = true
        dir.listFiles()?.forEach { child ->
            if (!child.deleteRecursively()) {
                allDeleted = false
            }
        }
        return allDeleted
    }

    private fun globalMarkerFile(model: DownloadableSttModel): File = File(modelDir(model), GLOBAL_MARKER_FILE_NAME)

    private fun languageMarkerFile(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): File {
        val key = normalizeLanguageKey(language)
        return File(modelDir(model), "$MARKER_PREFIX$key$MARKER_SUFFIX")
    }

    private fun statusMapKey(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): String = "${model.modelId}:${normalizeLanguageKey(language)}"

    companion object {
        const val MODEL_ID: String = "sherpa-onnx-whisper-tiny"
        const val ENCODER_FILE_NAME: String = "tiny-encoder.int8.onnx"
        const val DECODER_FILE_NAME: String = "tiny-decoder.int8.onnx"
        const val TOKENS_FILE_NAME: String = "tiny-tokens.txt"

        const val BASE_ENCODER_FILE_NAME: String = "base-encoder.int8.onnx"
        const val BASE_DECODER_FILE_NAME: String = "base-decoder.int8.onnx"
        const val BASE_TOKENS_FILE_NAME: String = "base-tokens.txt"

        const val SMALL_ENCODER_FILE_NAME: String = "small-encoder.int8.onnx"
        const val SMALL_DECODER_FILE_NAME: String = "small-decoder.int8.onnx"
        const val SMALL_TOKENS_FILE_NAME: String = "small-tokens.txt"

        const val SENSE_VOICE_MODEL_FILE_NAME: String = "model.int8.onnx"
        const val SENSE_VOICE_TOKENS_FILE_NAME: String = "tokens.txt"

        const val MOONSHINE_PREPROCESS_FILE_NAME: String = "preprocess.onnx"
        const val MOONSHINE_ENCODE_FILE_NAME: String = "encode.int8.onnx"
        const val MOONSHINE_UNCACHED_DECODE_FILE_NAME: String = "uncached_decode.int8.onnx"
        const val MOONSHINE_CACHED_DECODE_FILE_NAME: String = "cached_decode.int8.onnx"
        const val MOONSHINE_TOKENS_FILE_NAME: String = "tokens.txt"

        private const val MODELS_ROOT_REL_PATH: String = "stt_models"
        private const val MARKER_PREFIX: String = "lang_"
        private const val MARKER_SUFFIX: String = ".ready"
        private const val GLOBAL_MARKER_FILE_NAME: String = "global.ready"
        private const val LOG_TAG: String = "ChunkFlowSherpa"

        private const val HF_WHISPER_TINY_BASE_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"
        private const val HF_WHISPER_BASE_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main"
        private const val HF_WHISPER_SMALL_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main"
        private const val HF_SENSE_VOICE_SMALL_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
        private const val HF_MOONSHINE_TINY_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-tiny-en-int8/resolve/main"
        private const val HF_MOONSHINE_BASE_URL: String =
            "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-base-en-int8/resolve/main"

        internal val DEFAULT_WHISPER_TINY_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = ENCODER_FILE_NAME,
                url = "$HF_WHISPER_TINY_BASE_URL/$ENCODER_FILE_NAME",
                expectedSizeBytes = 12_937_772L,
                expectedSha256Hex = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            ),
            ArtifactEntry(
                fileName = DECODER_FILE_NAME,
                url = "$HF_WHISPER_TINY_BASE_URL/$DECODER_FILE_NAME",
                expectedSizeBytes = 89_855_401L,
                expectedSha256Hex = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            ),
            ArtifactEntry(
                fileName = TOKENS_FILE_NAME,
                url = "$HF_WHISPER_TINY_BASE_URL/$TOKENS_FILE_NAME",
                expectedSizeBytes = 816_730L,
                expectedSha256Hex = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        )

        internal val DEFAULT_WHISPER_BASE_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = BASE_ENCODER_FILE_NAME,
                url = "$HF_WHISPER_BASE_URL/$BASE_ENCODER_FILE_NAME",
                expectedSizeBytes = 29_120_534L,
                expectedSha256Hex = "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
            ),
            ArtifactEntry(
                fileName = BASE_DECODER_FILE_NAME,
                url = "$HF_WHISPER_BASE_URL/$BASE_DECODER_FILE_NAME",
                expectedSizeBytes = 130_672_026L,
                expectedSha256Hex = "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
            ),
            ArtifactEntry(
                fileName = BASE_TOKENS_FILE_NAME,
                url = "$HF_WHISPER_BASE_URL/$BASE_TOKENS_FILE_NAME",
                expectedSizeBytes = 816_730L,
                expectedSha256Hex = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        )

        internal val DEFAULT_WHISPER_SMALL_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = SMALL_ENCODER_FILE_NAME,
                url = "$HF_WHISPER_SMALL_URL/$SMALL_ENCODER_FILE_NAME",
                expectedSizeBytes = 112_442_483L,
                expectedSha256Hex = "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            ),
            ArtifactEntry(
                fileName = SMALL_DECODER_FILE_NAME,
                url = "$HF_WHISPER_SMALL_URL/$SMALL_DECODER_FILE_NAME",
                expectedSizeBytes = 262_226_114L,
                expectedSha256Hex = "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            ),
            ArtifactEntry(
                fileName = SMALL_TOKENS_FILE_NAME,
                url = "$HF_WHISPER_SMALL_URL/$SMALL_TOKENS_FILE_NAME",
                expectedSizeBytes = 816_730L,
                expectedSha256Hex = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
            ),
        )

        internal val DEFAULT_SENSE_VOICE_SMALL_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = SENSE_VOICE_MODEL_FILE_NAME,
                url = "$HF_SENSE_VOICE_SMALL_URL/$SENSE_VOICE_MODEL_FILE_NAME",
                expectedSizeBytes = 239_233_841L,
                expectedSha256Hex = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            ),
            ArtifactEntry(
                fileName = SENSE_VOICE_TOKENS_FILE_NAME,
                url = "$HF_SENSE_VOICE_SMALL_URL/$SENSE_VOICE_TOKENS_FILE_NAME",
                expectedSizeBytes = 315_894L,
                expectedSha256Hex = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            ),
        )

        internal val DEFAULT_MOONSHINE_TINY_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = MOONSHINE_PREPROCESS_FILE_NAME,
                url = "$HF_MOONSHINE_TINY_URL/$MOONSHINE_PREPROCESS_FILE_NAME",
                expectedSizeBytes = 6_800_738L,
                expectedSha256Hex = "f33addce61a143460fe753b5ee5b7db255e5140b5b779c065b94f6c83ff0bf4e",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_ENCODE_FILE_NAME,
                url = "$HF_MOONSHINE_TINY_URL/$MOONSHINE_ENCODE_FILE_NAME",
                expectedSizeBytes = 18_249_187L,
                expectedSha256Hex = "8774dfba578de027ec6595c2c654a0836434489bc963a0db124a7f181f571acb",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_UNCACHED_DECODE_FILE_NAME,
                url = "$HF_MOONSHINE_TINY_URL/$MOONSHINE_UNCACHED_DECODE_FILE_NAME",
                expectedSizeBytes = 53_216_096L,
                expectedSha256Hex = "216737000dd5881a17aa043f6bbd286add33e4c3b0ae257153e2ec15438bdc41",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_CACHED_DECODE_FILE_NAME,
                url = "$HF_MOONSHINE_TINY_URL/$MOONSHINE_CACHED_DECODE_FILE_NAME",
                expectedSizeBytes = 45_264_830L,
                expectedSha256Hex = "2aff28bba6a03d8dcf5c9feac45462629bae37317442299f28115ad09da773f6",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_TOKENS_FILE_NAME,
                url = "$HF_MOONSHINE_TINY_URL/$MOONSHINE_TOKENS_FILE_NAME",
                expectedSizeBytes = 436_688L,
                expectedSha256Hex = "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea",
            ),
        )

        internal val DEFAULT_MOONSHINE_BASE_ARTIFACTS: List<ArtifactEntry> = listOf(
            ArtifactEntry(
                fileName = MOONSHINE_PREPROCESS_FILE_NAME,
                url = "$HF_MOONSHINE_BASE_URL/$MOONSHINE_PREPROCESS_FILE_NAME",
                expectedSizeBytes = 14_077_290L,
                expectedSha256Hex = "ffa630d395c5ccf76f5d4954be5b882df76aaf6491519ec01fd82ea7a3819fb2",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_ENCODE_FILE_NAME,
                url = "$HF_MOONSHINE_BASE_URL/$MOONSHINE_ENCODE_FILE_NAME",
                expectedSizeBytes = 50_311_494L,
                expectedSha256Hex = "7e38770f776f2e5583a53b052936005df2ba5c833d7e09c2a5fd796b94bf73e2",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_UNCACHED_DECODE_FILE_NAME,
                url = "$HF_MOONSHINE_BASE_URL/$MOONSHINE_UNCACHED_DECODE_FILE_NAME",
                expectedSizeBytes = 122_120_451L,
                expectedSha256Hex = "c01f4b35093bcac20d352d23a75a539e772964579f9d024a90e5e6f09cae9987",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_CACHED_DECODE_FILE_NAME,
                url = "$HF_MOONSHINE_BASE_URL/$MOONSHINE_CACHED_DECODE_FILE_NAME",
                expectedSizeBytes = 99_983_837L,
                expectedSha256Hex = "2db74e51cedf64a8b1be3c8192e0bb5e4923af0e90bd9e87f8e8771873f8ea03",
            ),
            ArtifactEntry(
                fileName = MOONSHINE_TOKENS_FILE_NAME,
                url = "$HF_MOONSHINE_BASE_URL/$MOONSHINE_TOKENS_FILE_NAME",
                expectedSizeBytes = 436_688L,
                expectedSha256Hex = "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea",
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
