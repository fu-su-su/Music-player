package com.musicplayer.app.util

import java.io.File
import java.util.regex.Pattern

data class LyricLine(
    val time: Long,  // 毫秒
    val text: String
)

object LyricParser {

    private val TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:[.:](\\d{2,3}))?\\]")

    fun parseLyricFile(filePath: String): List<LyricLine> {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return emptyList()

        val lines = file.readLines()
        return parseLyricLines(lines)
    }

    fun parseLyricText(lyricText: String): List<LyricLine> {
        val lines = lyricText.lines()
        return parseLyricLines(lines)
    }

    private fun parseLyricLines(lines: List<String>): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val matcher = TIME_PATTERN.matcher(trimmedLine)
            val times = mutableListOf<Long>()

            // 提取所有时间标签
            while (matcher.find()) {
                val minutes = matcher.group(1)?.toIntOrNull() ?: 0
                val seconds = matcher.group(2)?.toIntOrNull() ?: 0
                val millisecondsStr = matcher.group(3)
                val milliseconds = when {
                    millisecondsStr == null -> 0
                    millisecondsStr.length == 2 -> (millisecondsStr.toIntOrNull() ?: 0) * 10
                    else -> millisecondsStr.toIntOrNull() ?: 0
                }
                val totalTime = (minutes * 60L + seconds) * 1000L + milliseconds
                times.add(totalTime)
            }

            // 提取歌词文本（去掉所有时间标签）
            var text = TIME_PATTERN.matcher(trimmedLine).replaceAll("").trim()
            // 处理一些特殊标签
            if (text.startsWith("ar:") || text.startsWith("ti:") || text.startsWith("al:") ||
                text.startsWith("by:") || text.startsWith("offset:")) {
                continue
            }

            if (text.isNotEmpty() && times.isNotEmpty()) {
                for (time in times) {
                    result.add(LyricLine(time, text))
                }
            } else if (text.isEmpty() && times.isNotEmpty()) {
                // 空行也添加
                for (time in times) {
                    result.add(LyricLine(time, ""))
                }
            }
        }

        // 按时间排序
        result.sortBy { it.time }
        return result
    }

    fun findCurrentLineIndex(lyrics: List<LyricLine>, currentTime: Long): Int {
        if (lyrics.isEmpty()) return -1

        var index = -1
        for (i in lyrics.indices) {
            if (lyrics[i].time <= currentTime) {
                index = i
            } else {
                break
            }
        }
        return index
    }

    /**
     * 从 LRC 文本中提取元数据：[ti:标题] [ar:艺术家]。
     * 若不存在，返回 null（用调用方提供的 fallback 标题/艺术家）。
     */
    fun extractLyricMetadata(lyricText: String): LyricMetadata {
        var title: String? = null
        var artist: String? = null

        // 行首匹配 [ti:xxx] / [ar:xxx]，大小写不敏感
        val tiPattern = Pattern.compile("\\[ti:\\s*(.+?)\\s*]", Pattern.CASE_INSENSITIVE)
        val arPattern = Pattern.compile("\\[ar:\\s*(.+?)\\s*]", Pattern.CASE_INSENSITIVE)
        tiPattern.matcher(lyricText).let { m -> if (m.find()) title = m.group(1)?.trim() }
        arPattern.matcher(lyricText).let { m -> if (m.find()) artist = m.group(1)?.trim() }

        return LyricMetadata(title = title, artist = artist)
    }
}

data class LyricMetadata(
    val title: String?,
    val artist: String?
)
