package com.perflyst.twire.misc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.rey.material.drawable.BlankDrawable
import timber.log.Timber
import kotlin.math.roundToInt

class GlideImageSpan(
    context: Context,
    url: String?,
    private val textView: TextView,
    assumedSize: Int,
    scale: Float,
    // EditText safety: reassigning the text to force a re-measure fires the
    // TextWatcher again and can reset the cursor mid-typing. Callers editing
    // live text pass false to relayout via invalidate/requestLayout instead.
    private val remeasureOnLoad: Boolean = true,
    fallbackText: String? = null
) : VerticalImageSpan(BlankDrawable()), Drawable.Callback {

    /** The emote URL this span was created for — lets callers reuse spans. */
    val sourceUrl: String? = url

    private var layerDrawable: LayerDrawable? = null

    private var mDrawable: Drawable? = null
    private var animatable: Animatable? = null
    private var imageTarget: CustomTarget<Drawable?>? = null

    constructor(
        context: Context,
        url: String?,
        textView: TextView,
        assumedSize: Int,
        scale: Float,
        backgroundColor: String?
    ) : this(context, url, textView, assumedSize, scale) {
        if (backgroundColor == null) return

        val backgroundDrawable = backgroundColor.toColorInt().toDrawable()

        this.layerDrawable = LayerDrawable(arrayOf<Drawable>(backgroundDrawable, BlankDrawable()))
        layerDrawable!!.setId(0, 0)
        layerDrawable!!.setId(1, 1)
    }

    init {
        val instance = this

        val scaledAssumedSize = (assumedSize / scale).roundToInt()
        val fallbackDrawable = fallbackText?.let {
            KeywordDrawable(it, textView, scaledAssumedSize)
        } ?: Color.LTGRAY.toDrawable().apply {
            setBounds(0, 0, scaledAssumedSize, scaledAssumedSize)
        }
        val errorDrawable = fallbackText?.let {
            KeywordDrawable(it, textView, scaledAssumedSize)
        } ?: (-0x3334).toDrawable().apply {
            setBounds(0, 0, scaledAssumedSize, scaledAssumedSize)
        }
        // CustomTarget otherwise requests the source at original size. Emotes
        // are tiny in chat, so decoding a full-resolution animated asset wastes
        // bandwidth and memory on slower phones.
        val requestSize = (assumedSize * scale).roundToInt().coerceAtLeast(1)

        val target = object : CustomTarget<Drawable?>() {
            override fun onLoadStarted(resource: Drawable?) {
                mDrawable = resource ?: fallbackDrawable
            }

            override fun onResourceReady(
                resource: Drawable,
                transition: Transition<in Drawable?>?
            ) {
                resource.setBounds(
                    0,
                    0,
                    (resource.intrinsicWidth / scale).roundToInt(),
                    (resource.intrinsicHeight / scale).roundToInt()
                )

                if (resource is Animatable) {
                    animatable = resource
                    resource.callback = instance
                    resource.start()
                }

                mDrawable = resource

                if (resource.intrinsicWidth != assumedSize) {
                    if (remeasureOnLoad) {
                        textView.text = textView.getText()
                    } else {
                        // Force the text layout to pick up the new span bounds
                        // WITHOUT touching the text (keeps cursor + IME state).
                        textView.invalidate()
                        textView.requestLayout()
                    }
                    Timber.tag("EmoteShift")
                        .d("Got $resource.intrinsicWidth but assumed $assumedSize")
                } else {
                    textView.invalidate()
                }
            }

            override fun onLoadFailed(resource: Drawable?) {
                // Keep the emote's keyword visible if its CDN is unavailable or
                // the image decoder cannot handle the returned format.
                mDrawable = resource ?: fallbackDrawable
                textView.invalidate()
                textView.requestLayout()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                animatable?.stop()
                mDrawable = placeholder ?: fallbackDrawable
                textView.invalidate()
            }
        }
        imageTarget = target

        Glide.with(context)
            .load(url)
            .override(requestSize, requestSize)
            .error(errorDrawable)
            .placeholder(fallbackDrawable)
            .into(target)
    }

    /** Cancel a stale row's request so off-screen chat does not clog Glide's queue. */
    fun clearImageRequest() {
        val target = imageTarget ?: return
        imageTarget = null
        runCatching { Glide.with(textView).clear(target) }
    }

    override fun invalidateDrawable(drawable: Drawable) {
        textView.invalidate()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        textView.postDelayed(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        textView.removeCallbacks(what)
    }

    override fun getDrawable(): Drawable? {
        val drawable = (if (this.mDrawable != null) this.mDrawable else super.getDrawable())!!

        if (layerDrawable != null) {
            layerDrawable!!.getDrawable(0).bounds = drawable.getBounds()
            layerDrawable!!.bounds = drawable.getBounds()
            layerDrawable!!.setDrawableByLayerId(1, drawable)

            return layerDrawable
        }

        return drawable
    }

    private class KeywordDrawable(
        private val text: String,
        textView: TextView,
        minHeight: Int
    ) : Drawable() {
        private val paint = Paint(textView.paint).apply {
            color = textView.currentTextColor
            isAntiAlias = true
        }

        init {
            val width = paint.measureText(text).roundToInt().coerceAtLeast(minHeight)
            val height = maxOf(minHeight, textView.lineHeight)
            setBounds(0, 0, width, height)
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val metrics = paint.fontMetrics
            val baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(text, bounds.left.toFloat(), baseline, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
