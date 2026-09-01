package com.musicplayer.app.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 自定义 ImageView：让图片从顶部完全不透明渐变到底部完全透明。
 *
 * 实现原理：
 *  - 先调用 super.onDraw() 绘制原图
 *  - 再用一个 LinearGradient 着色的矩形，配合 PorterDuff.Mode.DST_OUT
 *    擦除图片像素的 alpha
 *  - 渐变从顶部 alpha=0（不擦除，图片完整）到底部 alpha=255（完全擦除，图片透明）
 *
 * 注意：DST_OUT 模式需要 software layer（关闭硬件加速），否则在某些设备上无效。
 */
class GradientMaskImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    init {
        // DST_OUT 需要 software layer 才能正确工作
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 从顶部完全不擦除（图片100%不透明）到底部擦除50%（图片透明度50%），中间线性渐变
        val shader = LinearGradient(
            0f, 0f,
            0f, h,
            0x00000000,            // 顶部：alpha=0，不擦除（图片完整）
            0x80000000,            // 底部：alpha=128，擦除50%（图片50%透明）
            Shader.TileMode.CLAMP
        )
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, maskPaint)
    }
}
