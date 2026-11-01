package com.polaris.browser

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

// ───────────────────────── main menu ─────────────────────────

fun MainActivity.showMenu() {
    val t = cur ?: return
    val s = GlassSheet(this).fromBar(glassBar)
    val p = Palette.cur

    // quick action row: round liquid-glass buttons, captions underneath
    val quick = hbox(this).apply { setPadding(0, dp(2), 0, dp(8)); gravity = Gravity.TOP }
    var qi = 0
    fun q(ic: String, txt: String, color: Int = p.text, on: () -> Unit) {
        val cell = vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val btn = LiquidFrame(this@showMenu).apply {
            background = s.lensFor(this, 29f) ?: GlassDrawable(dp(29).toFloat(), if (p.dark) 0x26FFFFFF else 0x14000000, p.dark, density)
            foreground = ripple(this@showMenu, null, 30f)
            setOnClickListener { s.dismiss(); on() }
            pressable(0.86f)
        }
        btn.addView(ImageView(this).apply { setImageDrawable(icon(this@showMenu, ic, color)) },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        cell.addView(btn, lp(dp(58), dp(58)))
        cell.addView(TextView(this).apply {
            text = txt; gravity = Gravity.CENTER; maxLines = 1; includeFontPadding = false
            setTextColor(p.dim)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, TypedValue.COMPLEX_UNIT_SP)
        }, lp(MATCH, dp(18)).apply { topMargin = dp(7) })
        quick.addView(cell, lp(0, WRAP, 1f))
        cell.animateIn(qi++, 14f)
    }
    val home = t.isHome
    if (!home) {
        val marked = db.isBookmarked(t.url)
        if (t.web?.canGoForward() == true) q(Ic.FORWARD, "Вперёд") { t.web?.goForward() }
        q(Ic.REFRESH, "Обновить") { t.web?.reload() }
        if (!t.incognito) q(if (marked) Ic.STAR else Ic.STAR_O, "Закладка", if (marked) 0xFFF5B400.toInt() else p.text) { toggleBookmark() }
        q(Ic.SHARE, "Отправить") { share(t.url, t.title) }
        q(Ic.SEARCH, "Найти") { openFind() }
        s.content.addView(quick, lp(MATCH, WRAP))
    }

    val sc = ScrollView(this)
    val list = vbox(this)
    sc.addView(list)
    // rows go into scrollable list
    val origContent = s.content
    fun row(ic: String, text: String, sub: String? = null, color: Int = p.text, on: () -> Unit) {
        val r = hbox(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(this@showMenu, null, 18f)
            setOnClickListener { s.dismiss(); on() }
            pressable(0.97f)
        }
        r.addView(ImageView(this).apply { setImageDrawable(icon(this@showMenu, ic, color)) }, lp(dp(22), dp(22)).apply { marginEnd = dp(14) })
        val col = vbox(this)
        col.addView(label(this, text, 15.5f, color))
        if (sub != null) col.addView(label(this, sub, 12.5f, p.dim).apply { maxLines = 1 })
        r.addView(col, lp(0, WRAP, 1f))
        list.addView(r, lp(MATCH, WRAP))
        r.animateIn(list.childCount + 4)
    }
    fun toggleRow(ic: String, text: String, on: Boolean, action: () -> Unit) {
        val r = hbox(this).apply {
            setPadding(dp(10), dp(6), dp(6), dp(6))
            background = ripple(this@showMenu, null, 16f)
        }
        r.addView(ImageView(this).apply { setImageDrawable(icon(this@showMenu, ic, p.text)) }, lp(dp(22), dp(22)).apply { marginEnd = dp(14) })
        r.addView(label(this, text, 15.5f, p.text), lp(0, WRAP, 1f))
        val sw = Switch(this).apply { isChecked = on; isClickable = false; tintSwitch(this) }
        r.addView(sw)
        r.setOnClickListener { sw.isChecked = !sw.isChecked; r.postDelayed({ s.dismiss(); action() }, 180) }
        list.addView(r, lp(MATCH, WRAP))
        r.animateIn(list.childCount + 4)
    }

    row(Ic.ADD, "Новая вкладка") { newTab(null, false) }
    row(Ic.INCOGNITO, "Новая вкладка инкогнито", color = p.incognito) { newTab(null, true) }
    run {
        val active = if (home) 0 else scripts.all().count { it.enabled && UserScripts.matches(it, t.url) }
        row(Ic.TUNE, "Расширения", if (active > 0) "Работает на странице: $active" else "Юзерскрипты и стили") { showScripts() }
        if (t.cmds.isNotEmpty()) row(Ic.PLAY, "Команды скриптов", t.cmds.take(3).joinToString(", ")) { showScriptCommands() }
    }
    row(Ic.STAR_O, "Закладки") { showLibrary(0) }
    row(Ic.HISTORY, "История") { showLibrary(1) }
    row(Ic.DOWNLOAD, "Загрузки") { showLibrary(2) }
    if (!home) {
        row(Ic.READER, "Режим чтения", if (t.readerable) "Статья найдена" else null) { openReader() }
        row(Ic.PLAY, "Видео на странице", "Открыть в плеере Polaris") { findPageVideos() }
        toggleRow(Ic.DESKTOP, "Версия для ПК", t.desktop) {
            t.desktop = !t.desktop
            t.web?.let { applySettings(it, t); it.reload() }
        }
    }
    toggleRow(Ic.MOON, "Тёмные сайты", prefs.forceDark) {
        prefs.forceDark = !prefs.forceDark
        tabs.forEach { tb -> tb.web?.let { applySettings(it, tb) } }
        toast(this, if (prefs.forceDark) "Тёмная тема для сайтов включена — полностью применится к новым вкладкам" else "Тёмная тема для сайтов выключена")
        t.web?.reload()
    }
    row(Ic.TUNE, "Настройки") { startActivity(Intent(this, SettingsActivity::class.java)) }

    val h = (resources.displayMetrics.heightPixels * 0.68f).toInt()
    origContent.addView(sc, lp(MATCH, WRAP))
    sc.post { if (sc.height > h) sc.layoutParams = sc.layoutParams.apply { height = h } }
    s.show()
}

