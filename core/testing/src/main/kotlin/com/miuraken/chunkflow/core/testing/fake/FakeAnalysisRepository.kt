package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ChunkTranscriptionTrialResult
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * テストとプレビュー用の [AnalysisRepository]。
 *
 * 教材ごとの解析結果をメモリ上に持つ。[put] でフィクスチャ（[SampleMaterial]）を入れるか、[update] で個別に書き換える。
 * 解析の要求は実行せず、[requestedAnalyses] などに記録するだけ。
 */
class FakeAnalysisRepository : AnalysisRepository {
    /**
     * 教材 1 件分の解析結果。
     *
     * @property status 段階ごとの状態。
     * @property waveform 波形。
     * @property chunks チャンク。
     * @property transcripts チャンクごとの文字起こし。
     * @property availableOrigins 利用可能なチャンク出どころの明示指定（`null` の場合は [chunks] および [chunksByOrigin] から算出）。
     * @property chunksByOrigin 出どころ別のチャンク一覧（[setChunkSource] で切り替えを再現したい場合に設定する）。
     * @property lookaheadStatus 端末上の先読み簡易文字起こし状態（F6.2）。
     * @property bookmarks ブックマーク一覧（F8.1）。
     */
    data class Entry(
        val status: Map<AnalysisStage, StageStatus> = emptyMap(),
        val waveform: Waveform? = null,
        val chunks: List<Chunk> = emptyList(),
        val transcripts: Map<ChunkId, Transcript> = emptyMap(),
        val availableOrigins: Set<ChunkOrigin>? = null,
        val chunksByOrigin: Map<ChunkOrigin, List<Chunk>> = emptyMap(),
        val lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
        val bookmarks: List<Bookmark> = emptyList(),
    )

    private val entries = MutableStateFlow<Map<MediaId, Entry>>(emptyMap())
    private var nextBookmarkId = 1L
    private var nextManualChunkId = 10_000L

    /** [requestAnalysis] の呼び出し（呼ばれた順）。 */
    val requestedAnalyses: MutableList<Pair<MediaId, Set<AnalysisStage>>> = mutableListOf()

    /** [resegment] の呼び出し（呼ばれた順）。 */
    val resegmentRequests: MutableList<Pair<MediaId, SegmentationParams>> = mutableListOf()

    /** [setCustomSegmentationParams] で設定された教材ごとの分割パラメータ（`null` は全体設定に従う）。 */
    val customSegmentationParamsByMedia: MutableMap<MediaId, SegmentationParams?> = mutableMapOf()

    /** [startLanguageDetectionOnly] の呼び出し履歴（呼ばれた順）。 */
    val languageDetectionOnlyRequests: MutableList<MediaId> = mutableListOf()

    /** [setChunkSource] で設定された出どころ。 */
    val chunkSources: MutableMap<MediaId, ChunkOrigin> = mutableMapOf()

    /** [setTranscriptOffset] で設定された補正値。 */
    val transcriptOffsets: MutableMap<MediaId, Int> = mutableMapOf()

    /** [autoAlignTranscriptOffset] が返す自動補正値のスタブ設定。 */
    val autoAlignedOffsets: MutableMap<MediaId, Int?> = mutableMapOf()

    /** [triggerLookaheadAt] の呼び出し履歴（呼ばれた順）。 */
    val lookaheadTriggers: MutableList<Pair<MediaId, ChunkId?>> = mutableListOf()

    /** [prioritizeChunkTranscription] の呼び出し履歴（呼ばれた順）。 */
    val prioritizedChunkRequests: MutableList<Pair<MediaId, ChunkId>> = mutableListOf()

    /** [retranscribeChunk] の呼び出し履歴（呼ばれた順）。 */
    val retranscribeChunkRequests: MutableList<Pair<MediaId, ChunkId>> = mutableListOf()

    /** [tryTranscribeChunkCustom] の呼び出し履歴（呼ばれた順）。 */
    data class CustomTrialRequest(
        val mediaId: MediaId,
        val chunkId: ChunkId,
        val engine: OnDeviceTranscriptionEngine,
        val languageOverride: LanguageTag?,
        val audioSpeed: Float,
    )

    val customTrialRequests: MutableList<CustomTrialRequest> = mutableListOf()

    /** [tryTranscribeChunkCustom] が返すスタブ結果。 */
    var customTrialResultStub: ChunkTranscriptionTrialResult? = null

    /** [mergeChunks] の呼び出し履歴（呼ばれた順）。 */
    val mergeChunksRequests: MutableList<Pair<MediaId, List<ChunkId>>> = mutableListOf()

    /** 現在プレイヤー画面で開いているとして通知されている教材 ID の集合。 */
    val openTranscriptionSessions: MutableSet<MediaId> = mutableSetOf()

    /** [setMediaTranscriptionSessionOpen] の呼び出し履歴（呼ばれた順）。 */
    val transcriptionSessionEvents: MutableList<Pair<MediaId, Boolean>> = mutableListOf()

