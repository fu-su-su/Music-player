package com.musicplayer.app.util

import android.app.Application
import com.musicplayer.app.data.MusicRepository
import com.musicplayer.app.data.Song
import com.musicplayer.app.playback.LoopMode
import com.musicplayer.app.playback.MusicPlayerManager
import com.musicplayer.app.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 白名单工具集：仅暴露音乐播放器的基础操作。
 * 模型只能通过这些工具影响 App，任何其他请求在 system prompt 中被拒绝。
 */
class AgentTools(private val app: Application) {

    private val playerManager = MusicPlayerManager.getInstance(app)
    private val repository = MusicRepository(app)

    companion object {
        /** 无关问题固定拒答话术 */
        const val REJECT_MESSAGE = "抱歉，我只能帮你操作音乐播放器哦～"

        private const val SYSTEM_PROMPT = """你是"音乐播放器"App内置的语音助手，只能帮助用户操作本音乐播放器。

【允许的操作】仅限通过提供的工具执行：
- 播放/暂停、上一首/下一首、跳转进度
- 按歌名或歌手点歌
- 删除歌曲、修改歌曲显示名
- 切换排序方式、切换循环模式（顺序/单曲循环/随机）
- 扫描导入设备中新添加的歌曲
- 查询当前播放状态、搜索歌曲列表

【严格限制】
1. 凡是与音乐播放器操作无关的任何请求（聊天、写代码、讲笑话、回答知识问题、写文章等），你必须且只能原样回复以下文字，不得做任何解释、扩展或变体：
抱歉，我只能帮你操作音乐播放器哦～
2. 不要编造工具之外的能力。用户想匹配歌词/封面图片等需要手动选文件夹的操作时，提示用户去主页工具栏菜单中操作。
3. 回复必须简短自然，控制在两句话以内。"""
    }

    // ====================== 工具定义 ======================

