package com.polaris.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PictureInPictureParams
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : Activity() {

    internal lateinit var prefs: Prefs
    internal lateinit var db: Db
    internal val tabs = mutableListOf<Tab>()
    internal var cur: Tab? = null
    internal val main = Handler(Looper.getMainLooper())
    internal var p: Palette = Palette.LIGHT

    // chrome
    internal lateinit var root: FrameLayout
    private lateinit var statusScrim: View
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var holder: FrameLayout
    private lateinit var progress: View
    internal lateinit var barWrap: FrameLayout
    private lateinit var bar: LinearLayout
    private lateinit var addrIcon: ImageView
    private lateinit var addrText: TextView
    private lateinit var readerBtn: ImageView
    private lateinit var tabsCount: TextView
    private lateinit var backBtn: ImageView
    private var editPanel: FrameLayout? = null
    private var findBar: LinearLayout? = null

    private var statusTop = 0
    private var navBottom = 0
    private var imeBottom = 0
    private var barMode = 0 // 0 full, 1 mini capsule, 2 hidden by swipe
    private var barHandle: View? = null
    private var barIncognito: Boolean? = null
    internal lateinit var glassBar: GlassBar
    internal val scripts by lazy { ScriptStore(this) }
    internal val scriptToken: String = java.util.UUID.randomUUID().toString()
    internal val scriptCallbackKey: String = java.util.UUID.randomUUID().toString()
    private val scriptHandlers = java.util.WeakHashMap<WebView, androidx.webkit.ScriptHandler>()
    private var scriptBundle: String? = null
    private lateinit var addrField: LinearLayout
    private lateinit var tabsBtn: FrameLayout
    private lateinit var moreBtn: ImageView

    // video
    internal var videoFrame: VideoFrame? = null
    private var videoCallback: WebChromeClient.CustomViewCallback? = null
    private var videoControls: LinearLayout? = null
    private var videoSize = Rational(16, 9)
    private var speedIdx = 0
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.5f, 0.75f)

    // pending
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var blockedUnsaved = 0L
    internal var desktopUa = ""
    private var mobileUa = ""
    internal val readabilityJs by lazy { assets.open("Readability.js").bufferedReader().readText() }
    private val readerableJs by lazy { assets.open("Readability-readerable.js").bufferedReader().readText() }

    companion object {
        const val INC = "polaris-incognito"
        const val REQ_FILE = 11
        const val REQ_MEDIA = 12
        const val REQ_GEO = 13
        const val REQ_LOC = 14
        val VIDEO_EXT = Regex(".*\\.(mp4|webm|mkv|m3u8|mov|m4v|3gp)(\\?.*)?$", RegexOption.IGNORE_CASE)
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = Db(this)
        p = Palette.forPrefs(this, prefs)
        Palette.cur = p
        window.setDecorFitsSystemWindows(false)
        AdBlocker.init(this, prefs)
        Incog.wipe() // no incognito tab survives a restart, so nothing may survive on disk either
        buildUi()
        restoreSession()
        handleIntent(intent, fresh = true)
        if (tabs.isEmpty()) newTab(null, false)
        if (prefs.theme == "auto" && !prefs.askedLocation &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedLocation = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
        }
        main.postDelayed(themeTicker, 5 * 60_000L)
        main.postDelayed({ if (!isFinishing) Updater.autoCheck(this) }, 2500)
    }

    private val themeTicker = object : Runnable {
        override fun run() { checkTheme(); main.postDelayed(this, 5 * 60_000L) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, fresh = false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::glassBar.isInitialized) glassBar.kick(if (ev.actionMasked == MotionEvent.ACTION_UP) 900 else 400)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (::glassBar.isInitialized) { glassBar.setIdleRefresh(true); glassBar.kick(800) }
        cur?.web?.onResume()
        checkTheme()
        // settings may have changed
        tabs.forEach { t -> t.web?.let { applySettings(it, t) } }
        if (cur?.isHome == true) feedHome(cur!!)
    }

    override fun onPause() {
        super.onPause()
        if (::glassBar.isInitialized) glassBar.setIdleRefresh(false)
        if (!isInPictureInPictureMode) cur?.web?.onPause()
        saveSession()
        flushBlocked()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        main.removeCallbacks(themeTicker)
        if (::glassBar.isInitialized) try { glassBar.dismiss() } catch (_: Exception) {}
        tabs.forEach { it.web?.destroy() }
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoFrame != null) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        videoControls?.visibility = if (inPip) View.GONE else View.VISIBLE
        if (!inPip && lifecycleStopped) cur?.web?.evaluateJavascript("document.querySelectorAll('video').forEach(v=>v.pause())", null)
    }

    private var lifecycleStopped = false
    override fun onStop() { super.onStop(); lifecycleStopped = true }
    override fun onStart() { super.onStart(); lifecycleStopped = false }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            videoFrame != null -> videoCallback?.onCustomViewHidden() ?: hideVideo()
            editPanel != null -> closeEdit()
            findBar != null -> closeFind()
            cur?.web?.canGoBack() == true -> cur!!.web!!.goBack()
            cur != null && cur!!.parentId != -1L && tabs.any { it.id == cur!!.parentId } -> closeTab(cur!!)
            else -> moveTaskToBack(true)
        }
    }

    // ───────────────────────── intents ─────────────────────────

    private fun handleIntent(i: Intent?, fresh: Boolean) {
        i ?: return
        val target: String = when (i.action) {
            // other apps may only hand us web pages: never javascript:, file: or data:
            Intent.ACTION_VIEW -> i.dataString?.takeIf { isWebUrl(it) }
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { resolveFromApp(it) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { txt ->
                Regex("https?://\\S+").find(txt)?.value ?: resolveFromApp(txt)
            }
            else -> null
        } ?: return
        val c = cur
        if (c != null && c.isHome && !c.incognito && (c.web?.canGoBack() != true)) load(c, target)
        else newTab(target, false, parent = -2)
    }

    private fun resolveFromApp(text: String): String =
        resolve(text).let { if (it.isEmpty() || isWebUrl(it)) it else prefs.searchUrl(text) }

    // ───────────────────────── theme ─────────────────────────

    internal fun checkTheme() {
        val np = Palette.forPrefs(this, prefs)
        if (np.key != p.key) {
            p = np
            Palette.cur = p
            rebuildChrome()
            tabs.filter { it.isHome }.forEach { feedHome(it) }
        }
        applySystemBars()
    }

    private fun applySystemBars() {
        val t = cur
        val c = WindowInsetsControllerCompat(window, window.decorView)
        val home = t?.isHome == true
        val tc = t?.themeColor
        val scrimColor = when {
            home -> Color.TRANSPARENT
            tc != null -> tc
            t?.incognito == true -> 0xFF14171D.toInt()
            else -> p.bg
        }
        val darkTop = when {
            home -> p.dark || t?.incognito == true
            tc != null -> isDarkColor(tc)
            t?.incognito == true -> true
            else -> p.dark
        }
        c.isAppearanceLightStatusBars = !darkTop
        c.isAppearanceLightNavigationBars = !(p.dark || t?.incognito == true)
        root.setBackgroundColor(p.bg)
        statusScrim.setBackgroundColor(scrimColor)
    }

    private fun isDarkColor(c: Int): Boolean = Palette.luma(c) < 140

    // ── status bar follows the page: sample the colour right under it ──
    private val sampleRunnable = Runnable { sampleTop() }
    private fun scheduleSample(delay: Long) { main.removeCallbacks(sampleRunnable); main.postDelayed(sampleRunnable, delay) }

    private fun sampleTop() {
        val t = cur ?: return
        if (t.isHome || videoFrame != null || editPanel != null || statusTop <= 0 || isInPictureInPictureMode) return
        val w = root.width
        if (w <= 0) return
        val y = statusTop + dp(4)
        val bmp = Bitmap.createBitmap(48, 2, Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(window, android.graphics.Rect(0, y, w, y + dp(3)), bmp, { res ->
                if (res != android.view.PixelCopy.SUCCESS || cur !== t) return@request
                // most common colour wins (ignores logos/text in the header row)
                val counts = HashMap<Int, Int>()
                val sums = HashMap<Int, IntArray>()
                for (yy in 0 until bmp.height) for (x in 0 until bmp.width) {
                    val c = bmp.getPixel(x, yy)
                    val key = ((Color.red(c) shr 4) shl 8) or ((Color.green(c) shr 4) shl 4) or (Color.blue(c) shr 4)
                    counts[key] = (counts[key] ?: 0) + 1
                    val a = sums.getOrPut(key) { IntArray(3) }
                    a[0] += Color.red(c); a[1] += Color.green(c); a[2] += Color.blue(c)
                }
                val best = counts.maxByOrNull { it.value } ?: return@request
                val a = sums[best.key]!!
                val n = best.value
                val col = Color.rgb(a[0] / n, a[1] / n, a[2] / n)
                if (t.themeColor != col) { t.themeColor = col; applySystemBars() }
            }, main)
        } catch (_: Exception) {}
    }

    // ───────────────────────── UI ─────────────────────────

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(p.bg)

        swipe = SwipeRefreshLayout(this)
        holder = FrameLayout(this)
        swipe.addView(holder, ViewGroup.LayoutParams(MATCH, MATCH))
        swipe.setOnChildScrollUpCallback { _, _ -> (cur?.web?.scrollY ?: 0) > 0 || cur?.isHome == true }
        swipe.setOnRefreshListener { cur?.web?.reload(); main.postDelayed({ swipe.isRefreshing = false }, 700) }
        swipe.setProgressBackgroundColorSchemeColor(if (p.dark) 0xFF1C2133.toInt() else Color.WHITE)
        swipe.setColorSchemeColors(p.accent)
        root.addView(swipe, FrameLayout.LayoutParams(MATCH, MATCH))

        statusScrim = View(this)
        root.addView(statusScrim, FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP))

        progress = View(this).apply { pivotX = 0f; scaleX = 0f; alpha = 0f }
        paintProgress()
        root.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.TOP))

        // Activity-side host for the find bar (needs keyboard focus).
        barWrap = FrameLayout(this)
        root.addView(barWrap, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        glassBar = GlassBar(this)
        glassBar.onSwipeDown = { setBarMode(2) }
        glassBar.onMiniTap = { setBarMode(0) }
        buildBar()

        root.setOnApplyWindowInsetsListener { _, ins ->
            val wi = WindowInsetsCompat.toWindowInsetsCompat(ins)
            statusTop = wi.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            navBottom = wi.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            imeBottom = wi.getInsets(WindowInsetsCompat.Type.ime()).bottom
            layoutInsets()
            if (::glassBar.isInitialized) { glassBar.setHidden(barMode == 2 || imeBottom > 0); updateHandle() }
            ins
        }
        setContentView(root)
        root.post {
            if (isFinishing || isDestroyed) return@post
            try { glassBar.show() } catch (_: Exception) {}
            placeBar()
        }
        applySystemBars()
    }

    private fun paintProgress() {
        val a = p.accent
        progress.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf((a and 0x00FFFFFF) or 0x66000000, a, Color.WHITE and 0xCCFFFFFF.toInt())).apply { cornerRadius = dp(2).toFloat() }
    }

    private fun placeBar() {
        if (::glassBar.isInitialized) glassBar.place(navBottom + dp(10), dp(12))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { placeBar() }
    }

    private var edgeHome = false

    private fun layoutInsets() {
        val full = videoFrame != null
        edgeHome = cur?.isHome == true
        (statusScrim.layoutParams as FrameLayout.LayoutParams).height = if (full) 0 else statusTop
        statusScrim.requestLayout()
        (swipe.layoutParams as FrameLayout.LayoutParams).topMargin = if (full || edgeHome) 0 else statusTop
        swipe.requestLayout()
        (progress.layoutParams as FrameLayout.LayoutParams).topMargin = statusTop
        progress.requestLayout()
        val lpw = barWrap.layoutParams as FrameLayout.LayoutParams
        lpw.bottomMargin = maxOf(navBottom, imeBottom)
        barWrap.layoutParams = lpw
        editPanel?.setPadding(0, statusTop, 0, maxOf(navBottom, imeBottom))
        placeBar()
    }

    private fun buildBar() {
        glassBar.content.removeAllViews()
        barIncognito = null
        lastCount = -1
        bar = hbox(this).apply { setPadding(dp(7), dp(7), dp(7), dp(7)) }
        backBtn = iconButton(this, Ic.BACK, 50, 22) { onBackPressed() }
        backBtn.setOnLongClickListener { showHistoryStack(); true }
        bar.addView(backBtn)

        val addr = hbox(this).apply {
            background = ripple(this@MainActivity, rounded(this@MainActivity, p.field, 22f), 22f)
            setPadding(dp(13), 0, dp(4), 0)
            setOnClickListener { openEdit() }
            setOnLongClickListener { copyUrl(); true }
            pressable(0.97f)
        }
        addrField = addr
        addrIcon = ImageView(this).apply {
            setOnClickListener { showSiteInfo() }
            setPadding(dp(1), dp(1), dp(1), dp(1))
        }
        addr.addView(addrIcon, lp(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addrText = label(this, "", 15.5f, p.text, true).apply { maxLines = 1; gravity = Gravity.CENTER_VERTICAL }
        addr.addView(addrText, lp(0, MATCH, 1f))
        readerBtn = iconButton(this, Ic.READER, 36, 20, p.accent) { openReader() }.apply { visibility = View.GONE }
        addr.addView(readerBtn)
        bar.addView(addr, lp(0, dp(50), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })

        tabsCount = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setTextColor(p.text)
            background = GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setStroke(dp(2), p.text) }
        }
        val tc = FrameLayout(this).apply {
            background = ripple(this@MainActivity, null, 22f)
            setOnClickListener { showTabs() }
            setOnLongClickListener { newTab(null, cur?.incognito == true); true }
            pressable(0.84f)
        }
        tabsBtn = tc
        tc.addView(tabsCount, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
        bar.addView(tc, lp(dp(50), dp(50)).apply { marginEnd = dp(6) })
        moreBtn = iconButton(this, Ic.MORE, 50, 22) { showMenu() }
        bar.addView(moreBtn)

        glassBar.content.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP))
        glassBar.setStyle(p, false)
        updateChrome()
    }

    internal fun rebuildChrome() {
        root.setBackgroundColor(p.bg)
        swipe.setProgressBackgroundColorSchemeColor(if (p.dark) 0xFF1C2133.toInt() else Color.WHITE)
        swipe.setColorSchemeColors(p.accent)
        paintProgress()
        buildBar()
        findBar?.let { closeFind() }
        applySystemBars()
    }

    private var lastCount = -1

    internal fun updateChrome() {
        val t = cur ?: return
        if (barMode == 1) { if (t.isHome) setBarMode(0) else refreshMiniText() }
        if (Incog.isSecure(window) != t.incognito) Incog.secure(window, t.incognito)
        val n = tabs.count { it.incognito == t.incognito }
        tabsCount.text = if (n > 99) ":)" else n.toString()
        if (lastCount != -1 && lastCount != n) tabsCount.pop()
        lastCount = n
        val fg = if (t.incognito) Color.WHITE else p.text
        if (barIncognito != t.incognito) {
            barIncognito = t.incognito
            glassBar.setStyle(p, t.incognito)
            (tabsCount.background as GradientDrawable).setStroke(dp(2), fg)
            tabsCount.setTextColor(fg)
            addrField.background = ripple(this, glassBar.fieldLens(addrField, p, t.incognito)
                ?: rounded(this, if (t.incognito) 0x26FFFFFF else p.field, 25f), 25f)
            // round matte backings under the bar icons
            for (v in listOf<View>(backBtn, tabsBtn, moreBtn)) {
                v.background = ripple(this, glassBar.fieldLens(v, p, t.incognito, 25f)
                    ?: rounded(this, if (t.incognito) 0x26FFFFFF else p.field, 25f), 25f)
            }
            for (i in 0 until bar.childCount) {
                ((bar.getChildAt(i) as? ImageView)?.drawable as? IconDrawable)?.tint(fg)
            }
        }
        (backBtn.drawable as? IconDrawable)?.tint(if (t.web?.canGoBack() == true || t.parentId != -1L) fg else (fg and 0x55FFFFFF))
        if (t.isHome) {
            addrText.swapText("Поиск")
            addrText.setTextColor(if (t.incognito) 0xB3FFFFFF.toInt() else p.dim)
            addrIcon.setImageDrawable(icon(this, if (t.incognito) Ic.INCOGNITO else Ic.SEARCH, if (t.incognito) Color.WHITE else p.accent))
        } else {
            addrText.swapText(t.host.ifEmpty { t.url })
            addrText.setTextColor(fg)
            val secure = t.url.startsWith("https://")
            val shieldOn = prefs.adblock && !siteAllowed(t.host)
            addrIcon.setImageDrawable(
                when {
                    t.blocked > 0 && shieldOn -> icon(this, Ic.SHIELD, 0xFF2FBF71.toInt())
                    t.incognito -> icon(this, Ic.INCOGNITO, Color.WHITE)
                    secure -> icon(this, Ic.LOCK, p.dim)
                    else -> icon(this, Ic.GLOBE, 0xFFE0A030.toInt())
                }
            )
        }
        val rv = if (t.readerable && !t.isHome) View.VISIBLE else View.GONE
        if (readerBtn.visibility != rv) { readerBtn.visibility = rv; if (rv == View.VISIBLE) readerBtn.pop() }
        setLoadProgress(t.progress)
        if (edgeHome != t.isHome) layoutInsets()
        applySystemBars()
    }

    private fun setLoadProgress(v: Int) {
        if (v >= 100 && progress.alpha == 0f) return
        if (v >= 100) {
            progress.animate().scaleX(1f).setDuration(150).withEndAction {
                progress.animate().alpha(0f).setDuration(250).start()
            }.start()
        } else {
            progress.alpha = 1f
            progress.animate().scaleX(maxOf(0.08f, v / 100f)).setDuration(200).setInterpolator(EASE_OUT).start()
        }
    }

    private fun showBar(show: Boolean, force: Boolean = false) {
        if (show) {
            if (barMode == 2 && !force) return
            setBarMode(0)
        } else {
            if (!prefs.autoHideBar) return
            if (barMode == 0 && cur?.isHome != true) setBarMode(1)
        }
    }

    internal fun setBarMode(m: Int) {
        if (m == barMode) return
        barMode = m
        when (m) {
            0 -> { glassBar.setMini(false); glassBar.setHidden(imeBottom > 0) }
            1 -> { refreshMiniText(); glassBar.setHidden(imeBottom > 0); glassBar.setMini(true) }
            2 -> glassBar.setHidden(true)
        }
        updateHandle()
    }

    private fun refreshMiniText() {
        val t = cur ?: return
        val h = t.host.removePrefix("www.").ifEmpty { "Поиск" }
        glassBar.setMiniText((if (t.incognito) "◐ " else "") + h, if (t.incognito) Color.WHITE else p.text)
    }

    /** Small pill at the bottom edge while the bar is swiped away: swipe up or tap to bring it back. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun updateHandle() {
        val want = barMode == 2 && imeBottom == 0 && videoFrame == null
        if (want && barHandle == null) {
            val h = FrameLayout(this)
            h.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat(); setColor(0x8C6E6E78.toInt()); setStroke(1, 0x66FFFFFF)
                }
            }, FrameLayout.LayoutParams(dp(72), dp(6), Gravity.CENTER))
            var y0 = 0f
            h.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y0 = e.rawY; true }
                    MotionEvent.ACTION_UP -> {
                        if (y0 - e.rawY > dp(12) || kotlin.math.abs(y0 - e.rawY) < dp(8)) setBarMode(0)
                        true
                    }
                    else -> true
                }
            }
            h.alpha = 0f
            root.addView(h, FrameLayout.LayoutParams(dp(150), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = navBottom })
            h.animate().alpha(1f).setStartDelay(180).setDuration(220).start()
            barHandle = h
        } else if (!want && barHandle != null) {
            val h = barHandle!!; barHandle = null
            h.animate().alpha(0f).setStartDelay(0).setDuration(150).withEndAction { root.removeView(h) }.start()
        } else barHandle?.let { (it.layoutParams as FrameLayout.LayoutParams).bottomMargin = navBottom; it.requestLayout() }
    }

    // ───────────────────────── tabs ─────────────────────────

    internal fun newTab(url: String?, incognito: Boolean, background: Boolean = false, parent: Long = -1L, blank: Boolean = false): Tab {
        val t = Tab(Tab.nextId(), incognito, url, if (parent == -2L) -1L else parent)
        t.noAutoLoad = blank
        val idx = cur?.let { tabs.indexOf(it) + 1 } ?: tabs.size
        tabs.add(idx.coerceIn(0, tabs.size), t)
        if (background) {
            toast(this, "Открыто в фоновой вкладке")
            updateChrome()
        } else selectTab(t)
        return t
    }

    internal fun selectTab(t: Tab) {
        val old = cur
        if (old != null && old !== t) {
            capture(old)
            old.web?.onPause()
        }
        cur = t
        t.lastActive = System.currentTimeMillis()
        holder.removeAllViews()
        val w = t.web ?: createWeb(t).also { t.web = it }
        (w.parent as? ViewGroup)?.removeView(w)
        holder.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        if (old != null && old !== t) {
            w.alpha = 0f; w.scaleX = 0.95f; w.scaleY = 0.95f
            w.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(EASE_OUT).start()
        }
        w.onResume()
        if (t.noAutoLoad) t.noAutoLoad = false
        else if (w.url == null) {
            val u = t.pendingUrl
            t.pendingUrl = null
            if (u.isNullOrEmpty()) loadHome(t) else load(t, u)
        }
        showBar(true, force = true)
        updateChrome()
        scheduleSample(320)
        glassBar.kick(900)
    }

    internal fun closeTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        t.web?.let { holder.removeView(it); it.stopLoading(); it.destroy() }
        t.web = null
        if (t.incognito && tabs.none { it.incognito }) wipeIncognito()
        if (cur === t) {
            cur = null
            val next = tabs.firstOrNull { it.id == t.parentId }
                ?: tabs.filter { it.incognito == t.incognito }.maxByOrNull { it.lastActive }
                ?: tabs.maxByOrNull { it.lastActive }
            if (next != null) selectTab(next) else newTab(null, false)
        } else updateChrome()
    }

    internal fun closeAll(incognito: Boolean) {
        tabs.filter { it.incognito == incognito }.forEach { it.web?.destroy(); it.web = null }
        tabs.removeAll { it.incognito == incognito }
        if (incognito) wipeIncognito()
        cur = null
        val next = tabs.maxByOrNull { it.lastActive }
        if (next != null) selectTab(next) else newTab(null, false)
    }

    private fun wipeIncognito() {
        main.postDelayed({
            if (tabs.any { it.incognito }) return@postDelayed
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                try { ProfileStore.getInstance().deleteProfile(INC) } catch (_: Exception) {}
            }
        }, 400)
    }

    private fun capture(t: Tab) {
        val w = t.web ?: return
        if (w.width <= 0 || w.height <= 0) return
        try {
            val s = 0.35f
            val b = Bitmap.createBitmap((w.width * s).toInt(), (w.height * s).toInt(), Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.scale(s, s)
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            t.thumb = b
        } catch (_: Throwable) {}
    }

    internal fun captureCurrent() { cur?.let { capture(it) } }

    private fun saveSession() {
        val arr = JSONArray()
        tabs.filter { !it.incognito }.forEach {
            val u = it.web?.url ?: it.pendingUrl ?: it.url
            arr.put(JSONObject().put("u", if (u.startsWith(Tab.HOME)) "" else u).put("t", it.title).put("a", cur === it))
        }
        prefs.session = arr.toString()
    }

    private fun restoreSession() {
        val s = prefs.session
        if (s.isEmpty()) return
        try {
            val arr = JSONArray(s)
            var active: Tab? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = Tab(Tab.nextId(), false, o.optString("u"))
                t.title = o.optString("t")
                tabs.add(t)
                if (o.optBoolean("a")) active = t
            }
            (active ?: tabs.lastOrNull())?.let { selectTab(it) }
        } catch (_: Exception) {}
    }

    // ───────────────────────── loading ─────────────────────────

    internal fun resolve(input: String): String {
        val s = input.trim()
        if (s.isEmpty()) return ""
        val lower = s.lowercase()
        if (Regex("^[a-z][a-z0-9+.-]*:").containsMatchIn(lower) && !lower.contains(' ') &&
            (lower.startsWith("http") || lower.startsWith("file:") || lower.startsWith("about:") ||
                    lower.startsWith("data:") || lower.startsWith("view-source:") || lower.startsWith("chrome:") || lower.startsWith("javascript:"))
        ) return s
        val looksHost = !s.contains(' ') && (
                Regex("^[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)+(:\\d+)?(/.*)?$").matches(s) ||
                        Regex("^localhost(:\\d+)?(/.*)?$").matches(lower) ||
                        Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(s))
        if (looksHost) {
            val tld = s.substringBefore('/').substringBefore(':').substringAfterLast('.')
            if (tld.length >= 2 && !tld.all { it.isDigit() } || s.first().isDigit() || lower.startsWith("localhost"))
                return if (lower.startsWith("localhost") || s.first().isDigit()) "http://$s" else "https://$s"
        }
        return prefs.searchUrl(s)
    }

    internal fun load(t: Tab, url: String) {
        val w = t.web
        if (w == null) { t.pendingUrl = url; t.url = url; return }
        if (url.isEmpty()) { loadHome(t); return }
        t.url = url
        w.loadUrl(url)
        updateChrome()
    }

    internal fun loadHome(t: Tab) {
        t.url = Tab.HOME
        t.title = ""
        t.web?.loadUrl(Tab.HOME + homeHash(t))
        updateChrome()
    }

    /** Theme passed in the URL so the very first frame of a new home page is already in the right colours. */
    private fun homeHash(t: Tab): String {
        fun hx(c: Int) = String.format("%06X", c and 0xFFFFFF)
        val pal = if (t.incognito) Palette.build(true, "graphite", prefs.glass) else p
        return "#d=${if (pal.dark) 1 else 0}&i=${if (t.incognito) 1 else 0}&a=${pal.aurora.joinToString(",") { hx(it) }}&c=${hx(pal.accent)}"
    }

    internal fun feedHome(t: Tab) {
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
        val pal = if (t.incognito) Palette.build(true, "graphite", prefs.glass) else p
        val aur = JSONArray().apply { pal.aurora.forEach { put(hex(it)) } }
        val o = JSONObject().put("dark", pal.dark).put("incognito", t.incognito)
            .put("aurora", aur).put("accent", hex(pal.accent))
            .put("top", (statusTop / density).toInt())
            .put("anim", prefs.homeAnim)
        if (!t.incognito && prefs.homeBgVer > 0 && java.io.File(filesDir, "home_bg.jpg").exists())
            o.put("bg", "https://polaris.local/home-bg?v=${prefs.homeBgVer}")
        t.thumb = null // home look may have changed; recapture when leaving the tab
        t.web?.evaluateJavascript("window.setData&&setData($o)", null)
    }

    private fun flushBlocked() {
        if (blockedUnsaved > 0) { prefs.blockedTotal = prefs.blockedTotal + blockedUnsaved; blockedUnsaved = 0 }
    }

    internal fun siteAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val set = prefs.sp.getStringSet("allowSites", emptySet())!!
        var h: String = host
        while (h.contains('.')) { if (set.contains(h)) return true; h = h.substringAfter('.') }
        return false
    }

    internal fun toggleSiteAllowed(host: String) {
        val set = HashSet(prefs.sp.getStringSet("allowSites", emptySet())!!)
        if (!set.remove(host)) set.add(host)
        prefs.sp.edit().putStringSet("allowSites", set).apply()
        cur?.web?.reload()
    }

    // ───────────────────────── WebView ─────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWeb(t: Tab): WebView {
        // Sites keep their own look; only the explicit "Тёмные сайты" switch darkens them.
        val darkWeb = prefs.forceDark
        val ctx = ContextThemeWrapper(this, if (darkWeb) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val w = WebView(ctx)
        val profiles = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        if (t.incognito && profiles) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(INC)
                WebViewCompat.setProfile(w, INC)
            } catch (_: Exception) {}
        }
        w.setBackgroundColor(if (darkWeb || p.dark || t.incognito) 0xFF0A0D15.toInt() else Color.WHITE)
        val s = w.settings
        if (mobileUa.isEmpty()) {
            mobileUa = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
            val chrome = Regex("Chrome/[\\d.]+").find(mobileUa)?.value ?: "Chrome/130.0.0.0"
            desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        }
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.mediaPlaybackRequiresUserGesture = true
        s.setGeolocationEnabled(true)
        if (t.incognito && !profiles) {
            s.cacheMode = WebSettings.LOAD_NO_CACHE
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        } else CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        applySettings(w, t)

        if (prefs.adblock && prefs.cosmetic && AdBlocker.genericCss.isNotEmpty() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            try {
                WebViewCompat.addDocumentStartJavaScript(w, AdBlocker.injectJs(AdBlocker.genericCss, "__pzg"), setOf("*"))
            } catch (_: Exception) {}
        }

        w.addJavascriptInterface(GMBridge(this, w, t), "PolarisGM")
        installScripts(w)

        w.setOnScrollChangeListener { _, _, y, _, oy ->
            if (cur?.web === w) { scheduleSample(140); glassBar.kick(450) }
            if (videoFrame != null || editPanel != null || findBar != null) return@setOnScrollChangeListener
            val dy = y - oy
            if (y < dp(20)) showBar(true)
            else if (dy > dp(6)) showBar(false)
            else if (dy < -dp(6)) showBar(true)
        }
        w.setOnLongClickListener { onLongPress(w) }
        w.setDownloadListener { url, ua, cd, mime, len -> onDownload(t, url, ua, cd, mime, len) }
        w.webViewClient = Client(t)
        w.webChromeClient = Chrome(t)
        return w
    }

    private fun bundle(): String = scriptBundle ?: UserScripts.bundle(scripts.all(), scriptToken, scriptCallbackKey).also { scriptBundle = it }

    private fun installScripts(w: WebView) {
        scriptHandlers.remove(w)?.let { try { it.remove() } catch (_: Exception) {} }
        val b = bundle()
        if (b.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try { scriptHandlers[w] = WebViewCompat.addDocumentStartJavaScript(w, b, setOf("*")) } catch (_: Exception) {}
    }

    /** Re-inject after install/remove/toggle; the current page is reloaded so the change is visible right away. */
    internal fun reloadScripts() {
        scriptBundle = null
        tabs.forEach { t -> t.web?.let { installScripts(it) } }
        cur?.let { if (!it.isHome) it.web?.reload() }
    }

    internal fun applySettings(w: WebView, t: Tab) {
        val s = w.settings
        s.javaScriptEnabled = prefs.javascript
        s.textZoom = prefs.textZoom
        s.userAgentString = if (t.desktop) desktopUa else mobileUa
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, prefs.forceDark)
        }
    }

    private fun injectCss(w: WebView, host: String?) {
        if (!prefs.adblock || !prefs.cosmetic || siteAllowed(host)) return
        if (AdBlocker.genericCss.isNotEmpty()) w.evaluateJavascript(AdBlocker.injectJs(AdBlocker.genericCss, "__pzg"), null)
        val site = AdBlocker.cssFor(host)
        if (site.isNotEmpty()) w.evaluateJavascript(AdBlocker.injectJs(site, "__pzs"), null)
    }

    inner class Client(private val t: Tab) : WebViewClient() {
        @Volatile var pageHost: String? = null

        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val url = req.url.toString()
            val scheme = req.url.scheme?.lowercase() ?: return false
            if (scheme == "polaris") {
                // internal links of our own home page; websites and their frames must not reach them
                if (!req.isForMainFrame || view.url?.startsWith(Tab.HOME) != true) return true
                if (req.url.host == "edit") { main.post { openEdit() }; return true }
                val q = req.url.getQueryParameter("q") ?: ""
                if (q.isNotBlank()) main.post { load(t, resolve(q)) }
                return true
            }
            if (scheme == "http" || scheme == "https") {
                if (req.isForMainFrame && UserScripts.isScriptUrl(req.url.path)) {
                    main.post { offerInstall(url) }
                    return true
                }
                if (req.isForMainFrame && VIDEO_EXT.matches(req.url.path ?: "")) {
                    PlayerActivity.open(this@MainActivity, url, t.url, t.title, Incog.cookie(t.incognito, url), view.settings.userAgentString, t.incognito)
                    return true
                }
                return false
            }
            if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript" || scheme == "file") return false
            // background tabs and frames nobody tapped can't launch apps or redirect the page
            if (t !== cur || (!req.isForMainFrame && !req.hasGesture())) return true
            openExternal(t, url, req.hasGesture())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            t.url = url
            pageHost = try { Uri.parse(url).host?.lowercase() } catch (_: Exception) { null }
            t.blocked = 0
            t.readerable = false
            t.cmds.clear()
            t.favicon = favicon
            t.progress = if (url.startsWith(Tab.HOME)) 100 else 5
            if (cur === t) updateChrome()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            injectCss(view, pageHost)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && url.startsWith("http")) {
                val b = bundle(); if (b.isNotEmpty()) view.evaluateJavascript(b, null)
            }
            if (cur === t) { scheduleSample(120); main.postDelayed({ if (cur === t) sampleTop() }, 700) }
        }

        override fun onPageFinished(view: WebView, url: String) {
            t.progress = 100
            if (cur === t) { scheduleSample(80); glassBar.kick(1200); main.postDelayed({ if (cur === t) sampleTop() }, 900) }
            if (url.startsWith(Tab.HOME)) {
                t.title = ""
                feedHome(t)
            } else {
                injectCss(view, pageHost)
                if (prefs.javascript) {
                    view.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=theme-color]');return m?m.content:''})()"
                    ) { r ->
                        val c = (JSONTokener(r).nextValue() as? String)?.trim().orEmpty()
                        if (t.themeColor == null) t.themeColor = try { if (c.startsWith("#") && (c.length == 7 || c.length == 4)) Color.parseColor(expandHex(c)) else null } catch (_: Exception) { null }
                        if (cur === t) applySystemBars()
                    }
                    view.evaluateJavascript("(function(){$readerableJs;try{return isProbablyReaderable(document)}catch(e){return false}})()") { r ->
                        t.readerable = r == "true"
                        if (cur === t) updateChrome()
                    }
                }
            }
            if (cur === t) updateChrome()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            t.url = url
            if (!isReload && !t.incognito && prefs.saveHistory && !url.startsWith(Tab.HOME) &&
                (url.startsWith("http://") || url.startsWith("https://"))
            ) db.addHistory(url, view.title ?: "")
            if (cur === t) updateChrome()
        }

        override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
            if (req.url.host == "polaris.local") {
                if (!t.url.startsWith(Tab.HOME)) return AdBlocker.empty() // the wallpaper is for the home page only
                val f = java.io.File(filesDir, "home_bg.jpg")
                return if (f.exists()) WebResourceResponse("image/jpeg", null, java.io.FileInputStream(f)).apply {
                    responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
                } else AdBlocker.empty()
            }
            if (!prefs.adblock || req.isForMainFrame) return null
            val ph = pageHost
            if (siteAllowed(ph)) return null
            if (AdBlocker.isAd(req.url, ph)) {
                t.blocked++
                blockedUnsaved++
                if (t.blocked == 1 || t.blocked % 10 == 0) main.post { if (cur === t) updateChrome() }
                return AdBlocker.empty()
            }
            return null
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            confirm(
                "Небезопасное соединение",
                "Сертификат сайта ${Uri.parse(error.url).host} не прошёл проверку. Злоумышленники могут перехватить данные.",
                "Всё равно открыть", "Назад", danger = true,
                onYes = { handler.proceed() }, onNo = { handler.cancel() }
            )
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            authDialog(host, handler)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val url = t.url
            holder.removeView(view)
            view.destroy()
            t.web = null
            t.pendingUrl = url
            if (cur === t) { cur = null; selectTab(t) }
            return true
        }
    }

    private fun expandHex(c: String) = if (c.length == 4) "#" + c.drop(1).map { "$it$it" }.joinToString("") else c

    inner class Chrome(private val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // the home page and its internal polaris:// taps are not real loads
            if (t.isHome || view.url?.startsWith(Tab.HOME) == true) { t.progress = 100; return }
            t.progress = newProgress
            if (cur === t) { setLoadProgress(newProgress); glassBar.kick(500) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            t.title = title ?: ""
            if (!t.incognito && prefs.saveHistory && !t.isHome && !title.isNullOrBlank()) db.updateHistoryTitle(view.url ?: t.url, title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) { t.favicon = icon }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            showVideo(view, callback)
        }

        override fun onHideCustomView() { hideVideo() }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture && prefs.blockPopups) {
                toast(this@MainActivity, "Всплывающее окно заблокировано")
                return false
            }
            val nt = newTab(null, t.incognito, parent = t.id, blank = true)
            val nw = nt.web ?: return false
            (resultMsg.obj as WebView.WebViewTransport).webView = nw
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.firstOrNull { it.web === window }?.let { closeTab(it) }
        }

        override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = cb
            return try {
                val i = params.createIntent().apply {
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(Intent.createChooser(i, "Выбор файла"), REQ_FILE)
                true
            } catch (_: Exception) { fileCallback = null; false }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            main.post { handleWebPermission(request) }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val host = Uri.parse(origin).host ?: origin
            confirm("Доступ к геопозиции", "$host хочет знать, где вы находитесь.", "Разрешить", "Запретить",
                onYes = {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                        callback.invoke(origin, true, false)
                    else {
                        pendingGeo = origin to callback
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_GEO)
                    }
                },
                onNo = { callback.invoke(origin, false, false) })
        }
    }

    // ───────────────────────── permissions & results ─────────────────────────

    private fun handleWebPermission(r: PermissionRequest) {
        val wants = r.resources.toList()
        val grantable = wants.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (grantable.isEmpty()) { r.deny(); return }
        if (grantable.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }) { r.grant(grantable.toTypedArray()); return }
        val what = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in grantable) add("камере")
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) add("микрофону")
        }.joinToString(" и ")
        confirm("Доступ к $what", "${r.origin.host} запрашивает доступ к $what.", "Разрешить", "Запретить",
            onYes = {
                val need = buildList {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in grantable) add(Manifest.permission.CAMERA)
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) add(Manifest.permission.RECORD_AUDIO)
                }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (need.isEmpty()) r.grant(grantable.toTypedArray())
                else { pendingPermission = r; requestPermissions(need.toTypedArray(), REQ_MEDIA) }
            },
            onNo = { r.deny() })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }
        when (code) {
            REQ_MEDIA -> pendingPermission?.let { r ->
                if (ok) r.grant(r.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }.toTypedArray()) else r.deny()
                pendingPermission = null
            }
            REQ_GEO -> pendingGeo?.let { (o, cb) -> cb.invoke(o, res.any { it == PackageManager.PERMISSION_GRANTED }, false); pendingGeo = null }
            REQ_LOC -> checkTheme()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FILE) {
            val cb = fileCallback ?: return
            fileCallback = null
            if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
            val clip = data.clipData
            val uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri }
            else data.data?.let { arrayOf(it) }
            cb.onReceiveValue(uris)
        }
    }

    // ───────────────────────── external links ─────────────────────────

    private fun openExternal(t: Tab, url: String, gesture: Boolean) {
        val intent = try {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE); component = null; selector = null
            } else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } catch (_: Exception) { null } ?: return
        val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { isWebUrl(it) }
        // Android 11+ hides most installed apps from us (package visibility), so a bank app
        // registered for its own scheme (bank100000000008://, sbp, alfabank://…) won't resolve.
        // Just try to start it and fall back only if the system really has no handler.
        val handler = packageManager.resolveActivity(intent, 0)
        val missing = {
            if (fallback != null) load(t, fallback)
            else if (intent.`package` != null) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}"))) } catch (_: Exception) {}
            } else toast(this, "Нет приложения, чтобы открыть ссылку")
        }
        val go = {
            try { startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) { missing() }
            catch (_: Exception) { toast(this, "Не удалось открыть") }
        }
        val name = handler?.loadLabel(packageManager)?.toString()
        if (gesture) go()
        else confirm("Открыть приложение?", if (name != null) "Сайт хочет открыть «$name»." else "Сайт хочет открыть внешнее приложение.",
            "Открыть", "Отмена", onYes = go)
    }

    // ───────────────────────── long press ─────────────────────────

    private fun onLongPress(w: WebView): Boolean {
        val r = w.hitTestResult
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> linkMenu(extra, null)
            WebView.HitTestResult.IMAGE_TYPE -> linkMenu(null, extra)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = Handler(Looper.getMainLooper()) { m ->
                    linkMenu(m.data.getString("url"), m.data.getString("src") ?: extra); true
                }.obtainMessage()
                w.requestFocusNodeHref(msg)
            }
            else -> return false
        }
        return true
    }

    private fun linkMenu(link: String?, image: String?) {
        val t = cur ?: return
        val s = GlassSheet(this)
        s.content.addView(label(this, link ?: image ?: "", 13f, p.dim).apply {
            maxLines = 2; setPadding(dp(10), 0, dp(10), dp(8))
        })
        if (link != null) {
            s.row(Ic.ADD, "Открыть в новой вкладке") { newTab(link, t.incognito, background = true, parent = t.id); s.dismiss() }
            if (!t.incognito) s.row(Ic.INCOGNITO, "Открыть в инкогнито") { newTab(link, true); s.dismiss() }
            if (VIDEO_EXT.matches(Uri.parse(link).path ?: "")) s.row(Ic.PLAY, "Открыть в плеере Polaris") {
                PlayerActivity.open(this, link, t.url, "", Incog.cookie(t.incognito, link), t.web?.settings?.userAgentString, t.incognito); s.dismiss()
            }
            s.row(Ic.COPY, "Копировать ссылку") { copy(link); s.dismiss() }
            s.row(Ic.SHARE, "Поделиться ссылкой") { share(link, null); s.dismiss() }
            s.row(Ic.DOWNLOAD, "Скачать по ссылке") { startDownload(t, link, null, null, null); s.dismiss() }
        }
        if (image != null && !image.startsWith("data:")) {
            s.row(Ic.GLOBE, "Открыть изображение") { newTab(image, t.incognito, parent = t.id); s.dismiss() }
            s.row(Ic.DOWNLOAD, "Сохранить изображение") { startDownload(t, image, null, null, null); s.dismiss() }
            s.row(Ic.COPY, "Копировать адрес изображения") { copy(image); s.dismiss() }
        }
        s.show()
    }

    // ───────────────────────── downloads ─────────────────────────

    private fun onDownload(t: Tab, url: String, ua: String?, cd: String?, mime: String?, len: Long) {
        if (UserScripts.isScriptUrl(android.net.Uri.parse(url).path) || UserScripts.isScriptUrl(URLUtil.guessFileName(url, cd, mime))) {
            offerInstall(url); return
        }
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast(this, "Этот файл создаётся страницей и не может быть скачан напрямую")
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        val size = if (len > 0) android.text.format.Formatter.formatShortFileSize(this, len) else "размер неизвестен"
        val s = GlassSheet(this).title("Скачать файл?")
        s.content.addView(label(this, "$name\n$size · ${Uri.parse(url).host}", 14f, p.dim).apply {
            maxLines = 4; setPadding(dp(10), 0, dp(10), dp(10))
        })
        val isVideo = mime?.startsWith("video/") == true || VIDEO_EXT.matches(Uri.parse(url).path ?: "")
        if (isVideo) s.row(Ic.PLAY, "Смотреть в плеере Polaris") {
            PlayerActivity.open(this, url, t.url, name, Incog.cookie(t.incognito, url), ua, t.incognito); s.dismiss()
        }
        s.row(Ic.DOWNLOAD, "Скачать", color = p.accent) { startDownload(t, url, ua, cd, mime); s.dismiss() }
        s.show()
    }

    internal fun startDownload(t: Tab, url: String, ua: String?, cd: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val dm = getSystemService(android.app.DownloadManager::class.java)
            val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name)
                setDescription(Uri.parse(url).host)
                setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                Incog.cookie(t.incognito, url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", ua ?: t.web?.settings?.userAgentString ?: mobileUa)
                if (t.url.startsWith("http")) addRequestHeader("Referer", t.url)
                if (mime != null) setMimeType(mime)
            }
            val id = dm.enqueue(req)
            if (!t.incognito) db.addDownload(id, name, url, mime ?: "")
            toast(this, "Загрузка: $name")
        } catch (e: Exception) {
            toast(this, "Не удалось начать загрузку")
        }
    }

    // ───────────────────────── reader ─────────────────────────

    internal fun openReader() {
        val t = cur ?: return
        val w = t.web ?: return
        if (t.isHome) return
        val js = "(function(){$readabilityJs;try{var d=document.cloneNode(true);var r=new Readability(d).parse();" +
                "return r?JSON.stringify({t:r.title,b:r.byline,c:r.content,s:r.siteName,l:r.length}):''}catch(e){return ''}})()"
        w.evaluateJavascript(js) { res ->
            val s = (try { JSONTokener(res).nextValue() } catch (_: Exception) { null }) as? String
            if (s.isNullOrEmpty()) { toast(this, "Не удалось выделить текст статьи"); return@evaluateJavascript }
            ReaderActivity.pending = s
            ReaderActivity.pendingUrl = t.url
            ReaderActivity.pendingIncognito = t.incognito
            startActivity(Intent(this, ReaderActivity::class.java))
        }
    }

    // ───────────────────────── fullscreen video ─────────────────────────

    private fun showVideo(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (videoFrame != null) { cb.onCustomViewHidden(); return }
        val f = VideoFrame(this)
        f.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        videoFrame = f
        videoCallback = cb
        root.addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
        glassBar.hide()
        f.alpha = 0f; f.animate().alpha(1f).setDuration(200).start()
        buildVideoControls(f)
        f.bringIndicatorToFront()
        f.onAnyTouch = { flashControls() }
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speedIdx = 0
        // orient by video aspect
        main.postDelayed({
            cur?.web?.evaluateJavascript(
                "(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;var v=e&&(e.tagName=='VIDEO'?e:e.querySelector('video'));" +
                        "if(!v){var a=document.querySelectorAll('video');for(var i=0;i<a.length;i++){if(!a[i].paused){v=a[i];break}}if(!v)v=a[0]}" +
                        "return v?(v.videoWidth+'x'+v.videoHeight):''})()"
            ) { r ->
                val s = (JSONTokener(r).nextValue() as? String).orEmpty()
                val parts = s.split('x').mapNotNull { it.toIntOrNull() }
                if (parts.size == 2 && parts[0] > 0 && parts[1] > 0) {
                    videoSize = Rational(parts[0], parts[1])
                    requestedOrientation = if (parts[0] >= parts[1]) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                updatePipParams(true)
            }
        }, 250)
        layoutInsets()
    }

    private fun buildVideoControls(f: VideoFrame) {
        val pd = Palette.DARK
        val box = hbox(this).apply {
            background = glass(this@MainActivity, 24f, pd, tint = 0x99101420.toInt())
            setPadding(dp(4), dp(2), dp(4), dp(2))
            elevation = dp(4).toFloat()
        }
        val speedLbl = label(this, "1×", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = ripple(this@MainActivity, null, 20f)
        }
        speedLbl.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            val sp = speeds[speedIdx]
            speedLbl.text = fmtSpeed(sp)
            setVideoSpeed(sp)
            f.show(VideoFrame.SPEED, fmtSpeed(sp))
            main.postDelayed({ f.indicator.visibility = View.GONE }, 700)
        }
        box.addView(speedLbl, lp(WRAP, dp(40)))
        box.addView(iconButton(this, Ic.ROTATE, 40, 20, Color.WHITE) {
            requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        })
        box.addView(iconButton(this, Ic.PIP, 40, 20, Color.WHITE) { enterPip() })
        box.addView(iconButton(this, Ic.CLOSE, 40, 20, Color.WHITE) { videoCallback?.onCustomViewHidden() ?: hideVideo() })
        f.addView(box, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        videoControls = box
        flashControls()
    }

    private val hideControls = Runnable { videoControls?.animate()?.alpha(0f)?.setDuration(300)?.start() }

    private fun flashControls() {
        val v = videoControls ?: return
        v.animate().alpha(1f).setDuration(150).start()
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, 3000)
    }

    private fun fmtSpeed(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

    private fun setVideoSpeed(s: Float) {
        cur?.web?.evaluateJavascript(
            "(function(r){function a(d){d.querySelectorAll('video,audio').forEach(function(v){v.playbackRate=r});" +
                    "d.querySelectorAll('iframe').forEach(function(f){try{a(f.contentDocument)}catch(e){}})}a(document)})($s)", null
        )
    }

    internal fun hideVideo() {
        val f = videoFrame ?: return
        main.removeCallbacks(hideControls)
        f.resetBrightness()
        root.removeView(f)
        f.removeAllViews()
        videoFrame = null
        try { glassBar.show() } catch (_: Exception) {}
        videoControls = null
        videoCallback = null
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        updatePipParams(false)
        layoutInsets()
    }

    private fun pipParams(auto: Boolean): PictureInPictureParams {
        val r = videoSize.toFloat()
        val ratio = when {
            r > 2.39f -> Rational(239, 100)
            r < 1 / 2.39f -> Rational(100, 239)
            else -> videoSize
        }
        val b = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(auto).setSeamlessResizeEnabled(true)
        return b.build()
    }

    private fun updatePipParams(auto: Boolean) {
        try { setPictureInPictureParams(pipParams(auto)) } catch (_: Exception) {}
    }

    private fun enterPip() {
        if (videoFrame == null) return
        try { enterPictureInPictureMode(pipParams(true)) } catch (_: Exception) { toast(this, "Картинка в картинке недоступна") }
    }

    // ───────────────────────── address editing ─────────────────────────

    internal fun openEdit(prefill: String? = null) {
        if (editPanel != null) return
        val t = cur ?: return
        val inc = t.incognito
        val pp = if (inc) Palette.DARK else p
        val panel = FrameLayout(this).apply {
            val canBlur = android.os.Build.VERSION.SDK_INT >= 31
            setBackgroundColor(if (inc) (if (canBlur) 0xB3101318.toInt() else 0xF2101318.toInt())
                else (pp.bg and 0x00FFFFFF) or (if (canBlur) 0x8C000000.toInt() else 0xF0000000.toInt()))
            isClickable = true
            setPadding(0, statusTop, 0, maxOf(navBottom, imeBottom))
        }
        val col = vbox(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = vbox(this).apply { gravity = Gravity.BOTTOM; setPadding(dp(10), dp(10), dp(10), dp(6)) }
        scroll.addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
        col.addView(scroll, lp(MATCH, 0, 1f))

        // quick actions for current page
        if (!t.isHome) {
            val quick = hbox(this).apply { setPadding(dp(12), 0, dp(12), dp(6)) }
            fun chip(ic: String, txt: String, on: () -> Unit) = hbox(this).apply {
                background = ripple(this@MainActivity, GlassDrawable(dp(18).toFloat(), if (pp.dark) 0x26FFFFFF else 0x14000000, pp.dark, density), 18f)
                setPadding(dp(14), dp(9), dp(16), dp(9))
                addView(ImageView(this@MainActivity).apply { setImageDrawable(icon(this@MainActivity, ic, pp.text)) }, lp(dp(18), dp(18)).apply { marginEnd = dp(8) })
                addView(label(this@MainActivity, txt, 13.5f, pp.text))
                setOnClickListener { on() }
            }
            quick.addView(chip(Ic.COPY, "Копировать") { copyUrl(); closeEdit() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
            quick.addView(chip(Ic.SHARE, "Поделиться") { share(t.url, t.title); closeEdit() }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
            if (!t.incognito) quick.addView(chip(if (db.isBookmarked(t.url)) Ic.STAR else Ic.STAR_O, "Закладка") { toggleBookmark(); closeEdit() })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(quick) })
        }

        val pill = hbox(this).apply {
            background = glass(this@MainActivity, 28f, pp, strong = true)
            setPadding(dp(16), dp(3), dp(4), dp(3))
        }
        pill.addView(ImageView(this).apply { setImageDrawable(icon(this@MainActivity, Ic.SEARCH, pp.accent)) }, lp(dp(21), dp(21)))
        val input = EditText(this).apply {
            setText(prefill ?: if (t.isHome) "" else t.url)
            setTextColor(pp.text); setHintTextColor(pp.dim)
            hint = "Поиск"
            background = null
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    (if (inc) EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING else 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            selectAll()
            setSelectAllOnFocus(true)
        }
        pill.addView(input, lp(0, dp(48), 1f).apply { marginStart = dp(8) })
        pill.addView(iconButton(this, Ic.CLOSE, 40, 18, pp.dim) {
            if (input.text.isEmpty()) closeEdit() else input.setText("")
        })
        col.addView(pill, lp(MATCH, WRAP).apply { setMargins(dp(10), dp(4), dp(10), dp(10)) })
        panel.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        fun go(text: String) {
            val u = resolve(text)
            closeEdit()
            if (u.isNotEmpty()) load(t, u)
        }
        input.setOnEditorActionListener { _, _, _ -> go(input.text.toString()); true }

        var reqSeq = 0
        fun refresh(q: String) {
            list.removeAllViews()
            if (q.isBlank()) {
                val cm = getSystemService(ClipboardManager::class.java)
                val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clip.isNullOrEmpty() && clip.length < 2000) suggestionRow(list, pp, Ic.COPY, "Вставить и перейти", clip.take(80)) { go(clip) }
                return
            }
            db.suggest(q).forEach { e ->
                suggestionRow(list, pp, if (e.ts == -1L) Ic.STAR else Ic.HISTORY, e.title.ifBlank { e.url }, e.url) { go(e.url) }
            }
            suggestionRow(list, pp, Ic.SEARCH, q, "Искать в ${Prefs.ENGINES.first { it.first == prefs.engine }.second}") { go(prefs.searchUrl(q)) }
            if (!inc) {
                val my = ++reqSeq
                thread {
                    val sugg = fetchSuggestions(q)
                    main.post {
                        if (my != reqSeq || editPanel == null) return@post
                        sugg.take(5).reversed().forEach { s ->
                            suggestionRow(list, pp, Ic.SEARCH, s, null, top = true) { go(prefs.searchUrl(s)) }
                        }
                    }
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(s.toString()) }
        })
        refresh(if (t.isHome) "" else "")

        root.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
        editPanel = panel
        glassBar.hide()
        panel.alpha = 0f
        panel.animate().alpha(1f).setDuration(200).start()
        pill.translationY = dp(60).toFloat(); pill.scaleX = 0.92f
        pill.animate().translationY(0f).scaleX(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        blurPage(true)
        input.requestFocus()
        main.postDelayed({
            getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    private fun suggestionRow(list: LinearLayout, pp: Palette, ic: String, title: String, sub: String?, top: Boolean = false, on: () -> Unit) {
        val r = hbox(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = ripple(this@MainActivity, null, 18f, pp.ripple)
            setOnClickListener { on() }
            pressable(0.97f)
        }
        r.addView(ImageView(this).apply { setImageDrawable(icon(this@MainActivity, ic, if (ic == Ic.SEARCH) pp.accent else pp.dim)) }, lp(dp(20), dp(20)).apply { marginEnd = dp(16) })
        val c = vbox(this)
        c.addView(label(this, title, 15.5f, pp.text).apply { maxLines = 1 })
        if (sub != null) c.addView(label(this, sub, 12.5f, pp.dim).apply { maxLines = 1 })
        r.addView(c, lp(0, WRAP, 1f))
        if (top) list.addView(r, 0, lp(MATCH, WRAP)) else list.addView(r, lp(MATCH, WRAP))
        r.animateIn(if (top) 0 else list.childCount, 10f)
    }

    /** Frosts the page behind the address editor (Android 12+). */
    private fun blurPage(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val target = holder
        if (!on) { target.setRenderEffect(null); return }
        android.animation.ValueAnimator.ofFloat(1f, 28f).apply {
            duration = 260
            addUpdateListener {
                val r = (it.animatedValue as Float) * density
                if (editPanel != null) target.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP))
            }
            start()
        }
    }

    private fun fetchSuggestions(q: String): List<String> = try {
        val e = URLEncoder.encode(q, "UTF-8")
        val u = when (prefs.engine) {
            "ddg", "brave", "startpage" -> "https://duckduckgo.com/ac/?type=list&q=$e"
            "bing" -> "https://api.bing.com/osjson.aspx?query=$e"
            else -> "https://suggestqueries.google.com/complete/search?client=firefox&hl=ru&q=$e"
        }
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        c.setRequestProperty("User-Agent", mobileUa)
        val body = c.inputStream.readBytes().toString(Charsets.UTF_8)
        val arr = JSONArray(body).getJSONArray(1)
        List(arr.length()) { arr.getString(it) }.filter { it != q }
    } catch (_: Exception) { emptyList() }

    internal fun closeEdit() {
        val panel = editPanel ?: return
        editPanel = null
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.windowToken, 0)
        blurPage(false)
        panel.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(panel) }.start()
        try { glassBar.show() } catch (_: Exception) {}
        barMode = 0
        glassBar.setMini(false)
        glassBar.setHidden(false)
        updateHandle()
    }

    // ───────────────────────── find in page ─────────────────────────

    internal fun openFind() {
        val w = cur?.web ?: return
        if (findBar != null) return
        glassBar.hide()
        val fb = hbox(this).apply {
            background = glass(this@MainActivity, 28f, p, strong = true)
            elevation = dp(8).toFloat()
            setPadding(dp(14), dp(4), dp(4), dp(4))
        }
        val input = EditText(this).apply {
            hint = "Найти на странице"; background = null; setSingleLine()
            setTextColor(p.text); setHintTextColor(p.dim)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val count = label(this, "", 13f, p.dim).apply { setPadding(dp(6), 0, dp(6), 0) }
        fb.addView(input, lp(0, dp(44), 1f))
        fb.addView(count)
        fb.addView(iconButton(this, Ic.UP, 40, 22) { w.findNext(false) })
        fb.addView(iconButton(this, Ic.DOWN, 40, 22) { w.findNext(true) })
        fb.addView(iconButton(this, Ic.CLOSE, 40, 20) { closeFind() })
        w.setFindListener { active, num, done -> if (done) count.text = if (num == 0) "0/0" else "${active + 1}/$num" }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { w.findAllAsync(s.toString()); if (s.isNullOrEmpty()) count.text = "" }
        })
        input.setOnEditorActionListener { _, _, _ -> w.findNext(true); true }
        barWrap.addView(fb, FrameLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(6), dp(10), dp(8)) })
        findBar = fb
        showBar(true, force = true)
        input.requestFocus()
        main.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }, 80)
    }

    internal fun closeFind() {
        val fb = findBar ?: return
        findBar = null
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(fb.windowToken, 0)
        cur?.web?.clearMatches()
        barWrap.removeView(fb)
        try { glassBar.show() } catch (_: Exception) {}
    }

    // ───────────────────────── helpers ─────────────────────────

    internal fun copy(s: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("url", s))
    }

    internal fun copyUrl() {
        val t = cur ?: return
        if (t.isHome) return
        copy(t.url); toast(this, "Ссылка скопирована")
    }

    internal fun share(url: String, title: String?) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url); title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }, "Поделиться"))
    }

    internal fun toggleBookmark() {
        val t = cur ?: return
        if (t.isHome) return
        if (db.isBookmarked(t.url)) { db.removeBookmark(t.url); toast(this, "Удалено из закладок") }
        else { db.addBookmark(t.url, t.displayTitle); toast(this, "Добавлено в закладки") }
    }

    internal fun confirm(
        title: String, msg: String, yes: String, no: String, danger: Boolean = false,
        onYes: () -> Unit, onNo: () -> Unit = {}
    ) {
        val s = GlassSheet(this).title(title)
        var answered = false
        s.content.addView(label(this, msg, 14.5f, p.dim).apply { maxLines = 8; setPadding(dp(8), 0, dp(8), dp(14)) })
        val row = hbox(this)
        fun btn(text: String, primary: Boolean, on: () -> Unit) = TextView(this).apply {
            this.text = text; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(if (primary) Color.WHITE else p.text)
            background = ripple(this@MainActivity, rounded(this@MainActivity,
                if (primary) (if (danger) 0xFFE5484D.toInt() else p.accent) else p.field, 18f), 18f)
            setOnClickListener { answered = true; s.dismiss(); on() }
        }
        row.addView(btn(no, false, onNo), lp(0, dp(48), 1f).apply { marginEnd = dp(8) })
        row.addView(btn(yes, true, onYes), lp(0, dp(48), 1f))
        s.content.addView(row, lp(MATCH, WRAP))
        s.setOnDismissListener { if (!answered) onNo() }
        s.show()
    }

    private fun authDialog(host: String, h: HttpAuthHandler) {
        val s = GlassSheet(this).title("Вход на $host")
        fun field(hint: String, pass: Boolean) = EditText(this).apply {
            this.hint = hint; setSingleLine(); setTextColor(p.text); setHintTextColor(p.dim)
            background = rounded(this@MainActivity, p.field, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (pass) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val u = field("Логин", false); val pw = field("Пароль", true)
        s.content.addView(u, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        s.content.addView(pw, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
        var done = false
        s.row(Ic.LOCK, "Войти", color = p.accent) { done = true; h.proceed(u.text.toString(), pw.text.toString()); s.dismiss() }
        s.setOnDismissListener { if (!done) h.cancel() }
        s.show()
    }

    private fun showHistoryStack() {
        val w = cur?.web ?: return
        val list = w.copyBackForwardList()
        if (list.size <= 1) return
        val s = GlassSheet(this).title("История вкладки")
        for (i in list.currentIndex - 1 downTo maxOf(0, list.currentIndex - 12)) {
            val item = list.getItemAtIndex(i)
            val steps = i - list.currentIndex
            if (item.url.startsWith(Tab.HOME)) s.row(Ic.HOME, "Новая вкладка") { w.goBackOrForward(steps); s.dismiss() }
            else s.row(Ic.HISTORY, item.title.ifBlank { item.url }, item.url) { w.goBackOrForward(steps); s.dismiss() }
        }
        s.show()
    }

    internal fun showSiteInfo() {
        val t = cur ?: return
        if (t.isHome) { openEdit(); return }
        val s = GlassSheet(this).title(t.host)
        val secure = t.url.startsWith("https://")
        s.row(if (secure) Ic.LOCK else Ic.GLOBE, if (secure) "Соединение защищено" else "Соединение не защищено",
            if (secure) "Данные шифруются (HTTPS)" else "Не вводите здесь пароли и карты") {}
        val allowed = siteAllowed(t.host)
        s.row(Ic.SHIELD, if (allowed || !prefs.adblock) "Блокировка выключена на этом сайте" else "Заблокировано на странице: ${t.blocked}",
            if (!prefs.adblock) "Включите в настройках" else if (allowed) "Нажмите, чтобы включить" else "Нажмите, чтобы выключить для ${t.host}",
            color = if (!allowed && prefs.adblock) 0xFF34C77B.toInt() else p.text) {
            if (prefs.adblock) toggleSiteAllowed(t.host); s.dismiss()
        }
        s.show()
    }
}