    /** [prioritizeOpenedMediaAnalysis] の呼び出し履歴（呼ばれた順）。 */
    val prioritizedMediaAnalyses: MutableList<MediaId> = mutableListOf()

    /** [queryLanguageModelInfos] が返す言語ごとのモデル情報のスタブ設定。 */
    val languageModelInfos: MutableMap<LanguageTag, OnDeviceLanguageModelInfo> = mutableMapOf()

    /** [requestRecognitionModelDownload] の呼び出し履歴（呼ばれた順）。 */
    val requestedModelDownloads: MutableList<LanguageTag> = mutableListOf()

    /** [downloadSherpaModel] の呼び出し履歴（呼ばれた順）。 */
    val requestedSherpaDownloads: MutableList<LanguageTag> = mutableListOf()

    /** [deleteSherpaModel] の呼び出し履歴（呼ばれた順）。 */
    val requestedSherpaDeletions: MutableList<LanguageTag> = mutableListOf()

    /** フィクスチャの解析結果を入れる。 */
    fun put(material: SampleMaterial) {
        update(material.item.id) {
            Entry(
                status = material.analysis,
                waveform = material.waveform,
                chunks = material.chunks,
                transcripts = material.transcripts,
                chunksByOrigin = material.chunks.groupBy { it.origin },
            )
        }
    }

    /** 教材 [id] の解析結果を [transform] で書き換える。 */
    fun update(id: MediaId, transform: (Entry) -> Entry) {
        entries.update { map -> map + (id to transform(map[id] ?: Entry())) }
    }

    override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = entries.map { it[id]?.status.orEmpty() }

    override fun observeWaveform(id: MediaId): Flow<Waveform?> = entries.map { it[id]?.waveform }

    override fun observeChunks(id: MediaId): Flow<List<Chunk>> = entries.map { it[id]?.chunks.orEmpty() }

    override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = entries.map { it[id]?.transcripts.orEmpty() }

    override fun observeAvailableChunkOrigins(id: MediaId): Flow<Set<ChunkOrigin>> = entries.map { map ->
        val entry = map[id] ?: return@map emptySet()
        entry.availableOrigins
            ?: (entry.chunksByOrigin.keys + entry.chunks.map { it.origin })
    }

    override fun observeLookaheadStatus(id: MediaId): Flow<LookaheadTranscriptionStatus> = entries.map { it[id]?.lookaheadStatus ?: LookaheadTranscriptionStatus() }

    override fun observeBookmarks(id: MediaId): Flow<List<Bookmark>> = entries.map { map ->
        map[id]?.bookmarks.orEmpty().sortedWith(compareBy<Bookmark> { it.positionMs }.thenBy { it.id })
    }