    fun buildToolsJson(): JSONArray {
        val tools = JSONArray()

        fun addTool(name: String, description: String, properties: JSONObject, required: JSONArray) {
            tools.put(
                JSONObject().put(
                    "type", "function"
                ).put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", properties)
                                .put("required", required)
                        )
                )
            )
        }

        addTool(
            "play_pause", "切换播放/暂停状态",
            JSONObject(), JSONArray()
        )
        addTool(
            "play_song", "按歌名或歌手搜索并播放。找到多首时播放第一首",
            JSONObject().put(
                "query", JSONObject()
                    .put("type", "string")
                    .put("description", "歌名或歌手名关键字")
            ),
            JSONArray().put("query")
        )
        addTool(
            "next_song", "播放下一首", JSONObject(), JSONArray()
        )
        addTool(
            "previous_song", "播放上一首", JSONObject(), JSONArray()
        )
        addTool(
            "seek_to", "跳转到指定播放进度",
            JSONObject().put(
                "position_seconds", JSONObject()
                    .put("type", "number")
                    .put("description", "目标进度，单位秒")
            ),
            JSONArray().put("position_seconds")
        )
        addTool(
            "set_loop_mode", "设置循环模式",
            JSONObject().put(
                "mode", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray().put("ORDER").put("SINGLE").put("RANDOM"))
                    .put("description", "ORDER=顺序播放 SINGLE=单曲循环 RANDOM=随机播放")
            ),
            JSONArray().put("mode")
        )
        addTool(
            "delete_song", "按歌名关键字删除匹配的歌曲（会同时删除音频/歌词/封面文件）",
            JSONObject().put(
                "query", JSONObject()
                    .put("type", "string")
                    .put("description", "要删除的歌曲名关键字")
            ),
            JSONArray().put("query")
        )
        addTool(
            "rename_song", "修改歌曲在App内显示的名字（不改动手机上的源文件）",
            JSONObject()
                .put(
                    "query", JSONObject()
                        .put("type", "string")
                        .put("description", "要改名的歌曲名关键字")
                )
                .put(
                    "new_title", JSONObject()
                        .put("type", "string")
                        .put("description", "新的显示名")
                ),
            JSONArray().put("query").put("new_title")
        )
        addTool(
            "set_sort", "设置歌曲列表排序方式",
            JSONObject().put(
                "sort_type", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray().put("NAME").put("ARTIST").put("ADD_TIME"))
                    .put("description", "NAME=按名称 ARTIST=按艺术家 ADD_TIME=按添加时间")
            ),
            JSONArray().put("sort_type")
        )
        addTool(
            "scan_device_music", "扫描手机存储中新添加的音频文件并导入",
            JSONObject(), JSONArray()
        )
        addTool(
            "get_status", "查询当前播放状态和歌曲列表规模",
            JSONObject(), JSONArray()
        )
        addTool(
            "list_songs", "搜索歌曲列表，返回匹配的歌曲名与歌手",
            JSONObject().put(
                "query", JSONObject()
                    .put("type", "string")
                    .put("description", "歌名或歌手关键字，可为空表示列出前10首")
            ),
            JSONArray()
        )

        return tools
    }

    fun buildSystemMessage(): JSONObject {
        return JSONObject()
            .put("role", "system")
            .put("content", SYSTEM_PROMPT)
    }

    // ====================== 工具执行 ======================

    /**
     * 执行指定工具，返回给模型的结果文本。
     * 只允许调用本类白名单内的工具名，其余一律拒绝。
     */
    suspend fun execute(name: String, args: JSONObject): String = when (name) {
        "play_pause" -> {
            playerManager.togglePlayPause()
            "已切换播放/暂停"
        }
        "play_song" -> {
            val query = args.optString("query", "")
            val matched = fuzzySearch(query)
            if (matched.isEmpty()) {
                "没有找到与「$query」匹配的歌曲"
            } else {
                playerManager.refreshPlaylist()
                playerManager.playSong(matched.first().id)
                "正在播放《${matched.first().title}》-${matched.first().artist}" +
                    if (matched.size > 1) "（共${matched.size}首匹配）" else ""
            }
        }
        "next_song" -> {
            playerManager.next()
            "已切换到下一首"
        }
        "previous_song" -> {
            playerManager.previous()
            "已切换到上一首"
        }
        "seek_to" -> {
            val seconds = args.optDouble("position_seconds", -1.0)
            if (seconds < 0) {
                "参数错误：position_seconds"
            } else {
                playerManager.seekTo((seconds * 1000).toLong())
                "已跳转到 ${AudioUtils.formatDuration((seconds * 1000).toLong())}"
            }
        }
        "set_loop_mode" -> {
            val mode = args.optString("mode", "")
            when (mode) {
                "ORDER" -> playerManager.setLoopMode(LoopMode.ORDER)
                "SINGLE" -> playerManager.setLoopMode(LoopMode.SINGLE)
                "RANDOM" -> playerManager.setLoopMode(LoopMode.RANDOM)
                else -> "参数错误：mode"
            }
            if (mode in listOf("ORDER", "SINGLE", "RANDOM")) "循环模式已更新" else "参数错误：mode"
        }
        "delete_song" -> {
            val query = args.optString("query", "")
            val matched = fuzzySearch(query)
            if (matched.isEmpty()) {
                "没有找到与「$query」匹配的歌曲"
            } else {
                val sb = StringBuilder()
                for (song in matched) {
                    // 若删除的是当前播放歌曲，先停止播放
                    if (playerManager.getCurrentSong()?.id == song.id) {
                        playerManager.stop()
                    }
                    AudioUtils.deleteFile(song.path)
                    AudioUtils.deleteFile(song.lyricPath)
                    AudioUtils.deleteFile(song.coverPath)
                    repository.deleteSongById(song.id)
                    sb.append("《${song.title}》")
                }
                playerManager.refreshPlaylist()
                "已删除：$sb"
            }
        }
        "rename_song" -> {
            val query = args.optString("query", "")
            val newTitle = args.optString("new_title", "").trim()
            if (newTitle.isEmpty()) {
                "参数错误：new_title不能为空"
            } else {
                val matched = fuzzySearch(query)
                if (matched.isEmpty()) {
                    "没有找到与「$query」匹配的歌曲"
                } else {
                    val song = matched.first()
                    repository.updateSong(song.copy(title = newTitle))
                    "已将《${song.title}》改名为《$newTitle》"
                }
            }
        }
        "set_sort" -> {
            // 排序由 MainActivity 的 ViewModel 管理，Agent 通过全局事件总线通知
            val type = args.optString("sort_type", "")
            if (type in listOf("NAME", "ARTIST", "ADD_TIME")) {
                AgentBus.requestSort(type)
                "排序方式已切换"
            } else {
                "参数错误：sort_type"
            }
        }
        "scan_device_music" -> {
            val allSongs = AudioUtils.scanDeviceAudioFiles(app)
            var imported = 0
            for (song in allSongs) {
                try {
                    if (!repository.existsByTitleArtistDuration(song.title, song.artist, song.duration)) {
                        repository.insertSong(song)
                        imported++
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            playerManager.refreshPlaylist()
            "扫描到${allSongs.size}首歌曲，新增导入$imported 首"
        }
        "get_status" -> {
            val info = playerManager.playbackInfo.value
            val stateText = when (info.state) {
                PlaybackState.PLAYING -> "正在播放"
                PlaybackState.PAUSED -> "已暂停"
                PlaybackState.IDLE, PlaybackState.STOPPED -> "未播放"
                PlaybackState.COMPLETED -> "播放完毕"
                PlaybackState.ERROR -> "播放出错"
            }
            if (info.currentSongId == null) {
                "$stateText。歌曲列表共${playerManager.getPlaylist().size}首"
            } else {
                "$stateText：《${info.currentSongTitle}》-${info.currentSongArtist}，" +
                    "进度 ${AudioUtils.formatDuration(info.currentPosition)}/${AudioUtils.formatDuration(info.duration)}，" +
                    "列表共${playerManager.getPlaylist().size}首"
            }
        }
        "list_songs" -> {
            val query = args.optString("query", "")
            val songs = if (query.isEmpty()) {
                repository.getAllSongs().take(10)
            } else {
                fuzzySearch(query).take(10)
            }
            if (songs.isEmpty()) {
                "没有匹配的歌曲"
            } else {
                songs.joinToString("、") { "《${it.title}》${it.artist}" }
            }
        }
        else -> "不支持的操作：$name"
    }

    /** 标题/歌手模糊搜索：归一化后双向包含 */
    private suspend fun fuzzySearch(query: String): List<Song> {
        if (query.isBlank()) return emptyList()
        val all = repository.getAllSongs()
        val q = normalize(query)
        return all.filter { song ->
            val t = normalize(song.title)
            val a = normalize(song.artist ?: "")
            t.contains(q) || q.contains(t) || a.contains(q)
        }
    }

    private fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("[\\s\\-_·+.,!?\"'()\\[\\]【】（）]"), "")
    }
}
