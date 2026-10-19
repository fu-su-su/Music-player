package com.musicplayer.app.util

import java.io.File
import java.util.regex.Pattern

data class LyricLine(
    val time: Long,  // 毫秒
    val text: String
)

object LyricParser {

    /** 双语对允许的最大时间差（毫秒） */
    private const val BILINGUAL_MAX_GAP_MS = 1000L

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

        // 合并中英双语对：双语 LRC 中中文行与英文翻译行时间戳相同（或极接近），
        // 原本各自成行导致只有一句高亮；合并为"中文\n英文"同一条目后两句同步高亮。
        return mergeBilingualPairs(result)
    }

    /**
     * 合并相邻的"外语歌词行 + 中文翻译行"双语对（英/韩/日/俄等均支持）。
     * 判定条件（同时满足）：
     * 1. 两行时间差 ≤ [BILINGUAL_MAX_GAP_MS]（双语 LRC 时间戳通常完全相同）
     * 2. 一行含中文汉字、另一行完全不含汉字但含外语字母（拉丁/谚文/假名/
     *    西里尔/希腊/泰文/阿拉伯/希伯来/天城文等），避免把快歌里间隔很短的
     *    两句中文歌词误合并
     * 3. 两行文本不同
     * 合并后统一"外语在上、中文在下"。
     */
    private fun mergeBilingualPairs(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 2) return lines
        val merged = mutableListOf<LyricLine>()
        var i = 0
        while (i < lines.size) {
            val current = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next != null &&
                next.time - current.time in 0..BILINGUAL_MAX_GAP_MS &&
                current.text != next.text &&
                isBilingualPair(current.text, next.text)
            ) {
                // 外语行在上、中文行在下，时间取较早者
                val (foreign, chinese) =
                    if (containsHanzi(current.text)) next.text to current.text
                    else current.text to next.text
                merged.add(LyricLine(minOf(current.time, next.time), "$foreign\n$chinese"))
                i += 2
            } else {
                merged.add(current)
                i += 1
            }
        }
        return merged
    }

    /** 两行是否恰好构成"一句外语原文 + 一句中文翻译"。 */
    private fun isBilingualPair(a: String, b: String): Boolean {
        val aHasHanzi = containsHanzi(a)
        val bHasHanzi = containsHanzi(b)
        // 必须恰好一行含汉字、另一行不含汉字
        if (aHasHanzi == bHasHanzi) return false
        val foreignText = if (aHasHanzi) b else a
        // 外语行需含至少 2 个外语字母，排除纯标点/数字/符号行
        return countForeignLetters(foreignText) >= 2
    }

    /** 文本是否含 CJK 统一表意文字（中文/日文汉字）。 */
    private fun containsHanzi(text: String): Boolean =
        text.any { it.code in 0x4E00..0x9FFF }

    /**
     * 统计非汉字的外语字母数量：拉丁、希腊、西里尔、希伯来、阿拉伯、泰文、
     * 天城文（印地语）、日文假名、韩文谚文等。
     */
    private fun countForeignLetters(text: String): Int {
        var count = 0
        for (ch in text) {
            val c = ch.code
            val isForeignLetter =
                c in 0x0041..0x024F ||   // 拉丁字母及扩展
                c in 0x0370..0x03FF ||   // 希腊字母
                c in 0x0400..0x04FF ||   // 西里尔字母（俄语等）
                c in 0x0590..0x05FF ||   // 希伯来字母
                c in 0x0600..0x06FF ||   // 阿拉伯字母
                c in 0x0900..0x097F ||   // 天城文（印地语）
                c in 0x0E00..0x0E7F ||   // 泰文
                c in 0x3040..0x30FF ||   // 日文平假名/片假名
                c in 0xAC00..0xD7AF      // 韩文谚文音节
            if (isForeignLetter) count++
        }
        return count
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
