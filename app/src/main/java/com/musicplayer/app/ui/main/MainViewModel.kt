package com.musicplayer.app.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Song
import com.musicplayer.app.util.AudioUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.File

enum class SortType {
    NAME, ARTIST, ADD_TIME
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    private val _sortType = MutableStateFlow(SortType.NAME)
    val sortType: MutableStateFlow<SortType> get() = _sortType

    /** 当前搜索关键字（公开只读，供 UI 判断空结果文案等）。 */
    val searchKeyword = MutableStateFlow("")

    val songs: LiveData<List<Song>> = searchKeyword
        .flatMapLatest { keyword ->
            if (keyword.isBlank()) {
                _sortType.flatMapLatest { sortType ->
                    when (sortType) {
                        SortType.NAME -> repository.getSongsSortedByName()
                        SortType.ARTIST -> repository.getSongsSortedByArtist()
                        SortType.ADD_TIME -> repository.getSongsSortedByAddTime()
                    }
                }
            } else {
                repository.searchSongs(keyword.trim())
            }
        }.asLiveData()

    private val _importResult = MutableLiveData<Pair<Int, Int>>() // (成功数, 总数)
    val importResult: LiveData<Pair<Int, Int>> get() = _importResult

    private val _scanResult = MutableLiveData<Pair<Int, Int>>() // (成功导入数, 扫描总数)
    val scanResult: LiveData<Pair<Int, Int>> get() = _scanResult

    private val _matchResult = MutableLiveData<Pair<Int, Int>>() // (成功匹配数, 待匹配总数)
    val matchResult: LiveData<Pair<Int, Int>> get() = _matchResult

    private val _coverMatchResult = MutableLiveData<Triple<Int, Int, Int>>() // (成功匹配数, 歌曲总数, 扫描图片数)
    val coverMatchResult: LiveData<Triple<Int, Int, Int>> get() = _coverMatchResult

    fun setSortType(type: SortType) {
        _sortType.value = type
    }

    fun setSearchKeyword(keyword: String) {
        searchKeyword.value = keyword
    }

