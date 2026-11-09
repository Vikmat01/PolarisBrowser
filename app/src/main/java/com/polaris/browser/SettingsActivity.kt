package com.polaris.browser

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.*
import androidx.core.view.WindowInsetsControllerCompat

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private val p get() = Palette.cur

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this)
        val scroll = ScrollView(this)
        list = vbox(this).apply { setPadding(dp(14), dp(8), dp(14), dp(40)) }
        scroll.addView(list)
        root.addView(scroll)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val st = ins.getInsets(WindowInsets.Type.statusBars()).top
            val nb = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
            list.setPadding(dp(14), st + dp(8), dp(14), nb + dp(40))
            ins
        }
        setContentView(root)
        render()
    }


    private fun render() {
        Palette.cur = Palette.forPrefs(this, prefs)
        window.decorView.setBackgroundColor(p.bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.dark; isAppearanceLightNavigationBars = !p.dark
        }
        list.removeAllViews()
        val head = hbox(this)
        head.addView(iconButton(this, Ic.BACK) { finish() })
        head.addView(label(this, "Настройки", 24f, p.text, true).apply { setPadding(dp(6), 0, 0, 0) })
        list.addView(head, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })

        val engineName = Prefs.ENGINES.first { it.first == prefs.engine }.second
        section("Поиск") {
            item("Поисковая система", engineName) {
                choose("Поисковая система", Prefs.ENGINES, prefs.engine) { prefs.engine = it; render() }
            }
        }

        val themes = listOf("auto" to "Авто — по восходу и закату", "system" to "Как в системе", "light" to "Светлая", "dark" to "Тёмная")
        section("Оформление") {
            colorPicker(this)
            val glassLevels = listOf("clear" to "Прозрачное", "frost" to "Матовое", "solid" to "Плотное")
            item("Стекло", glassLevels.first { it.first == prefs.glass }.second) {
                choose("Стекло", glassLevels, prefs.glass) { prefs.glass = it; render() }
            }
            val bgName = if (prefs.homeBgVer > 0) "Своё фото" else "Сияние"
            item("Фон главной", bgName) {
                val opts = mutableListOf("aurora" to "Северное сияние", "pick" to "Выбрать изображение…")
                choose("Фон главной", opts, if (prefs.homeBgVer > 0) "pick" else "aurora") {
                    if (it == "pick") pickImage()
                    else { prefs.homeBgVer = 0; java.io.File(filesDir, "home_bg.jpg").delete(); render() }
                }
            }
            toggle("Анимация главной", "Плывущее сияние, мерцание звёзд, медленный наплыв фото", prefs.homeAnim) { prefs.homeAnim = it }
            item("Тема", themes.first { it.first == prefs.theme }.second) {
                choose("Тема", themes, prefs.theme) { prefs.theme = it; render() }
            }
            toggle("Тёмные сайты", "Затемнять сайты без своей тёмной темы", prefs.forceDark) { prefs.forceDark = it }
            toggle("Сжимать панель при прокрутке", null, prefs.autoHideBar) { prefs.autoHideBar = it }
            val zooms = listOf(80, 90, 100, 110, 120, 135, 150).map { it.toString() to "$it%" }
            item("Масштаб текста", "${prefs.textZoom}%") {
                choose("Масштаб текста", zooms, prefs.textZoom.toString()) { prefs.textZoom = it.toInt(); render() }
            }
        }

        val updated = if (prefs.listsUpdated == 0L) "ещё не загружены" else
            DateUtils.getRelativeTimeSpanString(prefs.listsUpdated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        section("Блокировка рекламы") {
            toggle("Блокировать рекламу и трекеры", "Всего заблокировано: ${prefs.blockedTotal}", prefs.adblock) { prefs.adblock = it }
            toggle("Скрывать рекламные блоки", "Убирает пустые места от рекламы", prefs.cosmetic) { prefs.cosmetic = it }
            toggle("Блокировать всплывающие окна", null, prefs.blockPopups) { prefs.blockPopups = it }
            item("Обновить фильтры", "EasyList + RuAdList · ${AdBlocker.ruleCount} правил · $updated") {
                toast(this@SettingsActivity, "Обновляю фильтры…")
                AdBlocker.update(this@SettingsActivity, prefs) { ok ->
                    runOnUiThread { toast(this@SettingsActivity, if (ok) "Фильтры обновлены" else "Не удалось загрузить фильтры"); render() }
                }
            }
            val allowed = prefs.sp.getStringSet("allowSites", emptySet())!!
            if (allowed.isNotEmpty()) item("Сайты-исключения", allowed.sorted().joinToString(", ")) {
                prefs.sp.edit().remove("allowSites").apply(); toast(this@SettingsActivity, "Исключения сброшены"); render()
            }
        }

        section("Конфиденциальность") {
            toggle("Блокировать сторонние cookies", "Мешает рекламным сетям следить за вами между сайтами. Изредка ломает вход через чужие окна", prefs.block3p) { prefs.block3p = it }
            toggle("Только защищённые соединения", "Открывать сайты по HTTPS, не грузить незащищённые картинки и видео, не пускать на сайты с поддельным сертификатом", prefs.httpsOnly) { prefs.httpsOnly = it }
            toggle("Просить сайты не продавать данные", "Сигнал Global Privacy Control и Do Not Track", prefs.gpc) { prefs.gpc = it }
            toggle("Подсказки поиска", "Пока вы печатаете, запрос отправляется поисковику", prefs.suggestions) { prefs.suggestions = it }
            toggle("Сохранять историю", null, prefs.saveHistory) { prefs.saveHistory = it }
            toggle("JavaScript", "Без него многие сайты не работают", prefs.javascript) { prefs.javascript = it }
            item("Очистить кэш", null) {
                WebView(this@SettingsActivity).apply { clearCache(true); destroy() }
                toast(this@SettingsActivity, "Кэш очищен")
            }
            item("Удалить cookies и данные сайтов", "Вы выйдете из аккаунтов на сайтах") {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                toast(this@SettingsActivity, "Данные сайтов удалены")
            }
            item("Очистить историю", null) { Db(this@SettingsActivity).clearHistory(); toast(this@SettingsActivity, "История очищена") }
        }

        section("Astra AI") {
            val has = Astra.installed(this@SettingsActivity)
            toggle("Функции Astra", if (has) "«Спросить Astra» в меню и при выделении текста. В инкогнито не работает"
                else "Установи Astra AI, чтобы спрашивать её о страницах", prefs.astra) { prefs.astra = it }
        }

        section("Браузер") {
            item("Сделать браузером по умолчанию", "Ссылки из других приложений будут открываться в Polaris") {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER))
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_BROWSER), 1)
                else if (rm.isRoleHeld(RoleManager.ROLE_BROWSER)) toast(this@SettingsActivity, "Polaris уже браузер по умолчанию")
                else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }

        section("О приложении") {
            item("Polaris Browser ${Updater.current(this@SettingsActivity)}", "Проверить обновления на GitHub") { Updater.manualCheck(this@SettingsActivity) }
            toggle("Проверять обновления автоматически", "Раз в 12 часов при запуске", prefs.sp.getBoolean("autoUpdate", true)) {
                prefs.sp.edit().putBoolean("autoUpdate", it).apply()
            }
            item("Страница проекта", "github.com/${Updater.REPO}") {
                startActivity(Intent(this@SettingsActivity, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse("https://github.com/${Updater.REPO}")))
            }
            item("Движок", "${WebView.getCurrentWebViewPackage()?.let { "${it.packageName.substringAfterLast('.')} ${it.versionName}" } ?: "WebView"}") {}
        }
    }

    private inner class Section(val box: LinearLayout) {
        private fun divider() {
            if (box.childCount == 0) return
            box.addView(android.view.View(this@SettingsActivity).apply {
                setBackgroundColor(if (p.dark) 0x14FFFFFF else 0x14000000)
            }, lp(MATCH, maxOf(1, dp(0.6f))).apply { marginStart = dp(16) })
        }

        fun item(title: String, sub: String?, on: () -> Unit) {
            divider()
            val inline = sub != null && sub.length <= 22
            val r = hbox(this@SettingsActivity).apply {
                minimumHeight = dp(54)
                setPadding(dp(16), dp(12), dp(12), dp(12))
                background = ripple(this@SettingsActivity, null, 0f)
                setOnClickListener { on() }
            }
            val col = vbox(this@SettingsActivity)
            col.addView(label(this@SettingsActivity, title, 16f, p.text).apply { maxLines = 1 })
            if (sub != null && !inline) col.addView(label(this@SettingsActivity, sub, 13f, p.dim).apply { maxLines = 2; setPadding(0, dp(3), 0, 0) })
            r.addView(col, lp(0, WRAP, 1f))
            if (inline) r.addView(label(this@SettingsActivity, sub!!, 15f, p.dim).apply { maxLines = 1; setPadding(dp(8), 0, dp(2), 0) })
            r.addView(ImageView(this@SettingsActivity).apply {
                setImageDrawable(icon(this@SettingsActivity, CHEVRON, (p.dim and 0x00FFFFFF) or 0x99000000.toInt()))
            }, lp(dp(18), dp(18)))
            box.addView(r, lp(MATCH, WRAP))
        }

        fun toggle(title: String, sub: String?, value: Boolean, on: (Boolean) -> Unit) {
            divider()
            val r = hbox(this@SettingsActivity).apply {
                minimumHeight = dp(54)
                setPadding(dp(16), dp(10), dp(12), dp(10))
                background = ripple(this@SettingsActivity, null, 0f)
            }
            val c = vbox(this@SettingsActivity)
            c.addView(label(this@SettingsActivity, title, 16f, p.text).apply { maxLines = 1 })
            if (sub != null) c.addView(label(this@SettingsActivity, sub, 13f, p.dim).apply { maxLines = 2; setPadding(0, dp(3), 0, 0) })
            r.addView(c, lp(0, WRAP, 1f).apply { marginEnd = dp(10) })
            val sw = Switch(this@SettingsActivity).apply { isChecked = value; tintSwitch(this) }
            sw.setOnCheckedChangeListener { _, v -> on(v) }
            r.addView(sw)
            r.setOnClickListener { sw.toggle() }
            box.addView(r, lp(MATCH, WRAP))
        }
    }

    private val CHEVRON = "M8.59,16.59L13.17,12 8.59,7.41 10,6l6,6 -6,6 -1.41,-1.41z"

    private fun colorPicker(sec: Section) {
        val head = hbox(this).apply { setPadding(dp(16), dp(14), dp(16), dp(6)) }
        head.addView(label(this, "Цвет", 16f, p.text), lp(0, WRAP, 1f))
        head.addView(label(this, accentByKey(prefs.accent).name, 15f, p.dim))
        sec.box.addView(head, lp(MATCH, WRAP))
        val row = hbox(this).apply { setPadding(dp(10), dp(4), dp(10), dp(14)); gravity = Gravity.CENTER }
        ACCENTS.forEach { a ->
            val sel = a.key == prefs.accent
            val c = if (p.dark) a.dark else a.light
            val cell = FrameLayout(this).apply {
                setOnClickListener { prefs.accent = a.key; render() }
                pressable(0.85f)
            }
            if (sel) cell.addView(android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(dp(2), c)
                }
            }, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
            cell.addView(android.view.View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
                }
            }, FrameLayout.LayoutParams(dp(if (sel) 26 else 28), dp(if (sel) 26 else 28), Gravity.CENTER))
            row.addView(cell, lp(0, dp(40), 1f))
        }
        sec.box.addView(row, lp(MATCH, WRAP))
    }

    private fun pickImage() {
        val i = if (android.os.Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(i, 7) } catch (_: Exception) { toast(this, "Не удалось открыть галерею") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val dm = resources.displayMetrics
        val maxSide = maxOf(dm.widthPixels, dm.heightPixels).coerceAtMost(2400)
        kotlin.concurrent.thread {
            val ok = try {
                val src = android.graphics.ImageDecoder.createSource(contentResolver, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val k = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                    dec.setTargetSize(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                java.io.File(filesDir, "home_bg.jpg").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                true
            } catch (_: Exception) { false }
            runOnUiThread {
                if (ok) { prefs.homeBgVer = System.currentTimeMillis(); toast(this, "Фон главной обновлён") }
                else toast(this, "Не удалось открыть изображение")
                render()
            }
        }
    }

    private fun section(title: String, build: Section.() -> Unit) {
        list.addView(label(this, title.uppercase(), 12.5f, p.dim, true).apply { letterSpacing = 0.06f; setPadding(dp(16), dp(22), 0, dp(8)) })
        val box = vbox(this).apply {
            background = GlassDrawable(dp(22).toFloat(), if (p.dark) 0xFF161A25.toInt() else 0xFFFFFFFF.toInt(), p.dark, density)
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        Section(box).build()
        list.addView(box, lp(MATCH, WRAP))
    }

    private fun choose(title: String, options: List<Pair<String, String>>, cur: String, on: (String) -> Unit) {
        val s = GlassSheet(this).title(title)
        options.forEach { (k, n) ->
            s.row(if (k == cur) Ic.STAR else null, n, color = if (k == cur) p.accent else p.text) { s.dismiss(); on(k) }
        }
        s.show()
    }
}
