package com.polaris.browser

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Fullscreen container for HTML5 video. Swipes on the left edge change
 * brightness, on the right edge — volume. Everything else passes through
 * to the page's own player controls.
 */
open class VideoFrame(val act: Activity) : FrameLayout(act) {
    private val slop = ViewConfiguration.get(act).scaledTouchSlop
    private val audio = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var downX = 0f; private var downY = 0f
    private var zone = 0 // 0 none, 1 brightness, 2 volume
    private var dragging = false
    private var startVal = 0f
    var onAnyTouch: (() -> Unit)? = null
    /** Return true for touches that should never start a gesture (e.g. over seek bars). */
    var excludeBottomFraction = 0.18f

    val indicator: LinearLayout
    private val indIcon: ImageView
    private val indText: TextView

    init {
        setBackgroundColor(Color.BLACK)
        val p = Palette.DARK
        indicator = hbox(act).apply {
            background = glass(act, 22f, p, tint = 0xCC111522.toInt())
            setPadding(dp(16), dp(10), dp(18), dp(10))
            visibility = View.GONE
            elevation = dp(6).toFloat()
        }
        indIcon = ImageView(act)
        indText = label(act, "", 16f, Color.WHITE, true)
        indicator.addView(indIcon, lp(dp(22), dp(22)).apply { marginEnd = dp(10) })
        indicator.addView(indText)
        addView(indicator, LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    fun bringIndicatorToFront() { indicator.bringToFront() }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onAnyTouch?.invoke()
                downX = e.x; downY = e.y; dragging = false
                zone = when {
                    e.y > height * (1 - excludeBottomFraction) || e.y < height * 0.1f -> 0
                    e.x < width * 0.3f -> 1
                    e.x > width * 0.7f -> 2
                    else -> 0
                }
                startVal = if (zone == 1) currentBrightness() else currentVolumeFrac()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY; val dx = e.x - downX
                if (zone != 0 && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.6f) { dragging = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val delta = (downY - e.y) / (height * 0.8f)
                val v = (startVal + delta).coerceIn(0f, 1f)
                if (zone == 1) setBrightness(v) else setVolumeFrac(v)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                postDelayed({ indicator.visibility = View.GONE }, 600)
            }
        }
        return true
    }

    private fun currentBrightness(): Float {
        val b = act.window.attributes.screenBrightness
        if (b >= 0) return b
        return try {
            android.provider.Settings.System.getInt(act.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) { 0.5f }
    }

    fun setBrightness(v: Float) {
        act.window.attributes = act.window.attributes.apply { screenBrightness = v.coerceAtLeast(0.01f) }
        show(Ic.SUN, "${(v * 100).toInt()}%")
    }

    private fun currentVolumeFrac(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolumeFrac(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val n = Math.round(v * max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, n, 0)
        show(VOL, "${(n * 100 / max)}%")
    }

    fun show(path: String, text: String) {
        indIcon.setImageDrawable(IconDrawable(path, Color.WHITE))
        indText.text = text
        indicator.visibility = View.VISIBLE
        indicator.bringToFront()
    }

    fun resetBrightness() {
        act.window.attributes = act.window.attributes.apply {
            screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    companion object {
        const val VOL = "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
        const val FFWD = "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z"
        const val REW = "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z"
        const val SPEED = "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z"
    }
}
