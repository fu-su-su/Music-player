package com.musicplayer.app.util

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.musicplayer.app.data.Song
import com.musicplayer.app.util.LyricParser
import java.io.File

object AudioUtils {

    private const val TAG = "AudioUtils"

    fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    fun parseAudioFile(context: Context, uri: Uri): Song? {
        val retriever = MediaMetadataRetriever()
        return try {
            // 直接用 Uri 读取元数据，避免 SAF Uri 无法转换为真实路径的问题
            retriever.setDataSource(context, uri)

            val displayName = getDisplayNameFromUri(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: displayName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "未知艺术家"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            // 将音频复制到 App 私有目录，MediaPlayer 可直接通过路径播放
            val destPath = copyFileUriToInternalStorage(context, uri, "music", "mp3")
                ?: return null

            Song(
                path = destPath,
                title = title,
                artist = artist,
                duration = duration
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /**
     * 将 SAF Uri 指向的文件复制到 App 私有目录 getExternalFilesDir/<subDir>/ 下。
     * 复制后的文件路径可直接被 MediaPlayer / BitmapFactory 使用，避免 Uri 权限/生命周期问题。
     *
     * @param context 上下文
     * @param uri 文件 Uri（SAF 返回的 content Uri）
     * @param subDir 子目录名（如 "music"、"covers"、"lyrics"）
     * @param defaultExtension 当 Uri 无法获取扩展名时使用的默认扩展名
     * @return 复制后的文件绝对路径，失败返回 null
     */
    fun copyFileUriToInternalStorage(
        context: Context,
        uri: Uri,
        subDir: String,
        defaultExtension: String = "dat"
    ): String? {
        return try {
            val targetDir = File(context.getExternalFilesDir(null), subDir).apply {
                if (!exists()) mkdirs()
            }

            val displayName = getDisplayNameFromUri(context, uri)
            val extension = displayName.substringAfterLast('.', missingDelimiterValue = "")
                .ifEmpty { defaultExtension }
            val safeName = displayName.substringBeforeLast('.')
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifEmpty { "file_${System.currentTimeMillis()}" }
            var destFile = File(targetDir, "$safeName.$extension")
            var counter = 1
            while (destFile.exists()) {
                destFile = File(targetDir, "${safeName}_$counter.$extension")
                counter++
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 删除指定路径的文件（用于删除歌曲时清理关联的音频/歌词/封面文件）。
     */
    fun deleteFile(path: String?) {
        if (path == null) return
        try {
            File(path).takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ====================== 歌词自动匹配 ======================

    /**
     * 递归遍历 SAF 目录树，收集所有文件（含子目录）。
     * maxDepth 限制递归深度，防止目录树过大导致扫描缓慢。
     */
    private fun collectFilesRecursive(
        dir: DocumentFile,
        depth: Int = 0,
        maxDepth: Int = 3,
        out: MutableList<DocumentFile>
    ) {
        if (depth > maxDepth) return
        try {
            for (item in dir.listFiles()) {
                if (item.isDirectory) {
                    collectFilesRecursive(item, depth + 1, maxDepth, out)
                } else {
                    out.add(item)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "遍历目录失败: ${e.message}")
        }
    }

    /**
     * 歌词匹配候选项：从 SAF 目录扫描得到。
     */
    data class LyricMatchCandidate(
        val uri: Uri,
        val title: String,
        val artist: String?
    )

    /**
     * 扫描 SAF 选中的歌词目录，提取所有 .lrc 文件及其元数据。
     * 元数据优先用 LRC 文件内的 [ti:] [ar:]，否则用文件名作为标题。
     */
    fun scanLyricFolder(context: Context, treeUri: Uri): List<LyricMatchCandidate> {
        val candidates = mutableListOf<LyricMatchCandidate>()
        val treeDocument = DocumentFile.fromTreeUri(context, treeUri) ?: return candidates

        // 递归收集目录及子目录中的所有文件
        val allFiles = mutableListOf<DocumentFile>()
        collectFilesRecursive(treeDocument, out = allFiles)
        Log.i(TAG, "歌词目录扫描：共 ${allFiles.size} 个文件")

        for (file in allFiles) {
            try {
                if (!file.isFile) continue
                val name = file.name ?: continue
                if (!name.endsWith(".lrc", ignoreCase = true)) continue

                val content = context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: continue

                val meta = LyricParser.extractLyricMetadata(content)
                val title = meta.title
                    ?: name.substringBeforeLast('.').replace(Regex("[\\s_\\-]+"), " ").trim()
                val artist = meta.artist
                candidates.add(LyricMatchCandidate(file.uri, title, artist))
            } catch (e: Exception) {
                Log.w(TAG, "跳过歌词文件: ${e.message}")
            }
        }
        Log.i(TAG, "歌词扫描完成，共 ${candidates.size} 个候选")
        return candidates
    }

    /**
     * 将候选歌词匹配到歌曲。匹配优先级：
     *  1. 标题归一化完全相等 + artist 相等
     *  2. 标题归一化完全相等
     *  3. 标题包含关系（处理"歌名 - 歌手"等情况）
     *
     * 用 HashMap 做 O(1) 标题索引，避免 O(n²) 复杂度，支持大批量匹配。
     */
    fun matchLyricsToSongs(
        candidates: List<LyricMatchCandidate>,
        songs: List<Song>
    ): Map<Long, Uri> {
        val result = mutableMapOf<Long, Uri>()

        // 构建索引：normalizedTitle -> List<candidate>
        val index = HashMap<String, MutableList<LyricMatchCandidate>>()
        for (c in candidates) {
            val key = normalizeTitle(c.title)
            index.getOrPut(key) { mutableListOf() }.add(c)
        }

        for (song in songs) {
            val songKey = normalizeTitle(song.title)

            // 阶段1：精确标题匹配，优先 artist 也匹配的
            val exactMatches = index[songKey]
            if (exactMatches != null && exactMatches.isNotEmpty()) {
                val withArtist = exactMatches.find { c ->
                    c.artist != null && song.artist != null &&
                        normalizeTitle(c.artist) == normalizeTitle(song.artist)
                } ?: exactMatches.first()
                result[song.id] = withArtist.uri
                continue
            }

            // 阶段2：包含匹配（处理"歌名 - 歌手"或带版本号等情况）
            var found: Uri? = null
            for ((key, list) in index) {
                if (key.length < 2) continue  // 跳过过短的关键字，避免误匹配
                if (key.contains(songKey) || songKey.contains(key)) {
                    val withArtist = list.find { c ->
                        c.artist != null && song.artist != null &&
                            normalizeTitle(c.artist) == normalizeTitle(song.artist)
                    } ?: list.first()
                    found = withArtist.uri
                    break
                }
            }
            if (found != null) {
                result[song.id] = found
            }
        }
        return result
    }

    /**
     * 标题归一化：小写、去除空格/连字符/下划线/标点，便于跨源匹配。
     */
    private fun normalizeTitle(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\s\\-_·+.,!?\"'()\\[\\]【】（）]"), "")
    }

    // ====================== 封面自动匹配 ======================

    /**
     * 图片匹配候选项：从 SAF 目录扫描得到，文件名（去扩展名）作为匹配标题。
     */
    data class ImageMatchCandidate(
        val uri: Uri,
        val title: String
    )

    /**
     * 扫描 SAF 选中的图片目录，提取所有图片文件（jpg/jpeg/png/webp）。
     * 图片名（去扩展名）作为匹配标题，如"告白气球.jpg" -> "告白气球"。
     */
    fun scanImageFolder(context: Context, treeUri: Uri): List<ImageMatchCandidate> {
        val candidates = mutableListOf<ImageMatchCandidate>()
        val treeDocument = DocumentFile.fromTreeUri(context, treeUri) ?: return candidates

        val imageExtensions = arrayOf(".jpg", ".jpeg", ".png", ".webp")

        // 递归收集目录及子目录中的所有文件（用户可能选 Music 根目录，图片在子文件夹里）
        val allFiles = mutableListOf<DocumentFile>()
        collectFilesRecursive(treeDocument, out = allFiles)
        Log.i(TAG, "图片目录扫描：共 ${allFiles.size} 个文件")

        for (file in allFiles) {
            try {
                if (!file.isFile) continue
                val name = file.name ?: continue
                if (imageExtensions.none { name.endsWith(it, ignoreCase = true) }) continue

                val title = name.substringBeforeLast('.')
                    .replace(Regex("[\\s_\\-]+"), " ")
                    .trim()
                if (title.isEmpty()) continue
                candidates.add(ImageMatchCandidate(file.uri, title))
            } catch (e: Exception) {
                Log.w(TAG, "跳过图片文件: ${e.message}")
            }
        }
        Log.i(TAG, "图片扫描完成，共 ${candidates.size} 个候选")
        return candidates
    }

    /**
     * 将候选图片匹配到歌曲（覆盖模式：对所有歌曲匹配）。
     * 匹配优先级：
     *  1. 标题归一化完全相等
     *  2. 标题包含关系（模糊匹配）
     *  3. 无名字匹配的图片随机分配给剩余歌曲
     *
     * 用 HashMap 做 O(1) 标题索引，支持大批量匹配。
     */
    fun matchImagesToSongs(
        candidates: List<ImageMatchCandidate>,
        songs: List<Song>
    ): Map<Long, Uri> {
        val result = mutableMapOf<Long, Uri>()

        // 构建索引：normalizedTitle -> List<candidate>
        val index = HashMap<String, MutableList<ImageMatchCandidate>>()
        for (c in candidates) {
            val key = normalizeTitle(c.title)
            index.getOrPut(key) { mutableListOf() }.add(c)
        }

        // 记录已被"名字匹配"占用的图片，随机分配时不再复用
        val usedImages = HashSet<Uri>()
        val unmatchedSongs = mutableListOf<Song>()

        for (song in songs) {
            val songKey = normalizeTitle(song.title)

            // 阶段1：精确标题匹配
            val exactMatches = index[songKey]
            if (exactMatches != null && exactMatches.isNotEmpty()) {
                result[song.id] = exactMatches.first().uri
                usedImages.add(exactMatches.first().uri)
                continue
            }

            // 阶段2：包含匹配（处理"歌名 - 歌手"或带后缀等情况）
            var found: ImageMatchCandidate? = null
            for ((key, list) in index) {
                if (key.length < 2) continue  // 跳过过短的关键字，避免误匹配
                if (key.contains(songKey) || songKey.contains(key)) {
                    found = list.first()
                    break
                }
            }
            if (found != null) {
                result[song.id] = found.uri
                usedImages.add(found.uri)
            } else {
                unmatchedSongs.add(song)
            }
        }

        // 阶段3：没有名字匹配的图片随机分配给剩余歌曲
        if (unmatchedSongs.isNotEmpty()) {
            val freeImages = candidates.filter { it.uri !in usedImages }
            if (freeImages.isNotEmpty()) {
                val shuffledSongs = unmatchedSongs.shuffled()
                val shuffledImages = freeImages.shuffled()
                val pairCount = minOf(shuffledSongs.size, shuffledImages.size)
                for (i in 0 until pairCount) {
                    result[shuffledSongs[i].id] = shuffledImages[i].uri
                }
            }
        }
        return result
    }

    private fun getDisplayNameFromUri(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return uri.lastPathSegment ?: "audio_${System.currentTimeMillis()}"
    }

    fun getEmbeddedCover(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // ignore
        }

        if ("content".equals(uri.scheme, ignoreCase = true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val projection = arrayOf(MediaStore.Audio.Media.DATA)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                            return cursor.getString(columnIndex)
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            try {
                val id = ContentUris.parseId(uri)
                val projection = arrayOf(MediaStore.Audio.Media.DATA)
                val selection = "${MediaStore.Audio.Media._ID} = ?"
                val selectionArgs = arrayOf(id.toString())
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                        return cursor.getString(columnIndex)
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        return uri.path?.let { path ->
            if (path.startsWith("/document/raw:")) {
                path.removePrefix("/document/raw:")
            } else if (path.startsWith("/document/primary:")) {
                val storageDir = android.os.Environment.getExternalStorageDirectory().absolutePath
                storageDir + "/" + path.removePrefix("/document/primary:")
            } else {
                path
            }
        }
    }

    fun getPathFromDocumentUri(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // ignore
        }

        val path = uri.path ?: return null
        return when {
            path.startsWith("/document/raw:") -> path.removePrefix("/document/raw:")
            path.startsWith("/document/primary:") -> {
                val storageDir = android.os.Environment.getExternalStorageDirectory().absolutePath
                storageDir + "/" + path.removePrefix("/document/primary:")
            }
            else -> {
                try {
                    val docId = android.provider.DocumentsContract.getDocumentId(uri)
                    if (docId.contains(":")) {
                        val split = docId.split(":")
                        val type = split[0]
                        if ("primary".equals(type, ignoreCase = true)) {
                            android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + split[1]
                        } else {
                            null
                        }
                    } else null
                } catch (e: Exception) {
                    path
                }
            }
        }
    }

    /**
     * 扫描设备中所有音频文件（通过MediaStore查询）。
     * 用于：用户从电脑复制音频到手机后，点击扫描即可发现并导入所有新音频。
     *
     * @param context 上下文
     * @return 扫描到的歌曲列表
     */
    fun scanDeviceAudioFiles(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val retriever = MediaMetadataRetriever()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        )

        // 只查询 IS_MUSIC=1 的音频文件，过滤掉短音频通知音
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    try {
                        val path = cursor.getString(pathColumn) ?: continue
                        val file = File(path)
                        // 跳过不存在的文件
                        if (!file.exists() || !file.canRead()) continue

                        val title = cursor.getString(titleColumn)
                            ?: file.nameWithoutExtension
                        val artist = cursor.getString(artistColumn)
                            ?: "未知艺术家"
                        val duration = cursor.getLong(durationColumn)

                        // 过滤掉过短的音频（<3秒，可能是通知音/铃声）
                        if (duration < 3000) continue

                        songs.add(
                            Song(
                                path = path,
                                title = title,
                                artist = artist,
                                duration = duration
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "跳过某音频文件: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描设备音频失败", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }

        Log.i(TAG, "设备扫描完成，共发现 ${songs.size} 个音频文件")
        return songs
    }
}
