package com.polaris.browser

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Self-update from GitHub Releases: the latest release's tag is the version (v2.2 → 2.2),
 * its .apk asset is downloaded and handed to the system installer. The APK must be signed
 * with the same key, otherwise Android refuses to install it over the current app.
 */
object Updater {
    const val REPO = "Vikmat01/PolarisBrowser"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val ACTION_DONE = "com.polaris.browser.INSTALL_DONE"

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** 1 if a > b, -1 if a < b, 0 if equal ("2.10" > "2.9"). */
    fun compare(a: String, b: String): Int {
        val x = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun fetchLatest(): Release? = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "PolarisBrowser")
        if (c.responseCode != 200) null else {
            val o = JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
            val assets = o.optJSONArray("assets")
            var url = ""; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
            }
            if (url.isEmpty()) null
            else Release(o.optString("tag_name").removePrefix("v"), o.optString("body"), url, size, o.optString("html_url", RELEASES_PAGE))
        }
    } catch (_: Exception) { null }

    /** Background check on launch, at most every 12 hours; shows the sheet only when there is something new. */
    fun autoCheck(act: MainActivity) {
        val p = act.prefs
        if (!p.sp.getBoolean("autoUpdate", true)) return
        val last = p.sp.getLong("updateChecked", 0)
        if (System.currentTimeMillis() - last < 12 * 3600_000L) return
        thread {
            val r = fetchLatest()
            act.main.post {
                p.sp.edit().putLong("updateChecked", System.currentTimeMillis()).apply()
                if (r != null && compare(r.version, current(act)) > 0 && p.sp.getString("skipVersion", "") != r.version && !act.isFinishing)
                    offer(act, r)
            }
        }
    }

    /** Manual check from Settings: always reports the result. */
    fun manualCheck(act: Activity) {
        toast(act, "Проверяю обновления…")
        thread {
            val r = fetchLatest()
            act.runOnUiThread {
                when {
                    r == null -> toast(act, "Не удалось связаться с GitHub")
                    compare(r.version, current(act)) > 0 -> offer(act, r)
                    else -> toast(act, "У вас последняя версия ${current(act)}")
                }
            }
        }
    }

    fun offer(act: Activity, r: Release) {
        val p = Palette.cur
        val s = GlassSheet(act).title("Доступна версия ${r.version}")
        val sizeTxt = if (r.size > 0) android.text.format.Formatter.formatShortFileSize(act, r.size) else ""
        s.content.addView(label(act, "Сейчас установлена ${current(act)}" + if (sizeTxt.isNotEmpty()) " · $sizeTxt" else "", 13.5f, p.dim)
            .apply { setPadding(act.dp(8), 0, act.dp(8), act.dp(8)) })
        if (r.notes.isNotBlank()) {
            val notes = label(act, r.notes.replace(Regex("[#*`>]+ ?"), "").trim(), 14.5f, p.text).apply {
                maxLines = 40; setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(12))
            }
            s.content.addView(ScrollView(act).apply { addView(notes) }, lp(MATCH, WRAP))
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = android.view.View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
        }
        s.content.addView(bar, lp(MATCH, act.dp(6)).apply { setMargins(act.dp(8), 0, act.dp(8), act.dp(10)) })
        val row = hbox(act)
        fun btn(text: String, primary: Boolean, on: (TextView) -> Unit) = TextView(act).apply {
            this.text = text; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) p.onAccent else p.text)
            background = ripple(act, rounded(act, if (primary) p.accent else p.field, 22f), 22f)
            setOnClickListener { on(this) }
            pressable(0.95f)
        }
        row.addView(btn("Позже", false) {
            act.getSharedPreferences("polaris", 0).edit().putString("skipVersion", r.version).apply(); s.dismiss()
        }, lp(0, act.dp(48), 1f).apply { marginEnd = act.dp(8) })
        row.addView(btn("Обновить", true) { b ->
            if (!act.packageManager.canRequestPackageInstalls()) {
                toast(act, "Разрешите Polaris устанавливать приложения и нажмите «Обновить» ещё раз")
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                return@btn
            }
            b.isEnabled = false; b.text = "Загрузка…"; bar.visibility = android.view.View.VISIBLE
            download(act, r, { pct -> bar.progress = pct; b.text = "Загрузка $pct%" }) { file ->
                if (file == null) { b.isEnabled = true; b.text = "Повторить"; toast(act, "Не удалось скачать обновление"); return@download }
                b.text = "Установка…"
                install(act, file)
                s.dismiss()
            }
        }, lp(0, act.dp(48), 1f))
        s.content.addView(row, lp(MATCH, WRAP))
        s.show()
    }

    private fun download(act: Activity, r: Release, progress: (Int) -> Unit, done: (File?) -> Unit) {
        thread {
            val out = File(act.cacheDir, "update.apk")
            val ok = try {
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "PolarisBrowser")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { inp ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024); var n: Int; var got = 0L; var last = -1
                        while (inp.read(buf).also { n = it } > 0) {
                            o.write(buf, 0, n); got += n
                            if (total > 0) { val pct = (got * 100 / total).toInt(); if (pct != last) { last = pct; act.runOnUiThread { progress(pct) } } }
                        }
                    }
                }
                out.length() > 0
            } catch (_: Exception) { false }
            act.runOnUiThread { done(if (ok) out else null) }
        }
    }

    private fun install(act: Activity, apk: File) {
        try {
            val pi = act.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(act.packageName)
                if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                session.openWrite("polaris.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; session.fsync(o) }
                val app = act.applicationContext
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                try { c.startActivity(confirm) } catch (_: Exception) {}
                            }
                            PackageInstaller.STATUS_SUCCESS -> { try { app.unregisterReceiver(this) } catch (_: Exception) {} }
                            else -> {
                                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                                toast(c, "Установка не удалась: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка"))
                            }
                        }
                    }
                }, IntentFilter(ACTION_DONE), Context.RECEIVER_NOT_EXPORTED)
                val pending = PendingIntent.getBroadcast(app, id, Intent(ACTION_DONE).setPackage(act.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            toast(act, "Установка не удалась: ${e.message}")
        }
    }
}
