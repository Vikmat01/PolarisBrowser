package com.polaris.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

/** Loopback, LAN, link-local and carrier-NAT addresses: userscripts have no business talking to the user's own network. */
internal fun isPrivateAddress(a: InetAddress): Boolean {
    val b = a.address
    return a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress ||
        (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) ||
        (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64)
}

/** GM_* bridge. Every call carries the per-run token, which only the injected bootstrap knows. */
class GMBridge(private val act: MainActivity, private val web: WebView, private val tab: Tab) {
    private val sp = act.getSharedPreferences("gm_values", 0)
    private fun ok(t: String) = t == act.scriptToken

    @JavascriptInterface fun get(t: String, id: Long, k: String): String? = if (ok(t)) sp.getString("$id|$k", null) else null
    @JavascriptInterface fun set(t: String, id: Long, k: String, v: String) { if (ok(t)) sp.edit().putString("$id|$k", v).apply() }
    @JavascriptInterface fun del(t: String, id: Long, k: String) { if (ok(t)) sp.edit().remove("$id|$k").apply() }
    @JavascriptInterface fun list(t: String, id: Long): String {
        if (!ok(t)) return "[]"
        val p = "$id|"
        return JSONArray(sp.all.keys.filter { it.startsWith(p) }.map { it.removePrefix(p) }).toString()
    }
    @JavascriptInterface fun open(t: String, url: String) {
        if (!ok(t) || !(url.startsWith("http://") || url.startsWith("https://"))) return
        act.main.post { act.newTab(url, tab.incognito, background = true, parent = tab.id) }
    }
    @JavascriptInterface fun clip(t: String, text: String) {
        if (!ok(t)) return
        act.main.post { act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text)) }
    }
    @JavascriptInterface fun note(t: String, text: String) { if (ok(t)) act.main.post { toast(act, text) } }
    @JavascriptInterface fun cmd(t: String, name: String) {
        if (!ok(t)) return
        act.main.post { if (name !in tab.cmds) tab.cmds.add(name) }
    }
    @JavascriptInterface fun xhr(t: String, id: Int, req: String) {
        if (!ok(t)) return
        thread {
            val res = JSONObject()
            try {
                val o = JSONObject(req)
                val u = o.getString("url")
                var url = URL(u)
                var method = o.optString("method", "GET").uppercase()
                var data = if (o.isNull("data")) null else o.getString("data")
                var hops = 0
                var c: HttpURLConnection
                var code: Int
                while (true) {
                    // checked again on every redirect, so a public site can't bounce the request into the home network
                    if ((url.protocol != "http" && url.protocol != "https") || InetAddress.getAllByName(url.host).any { isPrivateAddress(it) })
                        throw IllegalArgumentException("blocked address")
                    c = url.openConnection() as HttpURLConnection
                    c.requestMethod = method
                    val to = o.optInt("timeout", 0); c.connectTimeout = if (to > 0) to else 20000; c.readTimeout = if (to > 0) to else 30000
                    c.instanceFollowRedirects = false
                    o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> c.setRequestProperty(k, h.getString(k)) } }
                    if (c.getRequestProperty("User-Agent") == null) c.setRequestProperty("User-Agent", web.settings.userAgentString)
                    data?.let { d -> c.doOutput = true; c.outputStream.use { it.write(d.toByteArray()) } }
                    code = c.responseCode
                    val next = c.getHeaderField("Location")
                    if (code !in setOf(301, 302, 303, 307, 308) || next == null) break
                    if (++hops > 5) throw IllegalArgumentException("too many redirects")
                    c.disconnect()
                    url = URL(url, next)
                    if (code == 303 || (code != 307 && code != 308 && method == "POST")) { method = "GET"; data = null }
                }
                val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val headers = c.headerFields.filterKeys { it != null }.entries.joinToString("\r\n") { "${it.key}: ${it.value.joinToString(", ")}" }
                res.put("status", code).put("statusText", c.responseMessage ?: "").put("responseText", body)
                    .put("finalUrl", c.url.toString()).put("responseHeaders", headers).put("readyState", 4)
            } catch (e: Exception) {
                res.put("error", e.message ?: "error").put("status", 0).put("readyState", 4)
            }
            val key = JSONObject.quote(act.scriptCallbackKey)
            act.main.post { web.evaluateJavascript("window.__pzXhrDone&&__pzXhrDone($key,$id,$res)", null) }
        }
    }
}

// ───────────────────────── install ─────────────────────────