    override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) {
        requestedAnalyses += id to stages
    }

    override suspend fun resegment(id: MediaId, params: SegmentationParams) {
        resegmentRequests += id to params
        languageDetectionOnlyRequests += id
        update(id) { entry ->
            val autoChunks = entry.chunksByOrigin[ChunkOrigin.AUTO]
                ?: entry.chunks.filter { it.origin != ChunkOrigin.IMPORTED && it.origin != ChunkOrigin.MANUAL }
                    .ifEmpty { entry.chunks.map { it.copy(origin = ChunkOrigin.AUTO) } }
            entry.copy(
                chunks = autoChunks,
                chunksByOrigin = (entry.chunksByOrigin - ChunkOrigin.IMPORTED - ChunkOrigin.MANUAL) +
                    (ChunkOrigin.AUTO to autoChunks),
            )
        }
        chunkSources[id] = ChunkOrigin.AUTO
    }

    override suspend fun setCustomSegmentationParams(id: MediaId, params: SegmentationParams?) {
        customSegmentationParamsByMedia[id] = params
    }

    override suspend fun startLanguageDetectionOnly(id: MediaId) {
        languageDetectionOnlyRequests += id
    }

    override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) {
        chunkSources[id] = source
        entries.update { map ->
            val current = map[id] ?: return@update map
            val switched = current.chunksByOrigin[source] ?: return@update map
            map + (id to current.copy(chunks = switched))
        }
    }

    override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) {
        transcriptOffsets[id] = offsetMs
    }

    override suspend fun updateChunkLanguage(id: MediaId, chunkId: ChunkId, language: LanguageTag?) {
        update(id) { entry ->
            val existing = entry.transcripts[chunkId] ?: return@update entry
            val updated = existing.copy(
                language = language,
                languageConfidence = 1.0f,
                userEdited = true,
            )
            entry.copy(transcripts = entry.transcripts + (chunkId to updated))
        }
    }

    override suspend fun bulkSetUnknownTranscriptLanguage(id: MediaId, language: LanguageTag) {
        update(id) { entry ->
            val updatedTranscripts = entry.transcripts.mapValues { (_, transcript) ->
                val tag = transcript.language?.value
                if (tag.isNullOrBlank() || tag == "und") {
                    transcript.copy(
                        language = language,
                        languageConfidence = 1.0f,
                        userEdited = true,
                    )
                } else {
                    transcript
                }
            }
            entry.copy(transcripts = updatedTranscripts)
        }
    }

    override suspend fun autoAlignTranscriptOffset(id: MediaId): Int? {
        val aligned = autoAlignedOffsets[id]
        if (aligned != null) {
            transcriptOffsets[id] = aligned
        }
        return aligned
    }

    /** [resetTranscription] の呼び出し履歴（呼ばれた順）。 */
    val resetTranscriptionRequests: MutableList<MediaId> = mutableListOf()

    /** [setBackgroundTranscriptionEnabled] の呼び出し履歴（呼ばれた順）。 */
    val backgroundTranscriptionRequests: MutableList<Pair<MediaId, Boolean>> = mutableListOf()

    override suspend fun setLookaheadEnabled(id: MediaId, enabled: Boolean) {
        update(id) { entry ->
            val nextStatus = if (enabled) {
                entry.lookaheadStatus.copy(
                    enabled = true,
                    backgroundEnabled = true,
                )
            } else {
                entry.lookaheadStatus.copy(
                    enabled = false,
                    backgroundEnabled = false,
                    inFlightChunkId = null,
                    inFlightStep = null,
                    inFlightDetail = null,
                    inFlightStartedAtEpochMs = null,
                    isForcedChunkInFlight = false,
                    queuedChunkIds = emptySet(),
                )
            }
            entry.copy(lookaheadStatus = nextStatus)
        }
    }

    override suspend fun setBackgroundTranscriptionEnabled(id: MediaId, enabled: Boolean) {
        backgroundTranscriptionRequests += id to enabled
        update(id) { entry ->
            entry.copy(
                lookaheadStatus = entry.lookaheadStatus.copy(backgroundEnabled = enabled),
            )
        }
    }

    override suspend fun resetTranscription(id: MediaId) {
        resetTranscriptionRequests += id
        update(id) { entry ->
            entry.copy(
                lookaheadStatus = entry.lookaheadStatus.copy(
                    enabled = false,
                    backgroundEnabled = false,
                    inFlightChunkId = null,
                    inFlightStep = null,
                    inFlightDetail = null,
                    inFlightStartedAtEpochMs = null,
                    isForcedChunkInFlight = false,
                    queuedChunkIds = emptySet(),
                    skippedEmptyChunkIds = emptySet(),
                ),
            )
        }
    }

    override suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean {
        requestedModelDownloads += language
        val existing = languageModelInfos[language]
        languageModelInfos[language] = (existing ?: OnDeviceLanguageModelInfo(language = language)).copy(
            status = OnDeviceLanguageModelStatus.DOWNLOADING,
        )
        return true
    }

    override suspend fun downloadSherpaModel(language: LanguageTag): Boolean {
        requestedSherpaDownloads += language
        val existing = languageModelInfos[language] ?: OnDeviceLanguageModelInfo(language = language)
        languageModelInfos[language] = existing.copy(
            sherpaModelStatus = OnDeviceLanguageModelStatus.INSTALLED,
            sherpaModelId = "sherpa-onnx-whisper-tiny",
            sherpaModelSizeBytes = 103_609_903L,
            canDeleteSherpaModel = true,
        )
        return true
    }

    override suspend fun deleteSherpaModel(language: LanguageTag): Boolean {
        requestedSherpaDeletions += language
        val existing = languageModelInfos[language] ?: OnDeviceLanguageModelInfo(language = language)
        languageModelInfos[language] = existing.copy(
            sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
            canDeleteSherpaModel = false,
        )
        return true
    }

    /** [requestSttModelDownload] の呼び出し履歴（呼ばれた順）。 */
    val requestedSttModelDownloads: MutableList<Pair<LanguageTag, SttEngine>> = mutableListOf()

    /** [downloadSttModel] の呼び出し履歴（呼ばれた順）。 */
    val downloadedSttModels: MutableList<com.miuraken.chunkflow.core.model.DownloadableSttModel> = mutableListOf()

    /** [purgeSttModel] の呼び出し履歴（呼ばれた順）。 */
    val purgedSttModels: MutableList<com.miuraken.chunkflow.core.model.DownloadableSttModel> = mutableListOf()

    /** [deleteSttModelForLanguage] の呼び出し履歴（呼ばれた順）。 */
    val deletedLanguageSttModels: MutableList<Pair<LanguageTag, com.miuraken.chunkflow.core.model.DownloadableSttModel>> = mutableListOf()

    /** [queryDownloadableModelStates] が返すダウンロード可能モデルの状態マップ。 */
    val downloadableModelStates: MutableMap<
        com.miuraken.chunkflow.core.model.DownloadableSttModel,
        com.miuraken.chunkflow.core.model.DownloadableSttModelState,
        > = com.miuraken.chunkflow.core.model.DownloadableSttModel.entries.associateWith {
        com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = it)
    }.toMutableMap()

    override suspend fun requestSttModelDownload(language: LanguageTag, engine: SttEngine): Boolean {
        requestedSttModelDownloads += language to engine
        return when (engine) {
            SttEngine.SHERPA_ONNX -> downloadSherpaModel(language)

            SttEngine.SPEECH_RECOGNIZER -> {
                val existing = languageModelInfos[language] ?: OnDeviceLanguageModelInfo(language = language)
                languageModelInfos[language] = existing.copy(status = OnDeviceLanguageModelStatus.DOWNLOADING)
                true
            }

            SttEngine.ML_KIT -> {
                val existing = languageModelInfos[language] ?: OnDeviceLanguageModelInfo(language = language)
                languageModelInfos[language] = existing.copy(mlKitStatus = OnDeviceLanguageModelStatus.DOWNLOADING)
                true
            }

            SttEngine.MOONSHINE_TINY,
            SttEngine.MOONSHINE_BASE,
            -> {
                val model = engine.downloadableModel ?: return false
                if (!model.isLanguageSupported(language)) return false
                val existingInfo = languageModelInfos[language] ?: OnDeviceLanguageModelInfo(language = language)
                languageModelInfos[language] = existingInfo.copy(
                    extraEngineStatuses = existingInfo.extraEngineStatuses + (engine to OnDeviceLanguageModelStatus.INSTALLED),
                )
                val currentState = downloadableModelStates[model]
                    ?: com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = model)
                val normalizedCode = language.value.trim().lowercase().replace('_', '-').substringBefore('-')
                val normalizedTag = LanguageTag(if (normalizedCode == "cmn") "zh" else normalizedCode)
                val updatedDownloaded = (currentState.downloadedLanguages + normalizedTag).distinct()
                val totalBytes = updatedDownloaded.sumOf { model.approxSizeMbForLanguage(it).toLong() * 1_000_000L }
                downloadableModelStates[model] = currentState.copy(
                    status = OnDeviceLanguageModelStatus.INSTALLED,
                    sizeBytes = totalBytes,
                    canPurge = true,
                    downloadedLanguages = updatedDownloaded,
                )
                true
            }

            SttEngine.SHERPA_ONNX_SENSE_VOICE,
            SttEngine.SHERPA_ONNX_WHISPER_BASE,
            SttEngine.SHERPA_ONNX_WHISPER_SMALL,
            -> {
                val model = engine.downloadableModel ?: return false
                downloadSttModel(model)
            }

            SttEngine.OPENAI_API,
            SttEngine.GROQ_API,
            SttEngine.GEMINI_API,
            -> false
        }
    }

    override suspend fun queryDownloadableModelStates(): List<com.miuraken.chunkflow.core.model.DownloadableSttModelState> = com.miuraken.chunkflow.core.model.DownloadableSttModel.entries.map { model ->
        downloadableModelStates[model] ?: com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = model)
    }

    override suspend fun downloadSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean {
        downloadedSttModels += model
        val downloadedLangs = if (model.isPerLanguageModel) listOf(LanguageTag("en")) else emptyList()
        downloadableModelStates[model] = com.miuraken.chunkflow.core.model.DownloadableSttModelState(
            model = model,
            status = OnDeviceLanguageModelStatus.INSTALLED,
            sizeBytes = model.estimatedSizeBytes,
            canPurge = true,
            downloadedLanguages = downloadedLangs,
        )
        for ((lang, existing) in languageModelInfos.entries) {
            if (model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY) {
                languageModelInfos[lang] = existing.copy(
                    sherpaModelStatus = OnDeviceLanguageModelStatus.INSTALLED,
                    canDeleteSherpaModel = true,
                )
            } else if (!model.isPerLanguageModel || lang.value.startsWith("en", ignoreCase = true)) {
                languageModelInfos[lang] = existing.copy(
                    extraEngineStatuses = existing.extraEngineStatuses + (model.sttEngine to OnDeviceLanguageModelStatus.INSTALLED),
                )
            }
        }
        return true
    }

    override suspend fun purgeSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean {
        purgedSttModels += model
        downloadableModelStates[model] = com.miuraken.chunkflow.core.model.DownloadableSttModelState(
            model = model,
            status = OnDeviceLanguageModelStatus.NOT_INSTALLED,
            sizeBytes = model.estimatedSizeBytes,
            canPurge = false,
            downloadedLanguages = emptyList(),
        )
        for ((lang, existing) in languageModelInfos.entries) {
            if (model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY) {
                languageModelInfos[lang] = existing.copy(
                    sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                    canDeleteSherpaModel = false,
                )
            } else {
                languageModelInfos[lang] = existing.copy(
                    extraEngineStatuses = existing.extraEngineStatuses + (model.sttEngine to OnDeviceLanguageModelStatus.NOT_INSTALLED),
                )
            }
        }
        return true
    }

    override suspend fun deleteSttModelForLanguage(
        language: LanguageTag,
        model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ): Boolean {
        deletedLanguageSttModels += language to model
        if (model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY) {
            return deleteSherpaModel(language)
        }
        val existingInfo = languageModelInfos[language] ?: OnDeviceLanguageModelInfo(language = language)
        languageModelInfos[language] = existingInfo.copy(
            extraEngineStatuses = existingInfo.extraEngineStatuses + (model.sttEngine to OnDeviceLanguageModelStatus.NOT_INSTALLED),
        )
        val currentState = downloadableModelStates[model]
            ?: com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = model)
        val normalizedCode = language.value.trim().lowercase().replace('_', '-').substringBefore('-')
        val targetCode = if (normalizedCode == "cmn") "zh" else normalizedCode
        val remaining = currentState.downloadedLanguages.filterNot {
            val c = it.value.trim().lowercase().replace('_', '-').substringBefore('-')
            (if (c == "cmn") "zh" else c) == targetCode
        }
        val isStillInstalled = if (model.isPerLanguageModel) remaining.isNotEmpty() else false
        val totalBytes = if (isStillInstalled) {
            remaining.sumOf { model.approxSizeMbForLanguage(it).toLong() * 1_000_000L }
        } else {
            model.estimatedSizeBytes
        }
        downloadableModelStates[model] = currentState.copy(
            status = if (isStillInstalled) OnDeviceLanguageModelStatus.INSTALLED else OnDeviceLanguageModelStatus.NOT_INSTALLED,
            sizeBytes = totalBytes,
            canPurge = isStillInstalled,
            downloadedLanguages = remaining,
        )
        return true
    }

    override suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> {
        val requestedSet = languages.toSet()
        val extraLanguages = languageModelInfos.keys.filter { it !in requestedSet }
        return (languages + extraLanguages).map { lang ->
            val base = languageModelInfos[lang] ?: OnDeviceLanguageModelInfo(language = lang)
            val mergedExtras = base.extraEngineStatuses.toMutableMap()
            var sherpaStatus = base.sherpaModelStatus
            var canDeleteSherpa = base.canDeleteSherpaModel
            for ((model, state) in downloadableModelStates) {
                if (!model.isLanguageSupported(lang)) {
                    if (model != com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY) {
                        mergedExtras[model.sttEngine] = OnDeviceLanguageModelStatus.UNSUPPORTED
                    }
                    continue
                }
                if (model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY) {
                    if (state.status == OnDeviceLanguageModelStatus.INSTALLED) {
                        sherpaStatus = OnDeviceLanguageModelStatus.INSTALLED
                        canDeleteSherpa = true
                    }
                } else if (!base.extraEngineStatuses.containsKey(model.sttEngine)) {
                    if (model.isPerLanguageModel) {
                        val langCode = lang.value.trim().lowercase().replace('_', '-').substringBefore('-')
                        val normalizedLangCode = if (langCode == "cmn") "zh" else langCode
                        val isLangDownloaded = state.downloadedLanguages.any {
                            val c = it.value.trim().lowercase().replace('_', '-').substringBefore('-')
                            (if (c == "cmn") "zh" else c) == normalizedLangCode
                        }
                        mergedExtras[model.sttEngine] = if (isLangDownloaded) {
                            OnDeviceLanguageModelStatus.INSTALLED
                        } else {
                            OnDeviceLanguageModelStatus.NOT_INSTALLED
                        }
                    } else {
                        mergedExtras[model.sttEngine] = state.status
                    }
                }
            }
            base.copy(
                sherpaModelStatus = sherpaStatus,
                canDeleteSherpaModel = canDeleteSherpa,
                extraEngineStatuses = mergedExtras,
            )
        }
    }

    override fun setMediaTranscriptionSessionOpen(id: MediaId, open: Boolean) {
        transcriptionSessionEvents += id to open
        if (open) {
            openTranscriptionSessions.clear()
            openTranscriptionSessions.add(id)
        } else {
            openTranscriptionSessions.remove(id)
            update(id) { entry ->
                if (entry.lookaheadStatus.backgroundEnabled) {
                    entry
                } else {
                    entry.copy(lookaheadStatus = entry.lookaheadStatus.copy(queuedChunkIds = emptySet()))
                }
            }
        }
    }

    override suspend fun prioritizeOpenedMediaAnalysis(id: MediaId) {
        prioritizedMediaAnalyses += id
    }

    override suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) {
        if (transcriptionSessionEvents.isNotEmpty() && id !in openTranscriptionSessions) return
        lookaheadTriggers += id to currentChunkId
    }

    override suspend fun prioritizeChunkTranscription(id: MediaId, chunkId: ChunkId) {
        if (transcriptionSessionEvents.isNotEmpty() && id !in openTranscriptionSessions) return
        prioritizedChunkRequests += id to chunkId
        triggerLookaheadAt(id, chunkId)
    }

    override suspend fun retranscribeChunk(id: MediaId, chunkId: ChunkId) {
        retranscribeChunkRequests += id to chunkId
    }

    /** [clearTranscriptsForMedia] の呼び出し履歴（呼ばれた順）。 */
    val clearTranscriptsRequests: MutableList<MediaId> = mutableListOf()

    override suspend fun clearTranscriptsForMedia(id: MediaId) {
        clearTranscriptsRequests += id
        update(id) { entry ->
            entry.copy(transcripts = emptyMap())
        }
    }

    override suspend fun toggleChunkBookmark(id: MediaId, chunkId: ChunkId, note: String): Boolean {
        var added = false
        update(id) { entry ->
            val targetChunk = entry.chunks.firstOrNull { it.id == chunkId } ?: return@update entry
            val matching = entry.bookmarks.filter { it.positionMs in targetChunk.startMs until targetChunk.endMs }
            val nextBookmarks = if (matching.isNotEmpty()) {
                added = false
                entry.bookmarks.filterNot { it.positionMs in targetChunk.startMs until targetChunk.endMs }
            } else {
                added = true
                entry.bookmarks + Bookmark(
                    id = nextBookmarkId++,
                    mediaId = id,
                    positionMs = targetChunk.startMs,
                    note = note,
                )
            }
            entry.copy(bookmarks = nextBookmarks)
        }
        return added
    }

    override suspend fun updateChunkText(id: MediaId, chunkId: ChunkId, text: String) {
        update(id) { entry ->
            val existing = entry.transcripts[chunkId]
            val updated = if (existing != null) {
                existing.copy(
                    text = text,
                    textVariants = emptyMap(),
                    words = emptyList(),
                    userEdited = true,
                )
            } else {
                Transcript(
                    chunkId = chunkId,
                    text = text,
                    language = null,
                    languageConfidence = null,
                    engine = EngineId("manual"),
                    userEdited = true,
                )
            }
            entry.copy(transcripts = entry.transcripts + (chunkId to updated))
        }
    }

    override suspend fun tryTranscribeChunkCustom(
        id: MediaId,
        chunkId: ChunkId,
        engine: OnDeviceTranscriptionEngine,
        languageOverride: LanguageTag?,
        audioSpeed: Float,
    ): ChunkTranscriptionTrialResult? {
        customTrialRequests += CustomTrialRequest(
            mediaId = id,
            chunkId = chunkId,
            engine = engine,
            languageOverride = languageOverride,
            audioSpeed = audioSpeed,
        )
        return customTrialResultStub
    }

    override suspend fun applyChunkTranscriptionTrial(
        id: MediaId,
        chunkId: ChunkId,
        text: String,
        language: LanguageTag?,
    ) {
        update(id) { entry ->
            val existing = entry.transcripts[chunkId]
            val updated = if (existing != null) {
                existing.copy(
                    text = text,
                    language = language ?: existing.language,
                    textVariants = emptyMap(),
                    words = emptyList(),
                    userEdited = true,
                )
            } else {
                Transcript(
                    chunkId = chunkId,
                    text = text,
                    language = language,
                    languageConfidence = null,
                    engine = EngineId("manual"),
                    userEdited = true,
                )
            }
            entry.copy(transcripts = entry.transcripts + (chunkId to updated))
        }
    }

    override suspend fun splitChunk(id: MediaId, chunkId: ChunkId, splitPositionMs: Long): Boolean {
        var success = false
        update(id) { entry ->
            val idx = entry.chunks.indexOfFirst { it.id == chunkId }
            if (idx < 0) return@update entry
            val target = entry.chunks[idx]
            if (splitPositionMs <= target.startMs || splitPositionMs >= target.endMs) {
                return@update entry
            }
            success = true
            val first = target.copy(endMs = splitPositionMs, origin = ChunkOrigin.MANUAL)
            val second = Chunk(
                id = ChunkId(nextManualChunkId++),
                startMs = splitPositionMs,
                endMs = target.endMs,
                origin = ChunkOrigin.MANUAL,
                isSkipped = target.isSkipped,
            )
            val manualChunks = entry.chunks.flatMapIndexed { index, chunk ->
                if (index == idx) {
                    listOf(first, second)
                } else {
                    listOf(chunk.copy(origin = ChunkOrigin.MANUAL))
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    /** [addChunk] の呼び出し履歴（呼ばれた順）。 */
    val addChunkRequests: MutableList<Triple<MediaId, Long, Long>> = mutableListOf()

    override suspend fun addChunk(id: MediaId, startMs: Long, endMs: Long): Boolean {
        addChunkRequests += Triple(id, startMs, endMs)
        if (startMs < 0L || startMs >= endMs) return false
        var success = false
        update(id) { entry ->
            if (entry.chunks.any { startMs < it.endMs && endMs > it.startMs }) {
                return@update entry
            }
            success = true
            val newChunk = Chunk(
                id = ChunkId(nextManualChunkId++),
                startMs = startMs,
                endMs = endMs,
                origin = ChunkOrigin.MANUAL,
                isSkipped = false,
            )
            val manualChunks = (entry.chunks.map { it.copy(origin = ChunkOrigin.MANUAL) } + newChunk)
                .sortedBy { it.startMs }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    /** [deleteChunk] の呼び出し履歴（呼ばれた順）。 */
    val deleteChunkRequests: MutableList<Pair<MediaId, ChunkId>> = mutableListOf()

    /** [deleteChunks] の呼び出し履歴（呼ばれた順）。 */
    val deleteChunksRequests: MutableList<Pair<MediaId, Set<ChunkId>>> = mutableListOf()

    override suspend fun deleteChunk(id: MediaId, chunkId: ChunkId): Boolean {
        deleteChunkRequests += id to chunkId
        return deleteChunks(id, setOf(chunkId))
    }

    override suspend fun deleteChunks(id: MediaId, chunkIds: Set<ChunkId>): Boolean {
        if (chunkIds.isEmpty()) return false
        deleteChunksRequests += id to chunkIds
        var success = false
        update(id) { entry ->
            if (entry.chunks.none { it.id in chunkIds }) return@update entry
            success = true
            val manualChunks = entry.chunks.filterNot { it.id in chunkIds }
                .map { it.copy(origin = ChunkOrigin.MANUAL) }
            val updatedTranscripts = entry.transcripts - chunkIds
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                transcripts = updatedTranscripts,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    override suspend fun mergeWithNextChunk(id: MediaId, chunkId: ChunkId): Boolean {
        var success = false
        update(id) { entry ->
            val idx = entry.chunks.indexOfFirst { it.id == chunkId }
            if (idx < 0 || idx + 1 >= entry.chunks.size) return@update entry
            val current = entry.chunks[idx]
            val next = entry.chunks[idx + 1]
            success = true

            val merged = current.copy(
                endMs = next.endMs,
                origin = ChunkOrigin.MANUAL,
                isSkipped = current.isSkipped && next.isSkipped,
            )
            val manualChunks = buildList {
                for (i in entry.chunks.indices) {
                    when (i) {
                        idx -> add(merged)
                        idx + 1 -> Unit
                        else -> add(entry.chunks[i].copy(origin = ChunkOrigin.MANUAL))
                    }
                }
            }
            val currentTranscript = entry.transcripts[current.id]
            val nextTranscript = entry.transcripts[next.id]
            val updatedTranscripts = entry.transcripts.toMutableMap().apply {
                remove(next.id)
                if (currentTranscript != null && nextTranscript != null) {
                    val combinedText = listOf(currentTranscript.text, nextTranscript.text)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    put(
                        current.id,
                        currentTranscript.copy(
                            text = combinedText,
                            textVariants = emptyMap(),
                            words = emptyList(),
                            userEdited = true,
                        ),
                    )
                } else if (currentTranscript == null && nextTranscript != null) {
                    put(current.id, nextTranscript.copy(chunkId = current.id, userEdited = true))
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                transcripts = updatedTranscripts,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    override suspend fun mergeChunks(id: MediaId, chunkIds: List<ChunkId>): Boolean {
        mergeChunksRequests += id to chunkIds
        if (chunkIds.size < 2) return false
        var success = false
        update(id) { entry ->
            val indices = chunkIds.map { chunkId -> entry.chunks.indexOfFirst { it.id == chunkId } }.sorted()
            if (indices.any { it < 0 } || indices.distinct().size != indices.size) return@update entry
            val firstIndex = indices.first()
            val lastIndex = indices.last()
            success = true

            val first = entry.chunks[firstIndex]
            val mergedSkipped = (firstIndex..lastIndex).all { entry.chunks[it].isSkipped }
            val merged = first.copy(
                endMs = entry.chunks[lastIndex].endMs,
                origin = ChunkOrigin.MANUAL,
                isSkipped = mergedSkipped,
            )
            val manualChunks = entry.chunks.mapIndexedNotNull { index, chunk ->
                when (index) {
                    firstIndex -> merged
                    in (firstIndex + 1)..lastIndex -> null
                    else -> chunk.copy(origin = ChunkOrigin.MANUAL)
                }
            }
            // 文字起こしは半角スペース 1 文字を挟んで連結し、言語は先頭のチャンクの設定に合わせる
            val firstLanguage = entry.transcripts[first.id]?.language
            val sources = (firstIndex..lastIndex).mapNotNull { entry.transcripts[entry.chunks[it].id] }
            val updatedTranscripts = entry.transcripts.toMutableMap().apply {
                (firstIndex..lastIndex).forEach { remove(entry.chunks[it].id) }
                val base = sources.firstOrNull()
                if (base != null) {
                    put(
                        first.id,
                        base.copy(
                            chunkId = first.id,
                            text = sources.map { it.text }.filter { it.isNotBlank() }.joinToString(" "),
                            language = firstLanguage,
                            textVariants = emptyMap(),
                            words = emptyList(),
                            userEdited = true,
                        ),
                    )
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                transcripts = updatedTranscripts,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    override suspend fun moveChunkBoundary(
        id: MediaId,
        chunkId: ChunkId,
        newStartMs: Long,
        newEndMs: Long,
    ): Boolean {
        var success = false
        update(id) { entry ->
            val idx = entry.chunks.indexOfFirst { it.id == chunkId }
            if (idx < 0 || newStartMs < 0L || newStartMs >= newEndMs) return@update entry
            val current = entry.chunks[idx]
            val prev = entry.chunks.getOrNull(idx - 1)
            val next = entry.chunks.getOrNull(idx + 1)

            if (prev != null && newStartMs <= prev.startMs) return@update entry
            if (next != null && newEndMs >= next.endMs) return@update entry
            success = true

            val manualChunks = entry.chunks.mapIndexed { i, chunk ->
                when (i) {
                    idx - 1 -> if (chunk.endMs == current.startMs || newStartMs < chunk.endMs) {
                        chunk.copy(endMs = newStartMs, origin = ChunkOrigin.MANUAL)
                    } else {
                        chunk.copy(origin = ChunkOrigin.MANUAL)
                    }

                    idx -> chunk.copy(startMs = newStartMs, endMs = newEndMs, origin = ChunkOrigin.MANUAL)

                    idx + 1 -> if (chunk.startMs == current.endMs || newEndMs > chunk.startMs) {
                        chunk.copy(startMs = newEndMs, origin = ChunkOrigin.MANUAL)
                    } else {
                        chunk.copy(origin = ChunkOrigin.MANUAL)
                    }

                    else -> chunk.copy(origin = ChunkOrigin.MANUAL)
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    /** [translateChunk] の呼び出し履歴（呼ばれた順）。 */
    val translatedChunkRequests: MutableList<Triple<MediaId, ChunkId, Pair<LanguageTag, LanguageTag>>> = mutableListOf()

    /** [isTranslationSupported] でサポート対象とみなす言語コード（主言語サブタグ）の集合。 */
    val supportedTranslationLanguages: MutableSet<String> = mutableSetOf(
        "en", "ja", "ko", "zh", "cmn", "fr", "de", "es", "it", "pt", "ru", "ar", "hi", "th", "vi", "id", "nl", "pl", "tr", "uk",
    )

    /** [translateChunk] のカスタム翻訳関数スタブ。 */
    var translateChunkStub: ((String, LanguageTag, LanguageTag) -> String?)? = { text, _, targetLang ->
        "[${targetLang.value}] $text"
    }

    override suspend fun setChunksSkipped(
        id: MediaId,
        chunkIds: Set<ChunkId>,
        skipped: Boolean,
    ) {
        if (chunkIds.isEmpty()) return
        update(id) { entry ->
            val updatedChunks = entry.chunks.map { chunk ->
                if (chunk.id in chunkIds) chunk.copy(isSkipped = skipped) else chunk
            }
            val updatedByOrigin = entry.chunksByOrigin.mapValues { (_, list) ->
                list.map { chunk ->
                    if (chunk.id in chunkIds) chunk.copy(isSkipped = skipped) else chunk
                }
            }
            entry.copy(
                chunks = updatedChunks,
                chunksByOrigin = updatedByOrigin,
            )
        }
    }

    override fun isTranslationSupported(
        sourceLanguage: LanguageTag?,
        targetLanguage: LanguageTag?,
    ): Boolean {
        val src = normalizeTranslatePrimaryTag(sourceLanguage) ?: return false
        val dst = normalizeTranslatePrimaryTag(targetLanguage) ?: return false
        return src != dst && src in supportedTranslationLanguages && dst in supportedTranslationLanguages
    }

    override suspend fun translateChunk(
        id: MediaId,
        chunkId: ChunkId,
        sourceLanguage: LanguageTag,
        targetLanguage: LanguageTag,
    ): String? {
        if (!isTranslationSupported(sourceLanguage, targetLanguage)) return null
        translatedChunkRequests += Triple(id, chunkId, sourceLanguage to targetLanguage)
        val currentEntry = entries.value[id] ?: return null
        val transcript = currentEntry.transcripts[chunkId] ?: return null
        val rawText = transcript.text.trim()
        if (rawText.isEmpty()) return null
        if (!transcript.translatedText.isNullOrBlank() && transcript.translatedLanguage == targetLanguage) {
            return transcript.translatedText
        }
        val translated = translateChunkStub?.invoke(rawText, sourceLanguage, targetLanguage) ?: return null
        update(id) { entry ->
            val existing = entry.transcripts[chunkId] ?: return@update entry
            val updated = existing.copy(
                translatedText = translated,
                translatedLanguage = targetLanguage,
            )
            entry.copy(transcripts = entry.transcripts + (chunkId to updated))
        }
        return translated
    }

    private fun normalizeTranslatePrimaryTag(tag: LanguageTag?): String? {
        val raw = tag?.value?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (raw.isEmpty() || raw == "und") return null
        if (raw == "zh-yue" || raw == "zh-hk" || raw.startsWith("yue")) return "yue"
        val primary = raw.substringBefore('-')
        return if (primary == "cmn") "zh" else primary
    }
}
