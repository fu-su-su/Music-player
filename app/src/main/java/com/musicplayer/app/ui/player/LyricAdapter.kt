package com.musicplayer.app.ui.player

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.musicplayer.app.databinding.ItemLyricBinding
import com.musicplayer.app.util.LyricLine
import com.musicplayer.app.util.ThemeManager.LyricFontSize

/**
 * 歌词列表适配器。字号三档（小/中/大）由 [LyricFontSize] 决定，
 * 从 PlayerActivity 按用户设置传入。
 */
class LyricAdapter(
    private val fontSize: LyricFontSize = LyricFontSize.MEDIUM
) : ListAdapter<LyricLine, LyricAdapter.LyricViewHolder>(DIFF_CALLBACK) {

    private var currentHighlightIndex = -1

    companion object {
        /** 非高亮歌词颜色：浅灰 */
        private const val COLOR_NORMAL = 0xFFB0B0B0.toInt()
        /** 高亮歌词颜色：纯白 */
        private const val COLOR_HIGHLIGHT = 0xFFFFFFFF.toInt()

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LyricLine>() {
            override fun areItemsTheSame(oldItem: LyricLine, newItem: LyricLine) =
                oldItem.time == newItem.time
            override fun areContentsTheSame(oldItem: LyricLine, newItem: LyricLine) =
                oldItem == newItem
        }
    }

    fun updateHighlight(index: Int) {
        val oldIndex = currentHighlightIndex
        currentHighlightIndex = index
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (index != -1 && index != oldIndex) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val binding = ItemLyricBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return LyricViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        holder.bind(getItem(position), position == currentHighlightIndex)
    }

    inner class LyricViewHolder(private val binding: ItemLyricBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(line: LyricLine, isHighlighted: Boolean) {
            binding.lyricTextView.text = line.text.ifEmpty { "♪" }

            // 纯文本高亮：仅通过文字本身的样式区分，无 alpha 动画；字号跟随用户设置
            if (isHighlighted) {
                binding.lyricTextView.setTextColor(COLOR_HIGHLIGHT)  // 纯白
                binding.lyricTextView.textSize = fontSize.highlightSp
                binding.lyricTextView.setTypeface(null, Typeface.BOLD)  // 粗体
            } else {
                binding.lyricTextView.setTextColor(COLOR_NORMAL)    // 浅灰
                binding.lyricTextView.textSize = fontSize.normalSp
                binding.lyricTextView.setTypeface(null, Typeface.NORMAL)  // 常规
            }

            // alpha 全部满，不做淡入淡出
            binding.lyricTextView.animate().cancel()
            binding.lyricTextView.alpha = 1.0f
        }
    }
}