fun MainActivity.offerInstall(url: String) {
    toast(this, "Загружаю скрипт…")
    thread {
        val src = UserScripts.fetch(url)
        val s = src?.let { UserScripts.parse(it, url) }
        main.post {
            if (s == null) { toast(this, "Это не похоже на юзерскрипт или стиль"); return@post }
            showInstall(s)
        }
    }
}

fun MainActivity.showInstall(s: UScript) {
    val p = Palette.cur
    val existing = scripts.find(s.name, s.namespace)
    val what = if (s.isStyle) "стиль" else "скрипт"
    val sheet = GlassSheet(this).title(if (existing != null) "Обновить $what?" else "Установить $what?")
    val box = vbox(this).apply { setPadding(dp(8), 0, dp(8), dp(6)) }
    box.addView(label(this, s.name, 17f, p.text, true).apply { maxLines = 2 })
    val ver = listOfNotNull(s.version.ifEmpty { null }?.let { "версия $it" },
        existing?.version?.takeIf { it.isNotEmpty() && it != s.version }?.let { "сейчас $it" }).joinToString(" · ")
    if (ver.isNotEmpty()) box.addView(label(this, ver, 13f, p.dim).apply { setPadding(0, dp(3), 0, 0) })
    if (s.description.isNotEmpty()) box.addView(label(this, s.description, 14f, p.dim).apply { maxLines = 4; setPadding(0, dp(8), 0, 0) })
    fun section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        box.addView(label(this, title, 12.5f, p.dim, true).apply { setPadding(0, dp(14), 0, dp(4)) })
        box.addView(label(this, items.take(6).joinToString("\n") + if (items.size > 6) "\n… и ещё ${items.size - 6}" else "", 13.5f, p.text)
            .apply { maxLines = 8; typeface = Typeface.MONOSPACE })
    }
    section("РАБОТАЕТ НА", s.matches.map { if (it == "*" || it == "<all_urls>") "все сайты" else it })
    if (!s.isStyle) section("ДОСТУП", s.grants.filter { it != "none" }.map { grantName(it) }.distinct())
    box.addView(label(this, if (s.isStyle) "Стиль меняет только внешний вид сайтов."
        else "Скрипт получает полный доступ к страницам, на которых работает, включая то, что вы на них вводите. Ставьте только из источников, которым доверяете.",
        12.5f, p.dim).apply { maxLines = 5; setPadding(0, dp(14), 0, dp(12)) })
    sheet.content.addView(ScrollView(this).apply { addView(box) }, lp(MATCH, WRAP))
    val row = hbox(this)
    fun btn(text: String, primary: Boolean, on: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) p.onAccent else p.text)
        background = ripple(this@showInstall, rounded(this@showInstall, if (primary) p.accent else p.field, 22f), 22f)
        setOnClickListener { on() }
        pressable(0.95f)
    }
    row.addView(btn("Отмена", false) { sheet.dismiss() }, lp(0, dp(48), 1f).apply { marginEnd = dp(8) })
    row.addView(btn(if (existing != null) "Обновить" else "Установить", true) {
        scripts.save(s); reloadScripts(); sheet.dismiss()
        toast(this, if (existing != null) "Обновлено: ${s.name}" else "Установлено: ${s.name}")
    }, lp(0, dp(48), 1f))
    sheet.content.addView(row, lp(MATCH, WRAP))
    sheet.show()
}

private fun grantName(g: String) = when (g.removePrefix("GM_").removePrefix("GM.")) {
    "xmlhttpRequest", "xmlHttpRequest" -> "Запросы к любым сайтам"
    "setValue", "getValue", "deleteValue", "listValues" -> "Хранение своих настроек"
    "addStyle" -> "Изменение оформления"
    "openInTab" -> "Открытие вкладок"
    "setClipboard" -> "Запись в буфер обмена"
    "registerMenuCommand" -> "Команды в меню"
    "notification" -> "Уведомления"
    "unsafeWindow" -> "Доступ к коду страницы"
    else -> g
}

// ───────────────────────── manager ─────────────────────────

