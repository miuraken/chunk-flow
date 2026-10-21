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

    internal data class MoonshineMergedModelPaths(
        val encoderFile: File,
        val decoderMergedFile: File,
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

    private fun artifactsForLanguage(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): List<ArtifactEntry> {
        customArtifactsByModel[model]?.let { return it }
        if (!model.isPerLanguageModel) return artifactsFor(model)
        val langKey = normalizeLanguageKey(language)
        return when (model) {
            DownloadableSttModel.MOONSHINE_TINY ->
                MOONSHINE_TINY_ARTIFACTS_BY_LANG[langKey] ?: DEFAULT_MOONSHINE_TINY_ARTIFACTS

            DownloadableSttModel.MOONSHINE_BASE ->
                MOONSHINE_BASE_ARTIFACTS_BY_LANG[langKey] ?: DEFAULT_MOONSHINE_BASE_ARTIFACTS

            else -> artifactsFor(model)
        }
    }

    /** Whisper Tiny モデル一式が配置されるディレクトリ。 */
    internal val modelDir: File
        get() = modelDir(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /** 指定した [model] のファイル一式が配置されるディレクトリ。 */
    internal fun modelDir(model: DownloadableSttModel): File = File(modelsRootDir, model.modelId)

    /** 指定した言語別モデル [model] の言語 [language] 用サブディレクトリ。 */
    internal fun languageModelDir(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): File {
        if (!model.isPerLanguageModel || customArtifactsByModel.containsKey(model)) {
            return modelDir(model)
        }
        return File(modelDir(model), normalizeLanguageKey(language))
    }

    /** Whisper Tiny モデル全体の合計サイズ（バイト）。 */
    val totalModelSizeBytes: Long
        get() = totalModelSizeBytes(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /** 指定した [model] の合計サイズ（バイト）。 */
    fun totalModelSizeBytes(model: DownloadableSttModel): Long = artifactsFor(model).sumOf { it.expectedSizeBytes }

    /** 指定した [model] および言語 [language] の合計サイズ（バイト）。 */
    fun totalModelSizeBytesForLanguage(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): Long = artifactsForLanguage(model, language).sumOf { it.expectedSizeBytes }

    /**
     * Whisper Tiny の共通モデルファイル一式（encoder / decoder / tokens）がディスク上に揃っており、サイズも一致しているかどうか。
     */
    fun areCoreModelFilesPresent(): Boolean = areCoreModelFilesPresent(DownloadableSttModel.SHERPA_WHISPER_TINY)

    /**
     * 指定した [model] の共通モデルファイル一式がディスク上に揃っており、サイズも一致しているかどうか。
     */
    fun areCoreModelFilesPresent(model: DownloadableSttModel): Boolean {
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            return model.supportedLanguages.any { lang -> areLanguageModelFilesPresent(model, lang) }
        }
        val dir = modelDir(model)
        if (!dir.isDirectory) return false
        return artifactsFor(model).all { artifact ->
            val file = File(dir, artifact.fileName)
            file.isFile && (artifact.expectedSizeBytes <= 0L || file.length() == artifact.expectedSizeBytes)
        }
    }

    private fun areLanguageModelFilesPresent(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): Boolean {
        if (!model.isPerLanguageModel || customArtifactsByModel.containsKey(model)) {
            return areCoreModelFilesPresent(model)
        }
        val expectedArtifacts = artifactsForLanguage(model, language)
        val langDir = languageModelDir(model, language)
        val presentInLangDir = langDir.isDirectory && expectedArtifacts.all { artifact ->
            val file = File(langDir, artifact.fileName)
            file.isFile && (artifact.expectedSizeBytes <= 0L || file.length() == artifact.expectedSizeBytes)
        }
        if (presentInLangDir) return true
        // 後方互換: 既存の英語モデルが modelDir 直下に置かれている場合も認める
        if (normalizeLanguageKey(language) == "en") {
            val baseDir = modelDir(model)
            return baseDir.isDirectory && expectedArtifacts.all { artifact ->
                val file = File(baseDir, artifact.fileName)
                file.isFile && (artifact.expectedSizeBytes <= 0L || file.length() == artifact.expectedSizeBytes)
            }
        }
        return false
    }

    /**
     * 指定した [model] でダウンロード済みの言語一覧を返す（言語別モデル用）。
     */
    fun downloadedLanguagesFor(model: DownloadableSttModel): List<LanguageTag> {
        if (!model.isPerLanguageModel) return emptyList()
        return model.supportedLanguages.filter { lang -> isInstalledForLanguage(model, lang) }
    }

    /**
     * 指定した [model] がダウンロード済み（グローバルマーカーまたはいずれかの言語マーカーが存在し、コアファイルが揃っている）かどうか。
     */
    fun isModelDownloaded(model: DownloadableSttModel): Boolean {
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            return downloadedLanguagesFor(model).isNotEmpty()
        }
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
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            if (!areLanguageModelFilesPresent(model, language)) return false
            val hasLangMarker = languageMarkerFile(model, language).isFile
            val hasLegacyEnGlobal = normalizeLanguageKey(language) == "en" && globalMarkerFile(model).isFile
            return hasLangMarker || hasLegacyEnGlobal
        }
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
     * Moonshine ONNX 5 ファイル形式（英語 `en`）モデルのパスを解決する。
     */
    internal fun resolveMoonshineModelPaths(
        model: DownloadableSttModel,
        language: LanguageTag = LanguageTag("en"),
    ): MoonshineModelPaths? {
        if (model != DownloadableSttModel.MOONSHINE_TINY && model != DownloadableSttModel.MOONSHINE_BASE) {
            return null
        }
        if (normalizeLanguageKey(language) != "en") return null
        if (!areLanguageModelFilesPresent(model, language)) return null
        val langDir = languageModelDir(model, language)
        val candidateDir = if (File(langDir, MOONSHINE_PREPROCESS_FILE_NAME).isFile) {
            langDir
        } else {
            modelDir(model)
        }
        return MoonshineModelPaths(
            preprocessFile = File(candidateDir, MOONSHINE_PREPROCESS_FILE_NAME),
            encodeFile = File(candidateDir, MOONSHINE_ENCODE_FILE_NAME),
            uncachedDecodeFile = File(candidateDir, MOONSHINE_UNCACHED_DECODE_FILE_NAME),
            cachedDecodeFile = File(candidateDir, MOONSHINE_CACHED_DECODE_FILE_NAME),
            tokensFile = File(candidateDir, MOONSHINE_TOKENS_FILE_NAME),
        )
    }

    /**
     * Moonshine ONNX v2 統合デコーダ形式（`ja`, `zh`, `ko`, `es`, `ar`, `uk`, `vi`）モデルのパスを解決する。
     */
    internal fun resolveMoonshineMergedModelPaths(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): MoonshineMergedModelPaths? {
        if (model != DownloadableSttModel.MOONSHINE_TINY && model != DownloadableSttModel.MOONSHINE_BASE) {
            return null
        }
        val langKey = normalizeLanguageKey(language)
        if (langKey == "en") return null
        if (!areLanguageModelFilesPresent(model, language)) return null
        val dir = languageModelDir(model, language)
        val expected = artifactsForLanguage(model, language)
        val encoderName = expected.firstOrNull { it.fileName.startsWith("encoder_model.") }?.fileName ?: return null
        val decoderName = expected.firstOrNull { it.fileName.startsWith("decoder_model_merged.") }?.fileName ?: return null
        val tokensName = expected.firstOrNull { it.fileName == MOONSHINE_TOKENS_FILE_NAME }?.fileName ?: MOONSHINE_TOKENS_FILE_NAME
        return MoonshineMergedModelPaths(
            encoderFile = File(dir, encoderName),
            decoderMergedFile = File(dir, decoderName),
            tokensFile = File(dir, tokensName),
        )
    }

    /**
     * 指定した [model] のグローバルなダウンロード状態を返す（設定画面のモデル管理セクション用）。
     */
    fun getModelStatus(model: DownloadableSttModel): OnDeviceLanguageModelStatus {
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            val prefix = "${model.modelId}:"
            val anyDownloading = statusByLanguageKey.entries.any {
                it.key.startsWith(prefix) && it.value == OnDeviceLanguageModelStatus.DOWNLOADING
            }
            if (anyDownloading || statusByModel[model] == OnDeviceLanguageModelStatus.DOWNLOADING) {
                return OnDeviceLanguageModelStatus.DOWNLOADING
            }
            if (downloadedLanguagesFor(model).isNotEmpty()) {
                statusByModel.remove(model)
                return OnDeviceLanguageModelStatus.INSTALLED
            }
            val anyFailed = statusByLanguageKey.entries.any {
                it.key.startsWith(prefix) && it.value == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            }
            if (anyFailed || statusByModel[model] == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED) {
                return OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            }
            return OnDeviceLanguageModelStatus.NOT_INSTALLED
        }
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
        val downloadedLangs = downloadedLanguagesFor(model)
        val sizeBytes = if (model.isPerLanguageModel && downloadedLangs.isNotEmpty()) {
            downloadedLangs.sumOf { lang -> totalModelSizeBytesForLanguage(model, lang) }
        } else {
            totalModelSizeBytes(model)
        }
        val canPurge = if (model.isPerLanguageModel) {
            downloadedLangs.isNotEmpty()
        } else {
            status == OnDeviceLanguageModelStatus.INSTALLED
        }
        DownloadableSttModelState(
            model = model,
            status = status,
            sizeBytes = sizeBytes,
            canPurge = canPurge,
            availableLanguages = model.supportedLanguages,
            downloadedLanguages = downloadedLangs,
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
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            if (transientLang == OnDeviceLanguageModelStatus.DOWNLOADING) {
                return OnDeviceLanguageModelStatus.DOWNLOADING
            }
            if (isInstalledForLanguage(model, language)) {
                statusByLanguageKey.remove(langKey)
                return OnDeviceLanguageModelStatus.INSTALLED
            }
            if (transientLang == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED) {
                return OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            }
            return OnDeviceLanguageModelStatus.NOT_INSTALLED
        }
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
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            return downloadModelForLanguage(model, LanguageTag("en"))
        }
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
        if (!model.isPerLanguageModel) {
            statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOADING
        }

        return try {
            downloadMutex.withLock {
                statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOADING
                if (!model.isPerLanguageModel) {
                    statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOADING
                }
                val ok = if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
                    downloadLanguageArtifactsLocked(model, language)
                } else {
                    downloadArtifactsLocked(model)
                }
                if (!ok) {
                    statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    if (!model.isPerLanguageModel) {
                        statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    }
                    return@withLock false
                }

                val marker = languageMarkerFile(model, language)
                marker.parentFile?.mkdirs()
                runCatching { marker.writeText(model.modelId) }.onFailure {
                    statusByLanguageKey[mapKey] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    if (!model.isPerLanguageModel) {
                        statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    }
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
            if (!model.isPerLanguageModel) {
                statusByModel[model] = OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
            }
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

    private suspend fun downloadLanguageArtifactsLocked(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): Boolean {
        val dir = languageModelDir(model, language)
        if (!dir.exists() && !dir.mkdirs()) {
            return false
        }
        for (artifact in artifactsForLanguage(model, language)) {
            val targetFile = File(dir, artifact.fileName)
            val alreadyValid = targetFile.isFile &&
                (artifact.expectedSizeBytes <= 0L || targetFile.length() == artifact.expectedSizeBytes)
            if (alreadyValid) continue

            logInfo("downloading onnx artifact=${artifact.fileName} model=${model.modelId} lang=${normalizeLanguageKey(language)}")
            val downloaded = downloader.downloadToFile(
                spec = ModelArtifactDownloadSpec(
                    url = artifact.url,
                    expectedSizeBytes = artifact.expectedSizeBytes,
                    expectedSha256Hex = artifact.expectedSha256Hex,
                ),
                destinationFile = targetFile,
            )
            if (!downloaded) {
                logInfo("failed to download onnx artifact=${artifact.fileName} model=${model.modelId} lang=${normalizeLanguageKey(language)}")
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
        if (model.isPerLanguageModel && !customArtifactsByModel.containsKey(model)) {
            val langDir = languageModelDir(model, language)
            if (langDir.exists() && langDir != modelDir(model)) {
                langDir.deleteRecursively()
            }
            if (normalizeLanguageKey(language) == "en") {
                val globalMarker = globalMarkerFile(model)
                if (globalMarker.exists()) {
                    globalMarker.delete()
                }
                val baseDir = modelDir(model)
                DEFAULT_MOONSHINE_TINY_ARTIFACTS.forEach { entry ->
                    File(baseDir, entry.fileName).takeIf { it.isFile }?.delete()
                }
            }
        } else {
            val globalMarker = globalMarkerFile(model)
            if (globalMarker.exists()) {
                globalMarker.delete()
            }
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
        const val MOONSHINE_MERGED_ENCODER_ORT_FILE_NAME: String = "encoder_model.ort"
        const val MOONSHINE_MERGED_DECODER_ORT_FILE_NAME: String = "decoder_model_merged.ort"
        const val MOONSHINE_MERGED_ENCODER_ONNX_FILE_NAME: String = "encoder_model.onnx"
        const val MOONSHINE_MERGED_DECODER_ONNX_FILE_NAME: String = "decoder_model_merged.onnx"

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

        private fun sherpaMoonshineV2OrtArtifacts(
            repoSlug: String,
            encoderBytes: Long,
            encoderSha256: String,
            decoderBytes: Long,
            decoderSha256: String,
            tokensBytes: Long = 549_350L,
        ): List<ArtifactEntry> {
            val baseUrl = "https://huggingface.co/csukuangfj2/$repoSlug/resolve/main"
            return listOf(
                ArtifactEntry(
                    fileName = MOONSHINE_MERGED_ENCODER_ORT_FILE_NAME,
                    url = "$baseUrl/$MOONSHINE_MERGED_ENCODER_ORT_FILE_NAME",
                    expectedSizeBytes = encoderBytes,
                    expectedSha256Hex = encoderSha256,
                ),
                ArtifactEntry(
                    fileName = MOONSHINE_MERGED_DECODER_ORT_FILE_NAME,
                    url = "$baseUrl/$MOONSHINE_MERGED_DECODER_ORT_FILE_NAME",
                    expectedSizeBytes = decoderBytes,
                    expectedSha256Hex = decoderSha256,
                ),
                ArtifactEntry(
                    fileName = MOONSHINE_TOKENS_FILE_NAME,
                    url = "$baseUrl/$MOONSHINE_TOKENS_FILE_NAME",
                    expectedSizeBytes = tokensBytes,
                    expectedSha256Hex = null,
                ),
            )
        }

        private fun onnxCommunityMoonshineArtifacts(
            onnxRepoSlug: String,
            tokensRepoSlug: String,
            encoderBytes: Long,
            encoderSha256: String,
            decoderBytes: Long,
            decoderSha256: String,
            tokensBytes: Long = 549_350L,
        ): List<ArtifactEntry> {
            val onnxBaseUrl = "https://huggingface.co/onnx-community/$onnxRepoSlug/resolve/main/onnx"
            val tokensUrl = "https://huggingface.co/csukuangfj2/$tokensRepoSlug/resolve/main/$MOONSHINE_TOKENS_FILE_NAME"
            return listOf(
                ArtifactEntry(
                    fileName = MOONSHINE_MERGED_ENCODER_ONNX_FILE_NAME,
                    url = "$onnxBaseUrl/encoder_model_quantized.onnx",
                    expectedSizeBytes = encoderBytes,
                    expectedSha256Hex = encoderSha256,
                ),
                ArtifactEntry(
                    fileName = MOONSHINE_MERGED_DECODER_ONNX_FILE_NAME,
                    url = "$onnxBaseUrl/decoder_model_merged_quantized.onnx",
                    expectedSizeBytes = decoderBytes,
                    expectedSha256Hex = decoderSha256,
                ),
                ArtifactEntry(
                    fileName = MOONSHINE_TOKENS_FILE_NAME,
                    url = tokensUrl,
                    expectedSizeBytes = tokensBytes,
                    expectedSha256Hex = null,
                ),
            )
        }

        internal val MOONSHINE_TINY_ARTIFACTS_BY_LANG: Map<String, List<ArtifactEntry>> = mapOf(
            "en" to DEFAULT_MOONSHINE_TINY_ARTIFACTS,
            "ja" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27",
                encoderBytes = 13_238_184L,
                encoderSha256 = "86ece73812604b9b5f1274b4d1e6eec0d783b96088ff46d49e30a53f881cad73",
                decoderBytes = 58_327_272L,
                decoderSha256 = "9fcd9b71323a496b307e20dd305c4e9a1b533c7bedd6e4f660e974967dd60bb6",
            ),
            "ko" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27",
                encoderBytes = 13_238_176L,
                encoderSha256 = "947260d46252f48eada86a34986b3f70c01d68a343959949a77375b94debd055",
                decoderBytes = 58_327_336L,
                decoderSha256 = "95aa9f2e764b80625d2889d6ec9f05c965808e540ac50c16abd10c7ea33fe44b",
            ),
            "zh" to onnxCommunityMoonshineArtifacts(
                onnxRepoSlug = "moonshine-tiny-zh-ONNX",
                tokensRepoSlug = "sherpa-onnx-moonshine-base-zh-quantized-2026-02-27",
                encoderBytes = 8_012_610L,
                encoderSha256 = "23db10b7e6197da936034284002291f90a69ba594022c54bd1f632faa62a02dc",
                decoderBytes = 20_376_947L,
                decoderSha256 = "6bf35050449312174a03a3ba238fb3d4b4a7b5a1416143bb7ce8d7ebcb5ca304",
            ),
            "es" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-es-quantized-2026-02-27",
                encoderBytes = 20_964_320L,
                encoderSha256 = "331aafa2fc7f7e55ba28376eef08eaf919ae105bb719cd64ba6875505dca72b3",
                decoderBytes = 43_612_200L,
                decoderSha256 = "8e6513ad66a3a71ca86824746a09051eeb60468940eaf8201ce758e8919e2b5d",
                tokensBytes = 532_090L,
            ),
            "ar" to onnxCommunityMoonshineArtifacts(
                onnxRepoSlug = "moonshine-tiny-ar-ONNX",
                tokensRepoSlug = "sherpa-onnx-moonshine-base-ar-quantized-2026-02-27",
                encoderBytes = 8_012_610L,
                encoderSha256 = "9bdb34ef41b07c0d70737d1eed5977cad575939cea56e8cb596b2ebd6cf4fb83",
                decoderBytes = 20_376_763L,
                decoderSha256 = "c3d7481beb40f0880155195a12c619e035fd67249b8d0315d90f7f32dca4d380",
            ),
            "uk" to onnxCommunityMoonshineArtifacts(
                onnxRepoSlug = "moonshine-tiny-uk-ONNX",
                tokensRepoSlug = "sherpa-onnx-moonshine-base-uk-quantized-2026-02-27",
                encoderBytes = 8_099_966L,
                encoderSha256 = "d90eb3b0da03985227e0854a394df8702f5cc07f6796c14589b47031a11b438d",
                decoderBytes = 30_024_627L,
                decoderSha256 = "4563f11f74c5b25cf0f758dc439fc89e8c3399a31b46be4d9d9e543eb7606dc6",
            ),
            "vi" to onnxCommunityMoonshineArtifacts(
                onnxRepoSlug = "moonshine-tiny-vi-ONNX",
                tokensRepoSlug = "sherpa-onnx-moonshine-base-vi-quantized-2026-02-27",
                encoderBytes = 8_099_966L,
                encoderSha256 = "6c1faa27cade57ed598e464b7c6040e6af5e145818f5730f1b6cf34fe2b7d93b",
                decoderBytes = 30_024_628L,
                decoderSha256 = "4c185d5bd4cb7b8d55bb737bd5b2f89ef451a892123217c4c97abbc902fb231d",
            ),
        )

        internal val MOONSHINE_BASE_ARTIFACTS_BY_LANG: Map<String, List<ArtifactEntry>> = mapOf(
            "en" to DEFAULT_MOONSHINE_BASE_ARTIFACTS,
            "ja" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-ja-quantized-2026-02-27",
                encoderBytes = 31_326_816L,
                encoderSha256 = "3230cafb84d5f08800e60de6f932aad7c69c649a37fa6997fe04fe25f808b56b",
                decoderBytes = 109_424_424L,
                decoderSha256 = "35a522052d2d8695d0dd2870666f088d633111e08cfbed0418f61c4122b4ba25",
            ),
            "zh" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-zh-quantized-2026-02-27",
                encoderBytes = 31_326_816L,
                encoderSha256 = "c725a24b58595905921ea2a47e2bcf0f18c78f4d171d96136f2dcbc8c77a58a6",
                decoderBytes = 109_424_520L,
                decoderSha256 = "bf79fce626e123739ec37eceb2b2a010a93d720da266dd5d8ef9a47ef9a7dc36",
            ),
            "ko" to onnxCommunityMoonshineArtifacts(
                onnxRepoSlug = "moonshine-base-ko-ONNX",
                tokensRepoSlug = "sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27",
                encoderBytes = 20_656_286L,
                encoderSha256 = "f64b4fcca08b072907e3c5f3176728753eb2aeb57bcf575b6d22a80094ab1338",
                decoderBytes = 42_752_973L,
                decoderSha256 = "cd4bd94a83ac508c6dd78d7d513a525ce8e5b099cecc26156e1a952a0a8f80df",
            ),
            "es" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-es-quantized-2026-02-27",
                encoderBytes = 20_964_320L,
                encoderSha256 = "331aafa2fc7f7e55ba28376eef08eaf919ae105bb719cd64ba6875505dca72b3",
                decoderBytes = 43_612_200L,
                decoderSha256 = "8e6513ad66a3a71ca86824746a09051eeb60468940eaf8201ce758e8919e2b5d",
                tokensBytes = 532_090L,
            ),
            "ar" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-ar-quantized-2026-02-27",
                encoderBytes = 31_326_824L,
                encoderSha256 = "68e50ebe0317ce909f098044a5dda2a76e6b86dc882829a317771fbafc5826ae",
                decoderBytes = 109_424_552L,
                decoderSha256 = "8f272cb50818e28ad86bbffc21e1450a4d57155e95f099ca6a236f38f4d9eafb",
            ),
            "uk" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-uk-quantized-2026-02-27",
                encoderBytes = 31_326_816L,
                encoderSha256 = "ea37ff7a2c308b566def1fd6e6860e23db4968590f192d5cc0fbc494666e30f9",
                decoderBytes = 109_424_424L,
                decoderSha256 = "2c0c1ebc20ba75a21ff5315fd039e53d2fc9ba77a9ca9ad3bc4c7c1c1de93cbc",
            ),
            "vi" to sherpaMoonshineV2OrtArtifacts(
                repoSlug = "sherpa-onnx-moonshine-base-vi-quantized-2026-02-27",
                encoderBytes = 31_326_816L,
                encoderSha256 = "5a63cf0e248ef463776fbad50784813b4185eb73136f1fb062c5722e667130dc",
                decoderBytes = 109_424_520L,
                decoderSha256 = "0a4f007e9d585348d94124d8de47f7aefbc3e2d3fa44152646af7b94c639770d",
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