// ───────────────────────── tabs overview ─────────────────────────

fun MainActivity.showTabs() {
    captureCurrent()
    val s = GlassSheet(this, fullHeight = true)
    val p = Palette.cur
    var showInc = cur?.incognito == true
    var animateCards = true

    val seg = hbox(this).apply {
        background = rounded(this@showTabs, p.field, 18f)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    val grid = vbox(this)
    val scroll = ScrollView(this).apply { addView(grid); isVerticalScrollBarEnabled = false }

    lateinit var render: () -> Unit
    fun segBtn(text: String, inc: Boolean) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setOnClickListener { showInc = inc; animateCards = true; render() }
    }
    val bNormal = segBtn("Вкладки", false)
    val bInc = segBtn("Инкогнито", true)
    seg.addView(bNormal, lp(0, dp(36), 1f))
    seg.addView(bInc, lp(0, dp(36), 1f))

    render = {
        listOf(bNormal to false, bInc to true).forEach { (b, inc) ->
            val sel = inc == showInc
            b.background = if (sel) glass(this, 16f, p, strong = true) else null
            b.setTextColor(if (sel) (if (inc) p.incognito else p.text) else p.dim)
            b.text = (if (inc) "Инкогнито" else "Вкладки") + " · " + tabs.count { it.incognito == inc }
        }
        grid.removeAllViews()
        val list = tabs.filter { it.incognito == showInc }
        if (list.isEmpty()) {
            grid.addView(label(this, if (showInc) "Нет инкогнито-вкладок.\nИх история не сохраняется." else "Нет вкладок", 15f, p.dim).apply {
                gravity = Gravity.CENTER; maxLines = 3; setPadding(0, dp(60), 0, 0)
            }, lp(MATCH, WRAP))
        }
        val cardW = (resources.displayMetrics.widthPixels - dp(16 + 28 + 12)) / 2
        val cardH = (cardW * 1.35f).toInt()
        list.chunked(2).forEach { pair ->
            val row = hbox(this).apply { gravity = Gravity.TOP }
            pair.forEachIndexed { i, tab ->
                val card = FrameLayout(this).apply {
                    val active = tab === cur
                    background = glass(this@showTabs, 20f, p, strong = true)
                    if (active) foreground = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat(); setStroke(dp(2.5f), if (tab.incognito) p.incognito else p.accent)
                    }
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat()) }
                    }
                    elevation = dp(3).toFloat()
                    setOnClickListener { s.dismiss(); selectTab(tab) }
                }
                val col = vbox(this)
                val head = hbox(this).apply { setPadding(dp(10), dp(8), dp(4), dp(4)) }
                head.addView(ImageView(this).apply {
                    val f = tab.favicon
                    if (f != null) setImageBitmap(f) else setImageDrawable(icon(this@showTabs, if (tab.incognito) Ic.INCOGNITO else Ic.GLOBE, p.dim))
                }, lp(dp(16), dp(16)).apply { marginEnd = dp(6) })
                head.addView(label(this, tab.displayTitle, 12.5f, p.text, true).apply { maxLines = 1 }, lp(0, WRAP, 1f))
                head.addView(iconButton(this, Ic.CLOSE, 30, 16, p.dim) {
                    val c = card
                    c.isEnabled = false
                    c.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f).translationY(-dp(10).toFloat())
                        .setDuration(200).setInterpolator(EASE_OUT).withEndAction {
                            val last = tabs.count { it.incognito == showInc } == 1
                            if (last && !showInc) {
                                // closing the only tab: go straight to a fresh home page instead of re-drawing a new card
                                s.dismiss(); closeTab(tab)
                            } else {
                                closeTab(tab)
                                if (tabs.none { it.incognito == showInc }) showInc = false
                                animateCards = false; render()
                            }
                        }.start()
                })
                col.addView(head)
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val th = tab.thumb
                    if (th != null) setImageBitmap(th)
                    else {
                        val au = if (tab.incognito) Palette.build(true, "graphite", prefs.glass).aurora else p.aurora
                        background = android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                            intArrayOf(au[0], Palette.mix(au[1] and 0xFFFFFF, (if (p.dark || tab.incognito) 0x090B12 else 0xF3F5FA), 0.35f) or 0xFF000000.toInt(), au[2])
                        )
                        setImageDrawable(icon(this@showTabs, if (tab.incognito) Ic.INCOGNITO else Ic.POLARIS, 0xE6FFFFFF.toInt()))
                        scaleType = ImageView.ScaleType.CENTER
                        setPadding(dp(40), dp(40), dp(40), dp(40))
                    }
                }
                col.addView(thumb, lp(MATCH, 0, 1f))
                card.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
                row.addView(card, lp(cardW, cardH).apply { if (i == 0) marginEnd = dp(12) })
                card.pressable(0.95f)
                if (animateCards) card.animateIn(grid.childCount * 2 + i, 24f)
            }
            grid.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }
    }

    s.content.addView(seg, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
    s.content.addView(scroll, lp(MATCH, 0, 1f))

    val bottom = hbox(this).apply { setPadding(0, dp(10), 0, 0) }
    fun pill(text: String, primary: Boolean, on: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else p.text)
        background = ripple(this@showTabs, rounded(this@showTabs, if (primary) p.accent else p.field, 22f), 22f)
        setOnClickListener { on() }
    }
    bottom.addView(pill("Закрыть все", false) {
        s.dismiss(); closeAll(showInc)
    }, lp(0, dp(46), 1f).apply { marginEnd = dp(10) })
    bottom.addView(pill("+  Новая", true) { s.dismiss(); newTab(null, showInc) }, lp(0, dp(46), 1.3f))
    s.content.addView(bottom, lp(MATCH, WRAP))
    render()
    s.show()
}