fun MainActivity.showScripts() {
    val p = Palette.cur
    val s = GlassSheet(this, fullHeight = true).title("Расширения")
    val list = vbox(this)
    val scroll = ScrollView(this).apply { addView(list) }
    lateinit var render: () -> Unit
    render = {
        list.removeAllViews()
        val all = scripts.all()
        val url = cur?.url ?: ""
        if (all.isEmpty()) {
            list.addView(label(this, "Пока ничего не установлено.\n\nЮзерскрипты добавляют сайтам функции, стили меняют их вид. " +
                    "Откройте каталог ниже и нажмите «Установить» у нужного скрипта — Polaris предложит его поставить.", 14.5f, p.dim)
                .apply { maxLines = 10; setPadding(dp(10), dp(10), dp(10), dp(16)) })
        }
        all.forEachIndexed { i, sc ->
            val r = hbox(this).apply {
                setPadding(dp(12), dp(10), dp(6), dp(10))
                background = ripple(this@showScripts, null, 18f)
                setOnClickListener { showScriptDetails(sc) { render() } }
                pressable(0.98f)
            }
            r.addView(TextView(this).apply {
                text = if (sc.isStyle) "CSS" else "JS"; gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (sc.isStyle) 0xFF2FBF71.toInt() else p.accent)
                background = rounded(this@showScripts, p.field, 10f)
            }, lp(dp(36), dp(36)).apply { marginEnd = dp(12) })
            val col = vbox(this)
            col.addView(label(this, sc.name, 15.5f, p.text).apply { maxLines = 1 })
            val here = sc.enabled && UserScripts.matches(sc, url)
            val sub = listOfNotNull(sc.version.ifEmpty { null }?.let { "v$it" },
                if (here) "работает на этой странице" else siteSummary(sc)).joinToString(" · ")
            col.addView(label(this, sub, 12.5f, if (here) 0xFF2FBF71.toInt() else p.dim).apply { maxLines = 1; setPadding(0, dp(2), 0, 0) })
            r.addView(col, lp(0, WRAP, 1f))
            val sw = Switch(this).apply { isChecked = sc.enabled; tintSwitch(this) }
            sw.setOnCheckedChangeListener { _, v -> scripts.setEnabled(sc.id, v); reloadScripts() }
            r.addView(sw)
            list.addView(r, lp(MATCH, WRAP))
            r.animateIn(i)
        }
    }
    s.content.addView(scroll, lp(MATCH, 0, 1f))

    val actions = vbox(this).apply { setPadding(0, dp(8), 0, 0) }
    fun pill(text: String, icon: String, on: () -> Unit) = hbox(this).apply {
        gravity = Gravity.CENTER
        background = ripple(this@showScripts, rounded(this@showScripts, p.field, 20f), 20f)
        addView(ImageView(this@showScripts).apply { setImageDrawable(icon(this@showScripts, icon, p.text)) }, lp(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(label(this@showScripts, text, 14.5f, p.text, true).apply { maxLines = 1 })
        setOnClickListener { on() }
        pressable(0.95f)
    }
    val r1 = hbox(this)
    r1.addView(pill("Скрипты", Ic.SEARCH) { s.dismiss(); newTab("https://greasyfork.org/ru", cur?.incognito == true) }, lp(0, dp(46), 1f).apply { marginEnd = dp(8) })
    r1.addView(pill("Стили", Ic.PALETTE) { s.dismiss(); newTab("https://userstyles.world/", cur?.incognito == true) }, lp(0, dp(46), 1f))
    actions.addView(r1, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
    val r2 = hbox(this)
    r2.addView(pill("По ссылке", Ic.ADD) { addByLink() }, lp(0, dp(46), 1f).apply { marginEnd = dp(8) })
    r2.addView(pill("Свой", Ic.TEXT) { s.dismiss(); editScript(null) }, lp(0, dp(46), 1f).apply { marginEnd = dp(8) })
    r2.addView(pill("Обновить", Ic.REFRESH) { checkUpdates() }, lp(0, dp(46), 1f))
    actions.addView(r2, lp(MATCH, WRAP))
    s.content.addView(actions, lp(MATCH, WRAP))
    render()
    s.show()
}

private fun siteSummary(sc: UScript): String {
    val m = sc.matches
    if (m.any { it == "*" || it == "<all_urls>" || it.startsWith("*://*/") || it == "http*://*" }) return "все сайты"
    val hosts = m.map { it.substringAfter("://").substringBefore("/").removePrefix("*.").ifEmpty { it } }.distinct()
    return if (hosts.size == 1) hosts[0] else "${hosts.size} сайтов"
}

private fun MainActivity.showScriptDetails(sc: UScript, changed: () -> Unit) {
    val p = Palette.cur
    val sheet = GlassSheet(this).title(sc.name)
    if (sc.description.isNotEmpty()) sheet.content.addView(label(this, sc.description, 14f, p.dim).apply { maxLines = 4; setPadding(dp(10), 0, dp(10), dp(8)) })
    sheet.content.addView(label(this, "Сайты: " + sc.matches.take(4).joinToString(", ") + if (sc.matches.size > 4) "…" else "", 13f, p.dim)
        .apply { maxLines = 3; setPadding(dp(10), 0, dp(10), dp(8)) })
    if (sc.updateUrl.startsWith("http")) sheet.row(Ic.REFRESH, "Проверить обновление") {
        sheet.dismiss()
        thread {
            val src = UserScripts.fetch(sc.updateUrl); val n = src?.let { UserScripts.parse(it, sc.updateUrl) }
            main.post { if (n == null) toast(this, "Не удалось загрузить") else if (n.version == sc.version && n.source == sc.source) toast(this, "Уже последняя версия") else showInstall(n) }
        }
    }
    if (!sc.isStyle) sheet.row(Ic.TEXT, "Редактировать код") { sheet.dismiss(); editScript(sc) }
    sheet.row(Ic.TRASH, "Удалить", color = 0xFFE5484D.toInt()) {
        sheet.dismiss()
        confirm("Удалить «${sc.name}»?", "Его настройки тоже удалятся.", "Удалить", "Отмена", danger = true, onYes = {
            scripts.delete(sc.id)
            getSharedPreferences("gm_values", 0).edit().apply { getSharedPreferences("gm_values", 0).all.keys.filter { it.startsWith("${sc.id}|") }.forEach { remove(it) } }.apply()
            reloadScripts(); changed()
        })
    }
    sheet.show()
}

private fun MainActivity.addByLink() {
    val p = Palette.cur
    val sheet = GlassSheet(this).title("Добавить по ссылке")
    val f = EditText(this).apply {
        hint = "https://…/script.user.js"; setSingleLine(); setTextColor(p.text); setHintTextColor(p.dim)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        background = rounded(this@addByLink, p.field, 14f); setPadding(dp(14), dp(12), dp(14), dp(12))
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@addByLink)?.toString()
        if (clip != null && clip.startsWith("http") && clip.length < 600) setText(clip)
    }
    sheet.content.addView(f, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
    sheet.row(Ic.DOWNLOAD, "Загрузить", color = p.accent) {
        val u = f.text.toString().trim()
        if (!u.startsWith("http")) { toast(this, "Нужна ссылка http(s)"); return@row }
        sheet.dismiss(); offerInstall(u)
    }
    sheet.show()
}

private fun MainActivity.checkUpdates() {
    val list = scripts.all().filter { it.updateUrl.startsWith("http") }
    if (list.isEmpty()) { toast(this, "Нечего обновлять"); return }
    toast(this, "Проверяю ${list.size}…")
    thread {
        var n = 0
        for (sc in list) {
            val src = UserScripts.fetch(sc.updateUrl) ?: continue
            val up = UserScripts.parse(src, sc.updateUrl) ?: continue
            if (up.name == sc.name && up.source != sc.source) { scripts.save(up); n++ }
        }
        main.post { reloadScripts(); toast(this, if (n == 0) "Всё актуально" else "Обновлено: $n") }
    }
}

/** Minimal editor for writing or tweaking a userscript by hand. */
fun MainActivity.editScript(sc: UScript?) {
    val p = Palette.cur
    val sheet = GlassSheet(this, fullHeight = true).title(if (sc == null) "Свой скрипт" else "Редактировать")
    val tpl = sc?.source ?: """// ==UserScript==
// @name        Мой скрипт
// @match       https://example.com/*
// @run-at      document-idle
// ==/UserScript==

document.body.style.outline = '3px solid hotpink';
"""
    val ed = EditText(this).apply {
        setText(tpl); setTextColor(p.text); typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(false)
        background = rounded(this@editScript, p.field, 16f); setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    sheet.content.addView(ed, lp(MATCH, 0, 1f).apply { bottomMargin = dp(10) })
    sheet.row(Ic.CHECK, "Сохранить", color = p.accent) {
        val parsed = UserScripts.parse(ed.text.toString())
        if (parsed == null) { toast(this, "Нужен заголовок ==UserScript== с @name и @match"); return@row }
        if (sc != null && (parsed.name != sc.name || parsed.namespace != sc.namespace)) scripts.delete(sc.id)
        scripts.save(parsed.copy(updateUrl = "")); reloadScripts(); sheet.dismiss(); toast(this, "Сохранено")
    }
    sheet.show()
}

fun MainActivity.showScriptCommands() {
    val t = cur ?: return
    val sheet = GlassSheet(this).title("Команды скриптов")
    t.cmds.forEach { name ->
        sheet.row(Ic.PLAY, name) {
            sheet.dismiss()
            t.web?.evaluateJavascript("window.__pzCmd&&__pzCmd(${JSONObject.quote(scriptCallbackKey)},${JSONObject.quote(name)})", null)
        }
    }
    sheet.show()
}
