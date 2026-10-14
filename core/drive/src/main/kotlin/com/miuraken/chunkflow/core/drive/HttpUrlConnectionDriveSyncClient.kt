package com.miuraken.chunkflow.core.drive

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [HttpURLConnection] を用いた [DriveSyncClient] の本番実装（§5.1、§7.1、§8.1、§8.3、§8.5、§9.2）。
 *
 * - Google Drive REST API v3 のみを呼び出し、通信はすべて TLS（テスト用のローカルループバックを除く）で行う。
 * - アクセストークン・ファイル名・文字起こし内容はログや例外に含めない（§8.5）。
 * - すべての応答とストリームにサイズ上限を設け、上限超過・途中切断・不正 JSON は型付きエラーに変換する（§8.1、§9.2）。
 */
@Singleton
class HttpUrlConnectionDriveSyncClient(
    private val authProvider: DriveAuthProvider,
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val uploadBaseUrl: String = DEFAULT_UPLOAD_BASE_URL,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : DriveSyncClient {

    @Inject
    constructor(
        authProvider: DriveAuthProvider,
    ) : this(
        authProvider = authProvider,
        apiBaseUrl = DEFAULT_API_BASE_URL,
        uploadBaseUrl = DEFAULT_UPLOAD_BASE_URL,
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS,
    )

    override suspend fun listChunkFlowCourses(): DriveListResult {
        val token = when (val auth = authProvider.acquireAccessToken()) {
            is DriveAuthResult.Authorized -> auth.accessToken
            DriveAuthResult.NotAuthorized -> return DriveListResult.Failure(DriveError.AUTH)
        }

        // 1. ルートの ChunkFlow フォルダを検索する
        val rootQuery = "name = '$ROOT_FOLDER_NAME' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val rootFolders = when (val res = listFilesByQuery(token, rootQuery)) {
            is InternalListOutcome.Success -> res.files
            is InternalListOutcome.Failure -> return DriveListResult.Failure(res.error)
        }
        val rootFolder = rootFolders.firstOrNull()
            ?: return DriveListResult.Failure(DriveError.FOLDER_NOT_FOUND)

        // 2. ChunkFlow 直下のコースフォルダ一覧を取得する
        val childrenQuery = "'${escapeQueryLiteral(rootFolder.id)}' in parents and trashed = false"
        val rootChildren = when (val res = listFilesByQuery(token, childrenQuery)) {
            is InternalListOutcome.Success -> res.files
            is InternalListOutcome.Failure -> return DriveListResult.Failure(res.error)
        }

        val courses = mutableListOf<DriveCourseFiles>()

        // ChunkFlow 直下に直接置かれた音声や transcription/ フォルダがある場合も取りこぼさない
        val directAudios = rootChildren.filter(::isAudioRemoteFile)
        val directTranscripts = mutableListOf<DriveRemoteFile>()
        directTranscripts += rootChildren.filter(::isTranscriptRemoteFile)
        val directTranscriptionFolder = rootChildren.firstOrNull {
            it.mimeType == FOLDER_MIME_TYPE && it.name.equals(TRANSCRIPTION_FOLDER_NAME, ignoreCase = true)
        }
        if (directTranscriptionFolder != null) {
            val transQuery = "'${escapeQueryLiteral(directTranscriptionFolder.id)}' in parents and trashed = false"
            when (val res = listFilesByQuery(token, transQuery)) {
                is InternalListOutcome.Success -> {
                    directTranscripts += res.files.filter(::isTranscriptRemoteFile)
                }

                is InternalListOutcome.Failure -> return DriveListResult.Failure(res.error)
            }
        }
        if (directAudios.isNotEmpty() || directTranscripts.isNotEmpty()) {
            courses += DriveCourseFiles(
                courseName = "",
                audioFiles = directAudios,
                transcriptFiles = directTranscripts,
            )
        }

        // 各コースフォルダ (<course>/) を走査する
        val courseFolders = rootChildren.filter {
            it.mimeType == FOLDER_MIME_TYPE && !it.name.equals(TRANSCRIPTION_FOLDER_NAME, ignoreCase = true)
        }
        for (courseFolder in courseFolders) {
            currentCoroutineContext().ensureActive()
            val courseQuery = "'${escapeQueryLiteral(courseFolder.id)}' in parents and trashed = false"
            val courseChildren = when (val res = listFilesByQuery(token, courseQuery)) {
                is InternalListOutcome.Success -> res.files
                is InternalListOutcome.Failure -> return DriveListResult.Failure(res.error)
            }

            val audioFiles = courseChildren.filter(::isAudioRemoteFile)
            val transcriptFiles = mutableListOf<DriveRemoteFile>()
            transcriptFiles += courseChildren.filter(::isTranscriptRemoteFile)

            val transcriptionFolder = courseChildren.firstOrNull {
                it.mimeType == FOLDER_MIME_TYPE && it.name.equals(TRANSCRIPTION_FOLDER_NAME, ignoreCase = true)
            }
            if (transcriptionFolder != null) {
                val transQuery = "'${escapeQueryLiteral(transcriptionFolder.id)}' in parents and trashed = false"
                when (val res = listFilesByQuery(token, transQuery)) {
                    is InternalListOutcome.Success -> {
                        transcriptFiles += res.files.filter(::isTranscriptRemoteFile)
                    }

                    is InternalListOutcome.Failure -> return DriveListResult.Failure(res.error)
                }
            }

            courses += DriveCourseFiles(
                courseName = courseFolder.name,
                audioFiles = audioFiles,
                transcriptFiles = transcriptFiles,
            )
        }

        return DriveListResult.Success(courses)
    }

    override suspend fun downloadToFile(
        fileId: String,
        destFile: File,
        maxSizeBytes: Long,
        onProgress: ((Long) -> Unit)?,
    ): DriveDownloadResult {
        if (fileId.isBlank()) {
            return DriveDownloadResult.Failure(DriveError.INVALID_RESPONSE)
        }
        val token = when (val auth = authProvider.acquireAccessToken()) {
            is DriveAuthResult.Authorized -> auth.accessToken
            DriveAuthResult.NotAuthorized -> return DriveDownloadResult.Failure(DriveError.AUTH)
        }

        val url = "$apiBaseUrl/files/${encodeUrlComponent(fileId)}?alt=media"
        var connection: HttpURLConnection? = null
        return try {
            connection = openAuthenticatedConnection(url, token, method = "GET")
            val status = connection.responseCode
            if (status !in 200..299) {
                return DriveDownloadResult.Failure(mapHttpStatusToError(status))
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > maxSizeBytes) {
                return DriveDownloadResult.Failure(DriveError.TOO_LARGE)
            }

            destFile.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val buffer = ByteArray(STREAM_BUFFER_BYTES)

            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        totalBytes += read
                        if (totalBytes > maxSizeBytes) {
                            output.close()
                            destFile.delete()
                            return DriveDownloadResult.Failure(DriveError.TOO_LARGE)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress?.invoke(totalBytes)
                    }
                    output.fd.sync()
                }
            }

            if (contentLength > 0L && totalBytes < contentLength) {
                destFile.delete()
                return DriveDownloadResult.Failure(DriveError.NETWORK)
            }
            if (totalBytes <= 0L) {
                destFile.delete()
                return DriveDownloadResult.Failure(DriveError.NETWORK)
            }

            val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
            DriveDownloadResult.Success(
                bytesWritten = totalBytes,
                sha256Hex = sha256Hex,
            )
        } catch (ce: CancellationException) {
            destFile.delete()
            throw ce
        } catch (_: IOException) {
            destFile.delete()
            DriveDownloadResult.Failure(DriveError.NETWORK)
        } catch (_: SecurityException) {
            destFile.delete()
            DriveDownloadResult.Failure(DriveError.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun downloadBytes(
        fileId: String,
        maxSizeBytes: Long,
    ): DriveBytesDownloadResult {
        if (fileId.isBlank()) {
            return DriveBytesDownloadResult.Failure(DriveError.INVALID_RESPONSE)
        }
        val token = when (val auth = authProvider.acquireAccessToken()) {
            is DriveAuthResult.Authorized -> auth.accessToken
            DriveAuthResult.NotAuthorized -> return DriveBytesDownloadResult.Failure(DriveError.AUTH)
        }

        val url = "$apiBaseUrl/files/${encodeUrlComponent(fileId)}?alt=media"
        var connection: HttpURLConnection? = null
        return try {
            connection = openAuthenticatedConnection(url, token, method = "GET")
            val status = connection.responseCode
            if (status !in 200..299) {
                return DriveBytesDownloadResult.Failure(mapHttpStatusToError(status))
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > maxSizeBytes) {
                return DriveBytesDownloadResult.Failure(DriveError.TOO_LARGE)
            }

            val out = ByteArrayOutputStream()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var totalBytes = 0L

            connection.inputStream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    totalBytes += read
                    if (totalBytes > maxSizeBytes) {
                        return DriveBytesDownloadResult.Failure(DriveError.TOO_LARGE)
                    }
                    out.write(buffer, 0, read)
                }
            }

            if (contentLength > 0L && totalBytes < contentLength) {
                return DriveBytesDownloadResult.Failure(DriveError.NETWORK)
            }

            DriveBytesDownloadResult.Success(out.toByteArray())
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            DriveBytesDownloadResult.Failure(DriveError.NETWORK)
        } catch (_: SecurityException) {
            DriveBytesDownloadResult.Failure(DriveError.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun uploadTranscriptJson(
        courseName: String,
        fileName: String,
        jsonBytes: ByteArray,
    ): DriveUploadResult {
        if (fileName.isBlank()) {
            return DriveUploadResult.Failure(DriveError.INVALID_RESPONSE)
        }
        if (jsonBytes.size.toLong() > DriveSyncClient.MAX_TRANSCRIPT_SIZE_BYTES) {
            return DriveUploadResult.Failure(DriveError.TOO_LARGE)
        }
        val token = when (val auth = authProvider.acquireAccessToken()) {
            is DriveAuthResult.Authorized -> auth.accessToken
            DriveAuthResult.NotAuthorized -> return DriveUploadResult.Failure(DriveError.AUTH)
        }

        // 1. ChunkFlow ルートフォルダを探す
        val rootQuery = "name = '$ROOT_FOLDER_NAME' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val rootFolder = when (val res = listFilesByQuery(token, rootQuery)) {
            is InternalListOutcome.Success -> res.files.firstOrNull()
            is InternalListOutcome.Failure -> return DriveUploadResult.Failure(res.error)
        } ?: return DriveUploadResult.Failure(DriveError.FOLDER_NOT_FOUND)

        // 2. コースフォルダ (<courseName>/) を探す（なければ作成する）
        val trimmedCourse = courseName.trim()
        val courseFolderId = if (trimmedCourse.isEmpty()) {
            rootFolder.id
        } else {
            when (val res = findOrCreateSubfolder(token, rootFolder.id, trimmedCourse)) {
                is InternalFolderOutcome.Success -> res.folderId
                is InternalFolderOutcome.Failure -> return DriveUploadResult.Failure(res.error)
            }
        }

        // 3. transcription/ サブフォルダを探す（なければ作成する）
        val transcriptionFolderId = when (
            val res = findOrCreateSubfolder(token, courseFolderId, TRANSCRIPTION_FOLDER_NAME)
        ) {
            is InternalFolderOutcome.Success -> res.folderId
            is InternalFolderOutcome.Failure -> return DriveUploadResult.Failure(res.error)
        }

        // 4. 既存の同名ファイルが transcription/ 内にあるか調べる
        val safeFileName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val existingQuery =
            "'${escapeQueryLiteral(transcriptionFolderId)}' in parents and " +
                "name = '${escapeQueryLiteral(safeFileName)}' and trashed = false"
        val existingFile = when (val res = listFilesByQuery(token, existingQuery)) {
            is InternalListOutcome.Success -> res.files.firstOrNull()
            is InternalListOutcome.Failure -> return DriveUploadResult.Failure(res.error)
        }

        return if (existingFile != null) {
            updateExistingTranscriptFile(token, existingFile.id, jsonBytes)
        } else {
            createNewTranscriptFile(token, transcriptionFolderId, safeFileName, jsonBytes)
        }
    }

    private suspend fun findOrCreateSubfolder(
        token: String,
        parentFolderId: String,
        folderName: String,
    ): InternalFolderOutcome {
        val query =
            "'${escapeQueryLiteral(parentFolderId)}' in parents and " +
                "name = '${escapeQueryLiteral(folderName)}' and " +
                "mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val existing = when (val res = listFilesByQuery(token, query)) {
            is InternalListOutcome.Success -> res.files.firstOrNull()
            is InternalListOutcome.Failure -> return InternalFolderOutcome.Failure(res.error)
        }
        if (existing != null) {
            return InternalFolderOutcome.Success(existing.id)
        }

        val metadataJson = buildJsonObject {
            put("name", folderName)
            put("mimeType", FOLDER_MIME_TYPE)
            put("parents", JsonArray(listOf(JsonPrimitive(parentFolderId))))
        }.toString().toByteArray(Charsets.UTF_8)

        val url = "$apiBaseUrl/files?fields=$FILE_FIELDS"
        var connection: HttpURLConnection? = null
        return try {
            connection = openAuthenticatedConnection(url, token, method = "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setFixedLengthStreamingMode(metadataJson.size)
            }
            connection.outputStream.use { it.write(metadataJson) }
            val status = connection.responseCode
            if (status !in 200..299) {
                return InternalFolderOutcome.Failure(mapHttpStatusToError(status))
            }
            val body = readBoundedString(connection.inputStream, MAX_API_JSON_BYTES)
                ?: return InternalFolderOutcome.Failure(DriveError.INVALID_RESPONSE)
            val created = parseSingleFile(body)
                ?: return InternalFolderOutcome.Failure(DriveError.INVALID_RESPONSE)
            InternalFolderOutcome.Success(created.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            InternalFolderOutcome.Failure(DriveError.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun updateExistingTranscriptFile(
        token: String,
        fileId: String,
        jsonBytes: ByteArray,
    ): DriveUploadResult {
        val url = "$uploadBaseUrl/files/${encodeUrlComponent(fileId)}?uploadType=media&fields=$FILE_FIELDS"
        var connection: HttpURLConnection? = null
        return try {
            currentCoroutineContext().ensureActive()
            connection = openAuthenticatedConnection(url, token, method = "POST").apply {
                doOutput = true
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setFixedLengthStreamingMode(jsonBytes.size)
            }
            connection.outputStream.use { it.write(jsonBytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                return DriveUploadResult.Failure(mapHttpStatusToError(status))
            }
            val body = readBoundedString(connection.inputStream, MAX_API_JSON_BYTES)
                ?: return DriveUploadResult.Failure(DriveError.INVALID_RESPONSE)
            val updated = parseSingleFile(body)
                ?: return DriveUploadResult.Failure(DriveError.INVALID_RESPONSE)
            DriveUploadResult.Success(updated)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            DriveUploadResult.Failure(DriveError.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun createNewTranscriptFile(
        token: String,
        parentFolderId: String,
        fileName: String,
        jsonBytes: ByteArray,
    ): DriveUploadResult {
        val boundary = "chunkflow_boundary_${System.nanoTime()}"
        val metadataJson = buildJsonObject {
            put("name", fileName)
            put("mimeType", "application/json")
            put("parents", JsonArray(listOf(JsonPrimitive(parentFolderId))))
        }.toString()

        val multipartBytes = buildMultipartRelatedBody(
            boundary = boundary,
            metadataJson = metadataJson,
            mediaContentType = "application/json; charset=UTF-8",
            mediaBytes = jsonBytes,
        )

        val url = "$uploadBaseUrl/files?uploadType=multipart&fields=$FILE_FIELDS"
        var connection: HttpURLConnection? = null
        return try {
            currentCoroutineContext().ensureActive()
            connection = openAuthenticatedConnection(url, token, method = "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                setFixedLengthStreamingMode(multipartBytes.size)
            }
            connection.outputStream.use { it.write(multipartBytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                return DriveUploadResult.Failure(mapHttpStatusToError(status))
            }
            val body = readBoundedString(connection.inputStream, MAX_API_JSON_BYTES)
                ?: return DriveUploadResult.Failure(DriveError.INVALID_RESPONSE)
            val created = parseSingleFile(body)
                ?: return DriveUploadResult.Failure(DriveError.INVALID_RESPONSE)
            DriveUploadResult.Success(created)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            DriveUploadResult.Failure(DriveError.NETWORK)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun listFilesByQuery(
        token: String,
        query: String,
    ): InternalListOutcome {
        val collected = mutableListOf<DriveRemoteFile>()
        var pageToken: String? = null
        var pageCount = 0

        while (pageCount < MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            pageCount += 1
            val pageParam = if (pageToken != null) {
                "&pageToken=${encodeUrlComponent(pageToken)}"
            } else {
                ""
            }
            val url =
                "$apiBaseUrl/files?q=${encodeUrlComponent(query)}" +
                    "&fields=${encodeUrlComponent("nextPageToken,$FILE_LIST_FIELDS")}" +
                    "&pageSize=$PAGE_SIZE" +
                    "&spaces=drive" +
                    pageParam

            var connection: HttpURLConnection? = null
            try {
                connection = openAuthenticatedConnection(url, token, method = "GET")
                val status = connection.responseCode
                if (status !in 200..299) {
                    return InternalListOutcome.Failure(mapHttpStatusToError(status))
                }
                val body = readBoundedString(connection.inputStream, MAX_API_JSON_BYTES)
                    ?: return InternalListOutcome.Failure(DriveError.INVALID_RESPONSE)
                val parsed = try {
                    json.decodeFromString(RawFileListResponse.serializer(), body)
                } catch (_: SerializationException) {
                    return InternalListOutcome.Failure(DriveError.INVALID_RESPONSE)
                } catch (_: IllegalArgumentException) {
                    return InternalListOutcome.Failure(DriveError.INVALID_RESPONSE)
                }

                val files = parsed.files ?: return InternalListOutcome.Failure(DriveError.INVALID_RESPONSE)
                for (raw in files) {
                    val mapped = raw.toRemoteFile()
                        ?: return InternalListOutcome.Failure(DriveError.INVALID_RESPONSE)
                    collected += mapped
                }

                val next = parsed.nextPageToken?.takeIf { it.isNotBlank() }
                if (next == null) {
                    break
                }
                pageToken = next
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: IOException) {
                return InternalListOutcome.Failure(DriveError.NETWORK)
            } catch (_: SecurityException) {
                return InternalListOutcome.Failure(DriveError.NETWORK)
            } finally {
                connection?.disconnect()
            }
        }

        return InternalListOutcome.Success(collected)
    }

    private fun openAuthenticatedConnection(
        urlString: String,
        accessToken: String,
        method: String,
    ): HttpURLConnection {
        val uri = URI.create(urlString)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val isAllowedLoopbackHttp = scheme == "http" && (host == "127.0.0.1" || host == "localhost")
        if (scheme != "https" && !isAllowedLoopbackHttp) {
            throw IOException("Disallowed transport scheme")
        }
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return connection
    }

    private suspend fun readBoundedString(inputStream: InputStream, maxBytes: Int): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        inputStream.use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) {
                    return null
                }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun parseSingleFile(body: String): DriveRemoteFile? {
        val raw = try {
            json.decodeFromString(RawDriveFile.serializer(), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return raw.toRemoteFile()
    }

    private fun RawDriveFile.toRemoteFile(): DriveRemoteFile? {
        val validId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val validName = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val validMime = mimeType?.trim()?.takeIf { it.isNotEmpty() } ?: APPLICATION_OCTET_STREAM
        val parsedSize = size?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val parsedModifiedMs = parseModifiedTimeEpochMs(modifiedTime)
        val normalizedSha = sha256Checksum?.trim()?.lowercase()?.takeIf { it.length == 64 }
        return DriveRemoteFile(
            id = validId,
            name = validName,
            mimeType = validMime,
            sizeBytes = parsedSize,
            modifiedTimeEpochMs = parsedModifiedMs,
            sha256Checksum = normalizedSha,
        )
    }

    @Serializable
    private data class RawFileListResponse(
        @SerialName("nextPageToken")
        val nextPageToken: String? = null,
        @SerialName("files")
        val files: List<RawDriveFile>? = null,
    )

    @Serializable
    private data class RawDriveFile(
        @SerialName("id")
        val id: String? = null,
        @SerialName("name")
        val name: String? = null,
        @SerialName("mimeType")
        val mimeType: String? = null,
        @SerialName("size")
        val size: String? = null,
        @SerialName("modifiedTime")
        val modifiedTime: String? = null,
        @SerialName("sha256Checksum")
        val sha256Checksum: String? = null,
    )

    private sealed interface InternalListOutcome {
        data class Success(val files: List<DriveRemoteFile>) : InternalListOutcome
        data class Failure(val error: DriveError) : InternalListOutcome
    }

    private sealed interface InternalFolderOutcome {
        data class Success(val folderId: String) : InternalFolderOutcome
        data class Failure(val error: DriveError) : InternalFolderOutcome
    }

    companion object {
        /** Google Drive REST API v3 のエンドポイント。 */
        const val DEFAULT_API_BASE_URL: String = "https://www.googleapis.com/drive/v3"

        /** Google Drive Upload API v3 のエンドポイント。 */
        const val DEFAULT_UPLOAD_BASE_URL: String = "https://www.googleapis.com/upload/drive/v3"

        /** Google Drive 上のルートフォルダ名（§7.1）。 */
        const val ROOT_FOLDER_NAME: String = "ChunkFlow"

        /** コースフォルダ内の文字起こしサブフォルダ名（§7.1）。 */
        const val TRANSCRIPTION_FOLDER_NAME: String = "transcription"

        /** Google Drive のフォルダ MIME タイプ。 */
        const val FOLDER_MIME_TYPE: String = "application/vnd.google-apps.folder"

        private const val APPLICATION_OCTET_STREAM: String = "application/octet-stream"
        private const val FILE_FIELDS: String = "id,name,mimeType,size,modifiedTime,sha256Checksum"
        private const val FILE_LIST_FIELDS: String = "files($FILE_FIELDS)"
        private const val PAGE_SIZE: Int = 100
        private const val MAX_PAGES: Int = 50
        private const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS: Int = 30_000
        private const val STREAM_BUFFER_BYTES: Int = 64 * 1024
        private const val MAX_API_JSON_BYTES: Int = 5 * 1024 * 1024

        private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3",
            "m4a",
            "mp4",
            "ogg",
            "opus",
            "flac",
            "wav",
            "aac",
        )

        private val json = Json {
            ignoreUnknownKeys = true
        }

        internal fun isTranscriptRemoteFile(file: DriveRemoteFile): Boolean {
            if (file.mimeType == FOLDER_MIME_TYPE) return false
            val lower = file.name.lowercase()
            return lower.endsWith(".chunkflow.json") ||
                lower.endsWith(".srt") ||
                lower.endsWith(".vtt") ||
                lower.endsWith(".lrc")
        }

        internal fun isAudioRemoteFile(file: DriveRemoteFile): Boolean {
            if (file.mimeType == FOLDER_MIME_TYPE) return false
            if (isTranscriptRemoteFile(file)) return false
            val lowerName = file.name.lowercase()
            if (lowerName.endsWith(".json")) return false
            if (file.mimeType.lowercase().startsWith("audio/")) return true
            val ext = lowerName.substringAfterLast('.', "")
            return ext in SUPPORTED_AUDIO_EXTENSIONS
        }

        internal fun parseModifiedTimeEpochMs(modifiedTime: String?): Long {
            if (modifiedTime.isNullOrBlank()) return 0L
            return try {
                OffsetDateTime.parse(modifiedTime).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                0L
            } catch (_: ArithmeticException) {
                0L
            }
        }

        internal fun escapeQueryLiteral(value: String): String = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    else -> append(ch)
                }
            }
        }

        private fun encodeUrlComponent(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun mapHttpStatusToError(status: Int): DriveError = when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED,
            HttpURLConnection.HTTP_FORBIDDEN,
            -> DriveError.AUTH

            HttpURLConnection.HTTP_NOT_FOUND -> DriveError.FOLDER_NOT_FOUND

            else -> DriveError.NETWORK
        }

        private fun buildMultipartRelatedBody(
            boundary: String,
            metadataJson: String,
            mediaContentType: String,
            mediaBytes: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val headerPart = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadataJson).append("\r\n")
                append("--").append(boundary).append("\r\n")
                append("Content-Type: ").append(mediaContentType).append("\r\n\r\n")
            }
            val footerPart = "\r\n--$boundary--\r\n"
            out.write(headerPart.toByteArray(Charsets.UTF_8))
            out.write(mediaBytes)
            out.write(footerPart.toByteArray(Charsets.UTF_8))
            return out.toByteArray()
        }
    }
}