// ───────────────────────── library: bookmarks / history / downloads ─────────────────────────

fun MainActivity.showLibrary(startTab: Int) {
    val s = GlassSheet(this, fullHeight = true)
    val p = Palette.cur
    var tab = startTab
    val seg = hbox(this).apply {
        background = rounded(this@showLibrary, p.field, 18f)
        setPadding(dp(3), dp(3), dp(3), dp(3))
    }
    val search = EditText(this).apply {
        hint = "Поиск в истории"; setSingleLine()
        setTextColor(p.text); setHintTextColor(p.dim)
        background = rounded(this@showLibrary, p.field, 16f)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    val list = vbox(this)
    val scroll = ScrollView(this).apply { addView(list) }
    val footer = hbox(this)
    lateinit var render: () -> Unit
    val names = listOf("Закладки", "История", "Загрузки")
    val btns = names.mapIndexed { i, n ->
        TextView(this).apply {
            text = n; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener { tab = i; render() }
        }.also { seg.addView(it, lp(0, dp(36), 1f)) }
    }

    fun entryRow(ic: String, title: String, sub: String, onClick: () -> Unit, onLong: (() -> Unit)?, onDelete: (() -> Unit)?) {
        val r = hbox(this).apply {
            setPadding(dp(10), dp(9), dp(2), dp(9))
            background = ripple(this@showLibrary, null, 16f)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        r.addView(ImageView(this).apply { setImageDrawable(icon(this@showLibrary, ic, p.dim)) }, lp(dp(20), dp(20)).apply { marginEnd = dp(14) })
        val c = vbox(this)
        c.addView(label(this, title, 15f, p.text).apply { maxLines = 1 })
        c.addView(label(this, sub, 12f, p.dim).apply { maxLines = 1 })
        r.addView(c, lp(0, WRAP, 1f))
        if (onDelete != null) r.addView(iconButton(this, Ic.CLOSE, 38, 16, p.dim) { onDelete() })
        list.addView(r, lp(MATCH, WRAP))
    }
    fun header(text: String) {
        list.addView(label(this, text, 12.5f, p.accent, true).apply { setPadding(dp(10), dp(12), dp(10), dp(4)) })
    }
    fun open(url: String) { s.dismiss(); cur?.let { load(it, url) } }

    render = {
        btns.forEachIndexed { i, b ->
            b.background = if (i == tab) glass(this, 16f, p, strong = true) else null
            b.setTextColor(if (i == tab) p.text else p.dim)
        }
        search.visibility = if (tab == 1) View.VISIBLE else View.GONE
        list.removeAllViews()
        footer.removeAllViews()
        when (tab) {
            0 -> {
                val bm = db.bookmarks()
                if (bm.isEmpty()) list.addView(label(this, "Закладок пока нет", 15f, p.dim).apply { setPadding(dp(10), dp(30), 0, 0) })
                bm.forEach { e ->
                    entryRow(Ic.STAR, e.title.ifBlank { e.url }, e.url.removePrefix("https://").removePrefix("www."),
                        { open(e.url) },
                        { renameDialog(e) { render() } },
                        { db.removeBookmark(e.url); render() })
                }
                list.addView(label(this, "Первые 12 закладок показываются на стартовой странице. Удерживайте, чтобы переименовать.", 12f, p.dim).apply {
                    maxLines = 3; setPadding(dp(10), dp(14), dp(10), 0)
                })
            }
            1 -> {
                val items = db.history(search.text.toString())
                if (items.isEmpty()) list.addView(label(this, "История пуста", 15f, p.dim).apply { setPadding(dp(10), dp(30), 0, 0) })
                var lastDay = ""
                items.forEach { e ->
                    val day = when {
                        DateUtils.isToday(e.ts) -> "Сегодня"
                        DateUtils.isToday(e.ts + DateUtils.DAY_IN_MILLIS) -> "Вчера"
                        else -> DateUtils.formatDateTime(this, e.ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
                    }
                    if (day != lastDay) { header(day); lastDay = day }
                    val time = DateUtils.formatDateTime(this, e.ts, DateUtils.FORMAT_SHOW_TIME)
                    entryRow(Ic.HISTORY, e.title.ifBlank { e.url }, "$time · ${Uri.parse(e.url).host?.removePrefix("www.") ?: ""}",
                        { open(e.url) }, null, { db.deleteHistory(e.id); render() })
                }
                footer.addView(TextView(this).apply {
                    text = "Очистить историю"; gravity = Gravity.CENTER
                    setTextColor(0xFFE5484D.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    background = ripple(this@showLibrary, rounded(this@showLibrary, p.field, 20f), 20f)
                    setOnClickListener {
                        confirm("Очистить историю?", "Вся история посещений будет удалена.", "Очистить", "Отмена", danger = true,
                            onYes = { db.clearHistory(); render() })
                    }
                }, lp(MATCH, dp(44), 1f))
            }
            2 -> {
                val dm = getSystemService(DownloadManager::class.java)
                val dls = db.downloads()
                if (dls.isEmpty()) list.addView(label(this, "Загрузок пока нет", 15f, p.dim).apply { setPadding(dp(10), dp(30), 0, 0) })
                dls.forEach { d ->
                    var status = "Неизвестно"
                    var local: Uri? = null
                    var mime = d.mime
                    try {
                        dm.query(DownloadManager.Query().setFilterById(d.sysId)).use { c ->
                            if (c.moveToFirst()) {
                                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
                                status = when (st) {
                                    DownloadManager.STATUS_SUCCESSFUL -> { local = dm.getUriForDownloadedFile(d.sysId); "Готово · ${fmt(total)}" }
                                    DownloadManager.STATUS_RUNNING -> if (total > 0) "Загрузка ${done * 100 / total}% · ${fmt(done)} из ${fmt(total)}" else "Загрузка · ${fmt(done)}"
                                    DownloadManager.STATUS_PENDING -> "В очереди"
                                    DownloadManager.STATUS_PAUSED -> "Пауза"
                                    DownloadManager.STATUS_FAILED -> "Ошибка"
                                    else -> status
                                }
                                if (mime.isEmpty()) mime = dm.getMimeTypeForDownloadedFile(d.sysId) ?: ""
                            } else status = "Файл удалён"
                        }
                    } catch (_: Exception) {}
                    val isVideo = mime.startsWith("video/") || MainActivity.VIDEO_EXT.matches(d.name)
                    entryRow(if (isVideo) Ic.PLAY else Ic.DOWNLOAD, d.name, status, {
                        val u = local
                        if (u == null) toast(this, status)
                        else if (isVideo) PlayerActivity.openLocal(this, u, d.name)
                        else try {
                            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, mime.ifEmpty { "*/*" })
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: Exception) { toast(this, "Нет приложения для этого файла") }
                    }, null, { try { dm.remove(d.sysId) } catch (_: Exception) {}; db.deleteDownload(d.id); render() })
                }
                footer.addView(TextView(this).apply {
                    text = "Открыть папку «Загрузки»"; gravity = Gravity.CENTER
                    setTextColor(p.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    background = ripple(this@showLibrary, rounded(this@showLibrary, p.field, 20f), 20f)
                    setOnClickListener {
                        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) {}
                    }
                }, lp(MATCH, dp(44), 1f))
            }
        }
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: Editable?) { if (tab == 1) render() }
    })
    s.content.addView(seg, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
    s.content.addView(search, lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
    s.content.addView(scroll, lp(MATCH, 0, 1f))
    s.content.addView(footer, lp(MATCH, WRAP).apply { topMargin = dp(8) })
    render()
    s.show()
}

private fun MainActivity.renameDialog(e: Entry, done: () -> Unit) {
    val p = Palette.cur
    val s = GlassSheet(this).title("Переименовать")
    val f = EditText(this).apply {
        setText(e.title); setSingleLine(); setTextColor(p.text)
        background = rounded(this@renameDialog, p.field, 14f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        selectAll()
    }
    s.content.addView(f, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
    s.row(Ic.STAR, "Сохранить", color = p.accent) { db.renameBookmark(e.id, f.text.toString()); s.dismiss(); done() }
    s.show()
}

// ───────────────────────── page videos ─────────────────────────

fun MainActivity.findPageVideos() {
    val t = cur ?: return
    val w = t.web ?: return
    val js = "(function(){var out=[];function add(u,n){if(u&&/^https?:/.test(u)&&out.indexOf(u)<0)out.push(u)}" +
            "function scan(d){d.querySelectorAll('video').forEach(function(v){add(v.currentSrc||v.src);v.querySelectorAll('source').forEach(function(s){add(s.src)})});" +
            "d.querySelectorAll('a[href]').forEach(function(a){if(/\\.(mp4|webm|mkv|m3u8|mov)(\\?|$)/i.test(a.href))add(a.href)});" +
            "d.querySelectorAll('iframe').forEach(function(f){try{scan(f.contentDocument)}catch(e){}})}scan(document);return JSON.stringify(out)})()"
    w.evaluateJavascript(js) { r ->
        val str = (try { org.json.JSONTokener(r).nextValue() } catch (_: Exception) { null }) as? String ?: "[]"
        val arr = org.json.JSONArray(str)
        val urls = List(arr.length()) { arr.getString(it) }
        val cookie = { u: String -> Incog.cookie(t.incognito, u) }
        when (urls.size) {
            0 -> toast(this, "Прямых видео на странице не найдено (потоковые плееры вроде YouTube открывайте на весь экран — там есть скорость и картинка в картинке)")
            1 -> PlayerActivity.open(this, urls[0], t.url, t.title, cookie(urls[0]), w.settings.userAgentString, t.incognito)
            else -> {
                val s = GlassSheet(this).title("Видео на странице")
                urls.take(12).forEachIndexed { i, u ->
                    s.row(Ic.PLAY, "Видео ${i + 1}", u) { s.dismiss(); PlayerActivity.open(this, u, t.url, t.title, cookie(u), w.settings.userAgentString, t.incognito) }
                }
                s.show()
            }
        }
    }
}


fun tintSwitch(sw: Switch) {
    val p = Palette.cur
    val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    sw.thumbTintList = android.content.res.ColorStateList(st, intArrayOf(Color.WHITE, if (p.dark) 0xFFD0D5DE.toInt() else Color.WHITE))
    sw.trackTintList = android.content.res.ColorStateList(st, intArrayOf(p.accent, if (p.dark) 0x55FFFFFF else 0x33000000))
    sw.trackTintMode = android.graphics.PorterDuff.Mode.SRC
}
