package com.musicplayer.app.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 上下边缘始终对称渐隐的歌词列表。
 *
 * 系统自带的 fadingEdge 只在"有内容滚出边缘"时才有渐隐强度，导致播放中
 * 上方有渐隐、下方看不到渐显。本 View 在离屏缓冲层中绘制完歌词后，用
 * DST_OUT 渐变分别擦除顶部（淡出）与底部（淡入）区域的 alpha，
 * 与滚动位置无关，两侧永远对称，且透出底层封面。
 */
class FadingLyricRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val fadeLength: Float =
        context.resources.displayMetrics.density * FADE_LENGTH_DP

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private var topShader: Shader? = null
    private var bottomShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val length = fadeLength.coerceAtMost(h / 2f)
        // 顶部：最上沿完全擦除（淡出）→ 向内渐变为不擦除
        topShader = LinearGradient(
            0f, 0f, 0f, length,
            0xFF000000.toInt(), 0x00000000,
            Shader.TileMode.CLAMP
        )
        // 底部：向内不擦除 → 最下沿完全擦除（从下方进入时淡入）
        bottomShader = LinearGradient(
            0f, h - length, 0f, h.toFloat(),
            0x00000000, 0xFF000000.toInt(),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            super.draw(canvas)
            return
        }
        // 离屏缓冲：DST_OUT 在硬件加速画布上配合 saveLayer 可正确擦除 alpha
        val layer = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        super.draw(canvas)
        topShader?.let {
            fadePaint.shader = it
            canvas.drawRect(0f, 0f, w.toFloat(), fadeLength, fadePaint)
        }
        bottomShader?.let {
            fadePaint.shader = it
            canvas.drawRect(0f, h - fadeLength, w.toFloat(), h.toFloat(), fadePaint)
        }
        canvas.restoreToCount(layer)
    }

    companion object {
        private const val FADE_LENGTH_DP = 72f
    }
}