    /** 设置/清除歌曲的自定义播放页背景图（path 为 null 时清除）。 */
    fun setCustomBackground(songId: Long, path: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateCustomBackgroundPath(songId, path)
        }
    }

    /**
     * 从 SAF Uri 选取的图片设置自定义播放页背景：
     * 复制到私有目录 backgrounds/ 后保存文件路径到 DB，回调返回最终路径（失败为 null）。
     */
    fun setCustomBackgroundFromUri(songId: Long, uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val dir = File(app.getExternalFilesDir(null), "backgrounds")
                    dir.mkdirs()
                    val file = File(dir, "bg_${songId}_${System.currentTimeMillis()}.jpg")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext null
                    file.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            if (savedPath != null) {
                repository.updateCustomBackgroundPath(songId, savedPath)
            }
            withContext(Dispatchers.Main) { onResult(savedPath) }
        }
    }

    fun importAudioFiles(uris: List<Uri>) {
        viewModelScope.launch {
            var successCount = 0
            val totalCount = uris.size

            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val song = AudioUtils.parseAudioFile(getApplication(), uri)
                        if (song != null) {
                            // 用 title+artist+duration 去重，避免同名文件复制后路径不同导致重复
                            val exists = repository.existsByTitleArtistDuration(
                                song.title, song.artist, song.duration
                            )
                            if (!exists) {
                                repository.insertSong(song)
                                successCount++
                            } else {
                                // 已存在则删除刚复制的冗余文件
                                AudioUtils.deleteFile(song.path)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            _importResult.postValue(successCount to totalCount)
        }
    }

    /**
     * 扫描设备中所有音频文件并导入数据库（已存在的按 title+artist+duration 去重）。
     * 适用于：用户将电脑上的音频文件拷贝到手机存储后，点击"扫描设备音乐"批量导入。
     */
    fun scanDeviceAudioFiles() {
        viewModelScope.launch {
            val allSongs = withContext(Dispatchers.IO) {
                AudioUtils.scanDeviceAudioFiles(getApplication())
            }
            var importedCount = 0
            withContext(Dispatchers.IO) {
                for (song in allSongs) {
                    try {
                        if (!repository.existsByTitleArtistDuration(
                                song.title, song.artist, song.duration
                            )
                        ) {
                            repository.insertSong(song)
                            importedCount++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            _scanResult.postValue(importedCount to allSongs.size)
        }
    }

    /**
     * 关联歌词：将选择的歌词文件复制到 App 私有目录的 lyrics 子目录，
     * 确保路径有效且不受 SAF Uri 生命周期限制。
     */
    fun updateLyricPath(songId: Long, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val newLyricPath = AudioUtils.copyFileUriToInternalStorage(
                getApplication(), uri, "lyrics", "lrc"
            )
            val song = repository.getSongById(songId)
            if (song != null) {
                // 删除旧的歌词文件
                AudioUtils.deleteFile(song.lyricPath)
                repository.updateSong(song.copy(lyricPath = newLyricPath))
            }
        }
    }

    /**
     * 关联封面图片：将选择的图片复制到 App 私有目录的 covers 子目录，
     * 确保路径有效且不受 SAF Uri 生命周期限制。
     */
    fun updateCoverPath(songId: Long, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val newCoverPath = AudioUtils.copyFileUriToInternalStorage(
                getApplication(), uri, "covers", "jpg"
            )
            val song = repository.getSongById(songId)
            if (song != null) {
                // 删除旧的封面文件
                AudioUtils.deleteFile(song.coverPath)
                repository.updateSong(song.copy(coverPath = newCoverPath))
            }
        }
    }

    /**
     * 删除歌曲：同时清理关联的音频文件、歌词文件、封面文件。
     * 若删除的是当前播放歌曲，会停止播放。
     */
    fun deleteSong(songId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val song = repository.getSongById(songId)
            if (song != null) {
                // 清理关联文件
                AudioUtils.deleteFile(song.path)
                AudioUtils.deleteFile(song.lyricPath)
                AudioUtils.deleteFile(song.coverPath)
                // 删除数据库记录
                repository.deleteSongById(songId)
            }
        }
    }

    /**
     * 自动匹配歌词：扫描用户选中的歌词目录，对所有未关联歌词的歌曲批量匹配。
     * 匹配策略：标题归一化+artist优先，O(1) 索引避免 O(n²) 复杂度。
     */
    fun autoMatchLyrics(treeUri: Uri) {
        viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                AudioUtils.scanLyricFolder(getApplication(), treeUri)
            }
            val allSongs = withContext(Dispatchers.IO) {
                repository.getAllSongs()
            }
            // 只对未关联歌词的歌曲进行匹配
            val unmatched = allSongs.filter { it.lyricPath == null }

            val matches = withContext(Dispatchers.IO) {
                AudioUtils.matchLyricsToSongs(candidates, unmatched)
            }

            var success = 0
            withContext(Dispatchers.IO) {
                for ((songId, uri) in matches) {
                    try {
                        val destPath = AudioUtils.copyFileUriToInternalStorage(
                            getApplication(), uri, "lyrics", "lrc"
                        )
                        val song = repository.getSongById(songId)
                        if (song != null && destPath != null) {
                            // 删除旧的歌词文件（极少情况已有）
                            AudioUtils.deleteFile(song.lyricPath)
                            repository.updateSong(song.copy(lyricPath = destPath))
                            success++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            _matchResult.postValue(success to unmatched.size)
        }
    }

    /**
     * 自动匹配封面：扫描用户选中的图片目录，按标题模糊匹配并覆盖已有封面。
     */
    fun autoMatchCovers(treeUri: Uri) {
        viewModelScope.launch {
            val candidates = withContext(Dispatchers.IO) {
                AudioUtils.scanImageFolder(getApplication(), treeUri)
            }
            val allSongs = withContext(Dispatchers.IO) {
                repository.getAllSongs()
            }

            val matches = withContext(Dispatchers.IO) {
                AudioUtils.matchImagesToSongs(candidates, allSongs)
            }

            var success = 0
            withContext(Dispatchers.IO) {
                for ((songId, uri) in matches) {
                    try {
                        val destPath = AudioUtils.copyFileUriToInternalStorage(
                            getApplication(), uri, "covers", "jpg"
                        )
                        val song = repository.getSongById(songId)
                        if (song != null && destPath != null) {
                            // 覆盖模式：删除旧封面文件
                            AudioUtils.deleteFile(song.coverPath)
                            repository.updateSong(song.copy(coverPath = destPath))
                            success++
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            _coverMatchResult.postValue(Triple(success, allSongs.size, candidates.size))
        }
    }

    /**
     * 修改歌曲显示名（仅数据库title，不动源文件）。
     * 返回是否修改成功（经postValue在主线程不可直接获取，结果通过renameResult回调）。
     */
    fun renameSong(songId: Long, newTitle: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val song = repository.getSongById(songId)
            if (song != null && newTitle.isNotBlank()) {
                repository.updateSong(song.copy(title = newTitle.trim()))
                withContext(Dispatchers.Main) { onResult(true) }
            } else {
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }
}
