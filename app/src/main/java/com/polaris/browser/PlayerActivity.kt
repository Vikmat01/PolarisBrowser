package com.polaris.browser

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Polaris video player: swipe left edge — brightness, right edge — volume,
 * double tap left/right — seek ∓10 s, speed control, picture-in-picture,
 * remembers position per video.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        fun open(ctx: Context, url: String, referer: String?, title: String?, cookie: String?, ua: String?, incognito: Boolean = false) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                data = Uri.parse(url)
                putExtra("incognito", incognito)
                putExtra("referer", referer); putExtra("title", title)
                putExtra("cookie", cookie); putExtra("ua", ua)
            })
        }
        fun openLocal(ctx: Context, uri: Uri, title: String) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                data = uri; putExtra("title", title); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
    }

    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var frame: VideoFrame
    private lateinit var top: android.widget.LinearLayout
    private lateinit var speedLbl: TextView
    private lateinit var prefs: Prefs
    private var aspect = Rational(16, 9)
    private var key = ""
    private var incognito = false
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        incognito = intent.getBooleanExtra("incognito", false)
        Incog.secure(window, incognito)
        hideBars()

        frame = VideoFrame(this)
        frame.excludeBottomFraction = 0.22f
        pv = PlayerView(this).apply {
            setShowNextButton(false); setShowPreviousButton(false)
            controllerShowTimeoutMs = 3000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> this@PlayerActivity.top.visibility = v })
        }
        frame.addView(pv, 0, FrameLayout.LayoutParams(MATCH, MATCH))

        val pd = Palette.DARK
        top = hbox(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        top.addView(iconButton(this, Ic.BACK, 44, 22, Color.WHITE) { finish() })
        top.addView(label(this, intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: (intent.data?.lastPathSegment ?: "Видео"),
            15f, Color.WHITE, true).apply { maxLines = 1; setShadowLayer(4f, 0f, 1f, Color.BLACK) }, lp(0, WRAP, 1f))
        val tools = hbox(this).apply {
            background = glass(this@PlayerActivity, 22f, pd, tint = 0x99101420.toInt())
            setPadding(dp(4), 0, dp(4), 0)
        }
        speedLbl = label(this, fmt(prefs.playerSpeed), 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
            background = ripple(this@PlayerActivity, null, 20f)
            setOnClickListener { cycleSpeed() }
        }
        tools.addView(speedLbl, lp(WRAP, dp(40)))
        tools.addView(iconButton(this, Ic.DESKTOP, 40, 20, Color.WHITE) { cycleResize() })
        tools.addView(iconButton(this, Ic.ROTATE, 40, 20, Color.WHITE) {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        })
        tools.addView(iconButton(this, Ic.PIP, 40, 20, Color.WHITE) { pip() })
        top.addView(tools)
        frame.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        frame.bringIndicatorToFront()

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                val w = frame.width
                when {
                    e.x < w * 0.4f -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); frame.show(VideoFrame.REW, "−10 с") }
                    e.x > w * 0.6f -> { p.seekTo(p.currentPosition + 10_000); frame.show(VideoFrame.FFWD, "+10 с") }
                    else -> { if (p.isPlaying) p.pause() else p.play() }
                }
                frame.postDelayed({ frame.indicator.visibility = View.GONE }, 600)
                return true
            }
        })
        pv.setOnTouchListener { v, e -> gd.onTouchEvent(e); false }

        setContentView(frame)
        frame.setOnApplyWindowInsetsListener { _, ins ->
            val c = ins.getInsets(android.view.WindowInsets.Type.displayCutout())
            top.setPadding(dp(8) + c.left, dp(8) + c.top, dp(8) + c.right, dp(8))
            ins
        }
        startPlayer(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        savePos()
        player?.release(); player = null
        startPlayer(intent)
    }

    private fun startPlayer(i: Intent) {
        val uri = i.data ?: run { finish(); return }
        key = "pos:" + uri.toString().substringBefore('?').takeLast(180)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(i.getStringExtra("ua") ?: "Mozilla/5.0 (Linux; Android) PolarisBrowser")
        val headers = HashMap<String, String>()
        i.getStringExtra("cookie")?.let { headers["Cookie"] = it }
        i.getStringExtra("referer")?.takeIf { it.startsWith("http") }?.let { headers["Referer"] = it }
        http.setDefaultRequestProperties(headers)
        val dsf = DefaultDataSource.Factory(this, http)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dsf)).build()
        player = p
        pv.player = p
        val item = MediaItem.Builder().setUri(uri).apply {
            if (uri.path?.endsWith(".m3u8", true) == true) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.playbackParameters = PlaybackParameters(prefs.playerSpeed)
        val saved = if (incognito) 0L else prefs.sp.getLong(key, 0)
        if (saved > 5000) p.seekTo(saved)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width <= 0 || v.height <= 0) return
                val w = (v.width * v.pixelWidthHeightRatio).toInt()
                aspect = Rational(w, v.height)
                requestedOrientation = if (w >= v.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                updatePip()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) prefs.sp.edit().remove(key).apply()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                toast(this@PlayerActivity, "Не удалось воспроизвести: ${error.errorCodeName}")
            }
        })
        p.prepare()
        p.play()
    }

    private fun fmt(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

    private fun cycleSpeed() {
        val curIdx = SPEEDS.indexOfFirst { it == prefs.playerSpeed }.let { if (it < 0) 2 else it }
        val s = SPEEDS[(curIdx + 1) % SPEEDS.size]
        prefs.playerSpeed = s
        player?.playbackParameters = PlaybackParameters(s)
        speedLbl.text = fmt(s)
        frame.show(VideoFrame.SPEED, fmt(s))
        frame.postDelayed({ frame.indicator.visibility = View.GONE }, 700)
    }

    private fun cycleResize() {
        resizeMode = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        pv.resizeMode = resizeMode
        frame.show(Ic.DESKTOP, when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Заполнить"
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Растянуть"
            else -> "Вписать"
        })
        frame.postDelayed({ frame.indicator.visibility = View.GONE }, 800)
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val r = aspect.toFloat()
        val ratio = when { r > 2.39f -> Rational(239, 100); r < 1 / 2.39f -> Rational(100, 239); else -> aspect }
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(true).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePip() { try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {} }

    private fun pip() {
        try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) { toast(this, "Картинка в картинке недоступна") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && Build.VERSION.SDK_INT < 31) pip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        pv.useController = !inPip
        top.visibility = if (inPip) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        savePos()
        player?.pause()
    }

    override fun onResume() { super.onResume(); hideBars() }

    private fun savePos() {
        if (incognito) return
        val p = player ?: return
        if (p.duration > 60_000 && p.currentPosition < p.duration - 15_000)
            prefs.sp.edit().putLong(key, p.currentPosition).apply()
    }

    override fun onDestroy() {
        savePos()
        frame.resetBrightness()
        player?.release()
        player = null
        super.onDestroy()
    }
}
