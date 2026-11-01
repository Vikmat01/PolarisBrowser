package com.polaris.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Html
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

class ReaderActivity : Activity() {
    companion object {
        var pending: String? = null
        var pendingUrl: String = ""
        var pendingIncognito = false
    }

    private lateinit var prefs: Prefs
    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var data: JSONObject
    private var url = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val raw = pending ?: run { finish(); return }
        data = JSONObject(raw)
        url = pendingUrl
        window.setDecorFitsSystemWindows(false)

        val root = FrameLayout(this)
        web = WebView(this)
        if (pendingIncognito) { Incog.attach(web); Incog.secure(window, true) }
        web.settings.javaScriptEnabled = false
        web.settings.textZoom = 100
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                startActivity(Intent(Intent.ACTION_VIEW, r.url, this@ReaderActivity, MainActivity::class.java))
                finish()
                return true
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bar = hbox(this)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), dp(6), dp(10), dp(8))
        })
        root.setOnApplyWindowInsetsListener { _, ins ->
            val nb = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            val st = ins.getInsets(WindowInsets.Type.statusBars()).top
            (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = nb + dp(8)
            bar.requestLayout()
            web.setPadding(0, st, 0, 0)
            ins
        }
        setContentView(root)
        render()
    }

    private fun themeColors(): Triple<String, String, String> { // bg, text, dim
        val t = prefs.readerTheme
        val dark = when (t) { "dark" -> true; "light", "sepia" -> false; else -> Palette.cur.dark }
        return when {
            t == "sepia" -> Triple("#F4ECD8", "#3B2F1E", "#7A6A50")
            dark -> Triple("#111318", "#E6E6E6", "#9CA3AF")
            else -> Triple("#FFFFFF", "#1A1A1A", "#6B7280")
        }
    }

    private fun render() {
        val (bg, fg, dim) = themeColors()
        val serif = if (prefs.readerSerif) "Georgia, 'Noto Serif', serif" else "system-ui, Roboto, sans-serif"
        val title = Html.escapeHtml(data.optString("t"))
        val by = listOf(data.optString("s"), data.optString("b")).filter { it.isNotBlank() && it != "null" }.joinToString(" · ")
        val minutes = maxOf(1, data.optInt("l") / 1300)
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html{background:$bg}
              body{margin:0 auto;max-width:720px;padding:28px 22px 140px;color:$fg;background:$bg;
                   font-family:$serif;font-size:${prefs.readerSize}px;line-height:1.65;word-wrap:break-word}
              h1.t{font-family:system-ui,Roboto,sans-serif;font-size:1.55em;line-height:1.25;margin:8px 0 10px;font-weight:700}
              .m{color:$dim;font-family:system-ui,Roboto,sans-serif;font-size:.72em;margin-bottom:26px}
              img,video,figure,iframe{max-width:100%!important;height:auto!important;border-radius:12px}
              figure{margin:1.2em 0} figcaption{color:$dim;font-size:.8em}
              a{color:#4C7DFF} pre,code{white-space:pre-wrap;font-size:.85em;background:rgba(127,127,127,.12);border-radius:6px;padding:2px 5px}
              blockquote{margin:1em 0;padding-left:1em;border-left:3px solid rgba(127,127,127,.4);color:$dim}
              table{display:block;overflow-x:auto;border-collapse:collapse}
            </style></head><body>
            <h1 class="t">$title</h1>
            <div class="m">${Html.escapeHtml(by)}${if (by.isNotEmpty()) " · " else ""}≈ $minutes мин чтения</div>
            ${data.optString("c")}
            </body></html>
        """.trimIndent()
        web.setBackgroundColor(Color.parseColor(bg))
        window.decorView.setBackgroundColor(Color.parseColor(bg))
        web.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
        val darkBg = Color.parseColor(bg).let { (Color.red(it) + Color.green(it) + Color.blue(it)) / 3 < 128 }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkBg; isAppearanceLightNavigationBars = !darkBg
        }
        buildBar(darkBg)
    }

    private fun buildBar(darkBg: Boolean) {
        val p = if (darkBg) Palette.DARK else Palette.LIGHT
        bar.removeAllViews()
        bar.background = glass(this, 28f, p, strong = true)
        bar.elevation = dp(8).toFloat()
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.addView(iconButton(this, Ic.BACK, color = p.text) { finish() })
        bar.addView(label(this, Uri.parse(url).host?.removePrefix("www.") ?: "", 14.5f, p.dim).apply {
            maxLines = 1; gravity = Gravity.CENTER
        }, lp(0, WRAP, 1f))
        bar.addView(iconButton(this, Ic.TEXT, color = p.text) { settings() })
        bar.addView(iconButton(this, Ic.SHARE, color = p.text) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Поделиться"))
        })
    }

    private fun settings() {
        val p = Palette.cur
        val s = GlassSheet(this).title("Вид текста")
        val sizeRow = hbox(this).apply { setPadding(dp(6), 0, dp(6), dp(10)) }
        val sizeLbl = label(this, "${prefs.readerSize}", 16f, p.text, true).apply { gravity = Gravity.CENTER }
        fun btn(t: String, on: () -> Unit) = TextView(this).apply {
            text = t; gravity = Gravity.CENTER; setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            background = ripple(this@ReaderActivity, rounded(this@ReaderActivity, p.field, 18f), 18f)
            setOnClickListener { on() }
        }
        sizeRow.addView(btn("A−") { prefs.readerSize = (prefs.readerSize - 1).coerceAtLeast(13); sizeLbl.text = "${prefs.readerSize}"; render() }, lp(0, dp(46), 1f))
        sizeRow.addView(sizeLbl, lp(dp(60), WRAP))
        sizeRow.addView(btn("A+") { prefs.readerSize = (prefs.readerSize + 1).coerceAtMost(30); sizeLbl.text = "${prefs.readerSize}"; render() }, lp(0, dp(46), 1f))
        s.content.addView(sizeRow)

        val themes = hbox(this).apply { setPadding(dp(6), 0, dp(6), dp(10)) }
        listOf(Triple("auto", "Авто", "#8899AA"), Triple("light", "Светлая", "#FFFFFF"),
            Triple("sepia", "Сепия", "#F4ECD8"), Triple("dark", "Тёмная", "#111318")).forEach { (k, n, c) ->
            val sel = prefs.readerTheme == k
            themes.addView(TextView(this).apply {
                text = n; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val col = Color.parseColor(c)
                setTextColor(if (k == "dark" || k == "auto") Color.WHITE else Color.parseColor("#222222"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat(); setColor(col)
                    setStroke(dp(if (sel) 3 else 1), if (sel) p.accent else p.field)
                }
                setOnClickListener { prefs.readerTheme = k; s.dismiss(); render() }
            }, lp(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        s.content.addView(themes)
        s.row(Ic.TEXT, if (prefs.readerSerif) "Шрифт: с засечками" else "Шрифт: без засечек", "Нажмите, чтобы сменить") {
            prefs.readerSerif = !prefs.readerSerif; s.dismiss(); render()
        }
        s.show()
    }


    override fun onDestroy() {
        if (isFinishing) pending = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
