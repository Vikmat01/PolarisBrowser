package com.polaris.browser

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("polaris", Context.MODE_PRIVATE)

    var engine: String
        get() = sp.getString("engine", "google")!!.let { e -> if (ENGINES.any { it.first == e }) e else "google" }
        set(v) = sp.edit().putString("engine", v).apply()
    var accent: String
        get() = sp.getString("accent", "ocean")!!
        set(v) = sp.edit().putString("accent", v).apply()
    /** clear | frost | solid */
    var glass: String
        get() = sp.getString("glass", "frost")!!
        set(v) = sp.edit().putString("glass", v).apply()
    /** 0 = aurora; otherwise timestamp of the saved home_bg.jpg */
    var homeBgVer: Long
        get() = sp.getLong("homeBgVer", 0)
        set(v) = sp.edit().putLong("homeBgVer", v).apply()
    var homeAnim: Boolean
        get() = sp.getBoolean("homeAnim", true)
        set(v) = sp.edit().putBoolean("homeAnim", v).apply()
    /** Astra AI features: "Спросить Astra" in the menu and in the text selection menu. */
    var astra: Boolean
        get() = sp.getBoolean("astra", true)
        set(v) = sp.edit().putBoolean("astra", v).apply()
    var adblock: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) = sp.edit().putBoolean("adblock", v).apply()
    var cosmetic: Boolean
        get() = sp.getBoolean("cosmetic", true)
        set(v) = sp.edit().putBoolean("cosmetic", v).apply()
    /** auto | system | light | dark */
    var theme: String
        get() = sp.getString("theme", "auto")!!
        set(v) = sp.edit().putString("theme", v).apply()
    var forceDark: Boolean
        get() = sp.getBoolean("forceDark", false)
        set(v) = sp.edit().putBoolean("forceDark", v).apply()
    var javascript: Boolean
        get() = sp.getBoolean("js", true)
        set(v) = sp.edit().putBoolean("js", v).apply()
    var textZoom: Int
        get() = sp.getInt("textZoom", 100)
        set(v) = sp.edit().putInt("textZoom", v).apply()
    var saveHistory: Boolean
        get() = sp.getBoolean("saveHistory", true)
        set(v) = sp.edit().putBoolean("saveHistory", v).apply()
    var block3p: Boolean
        get() = sp.getBoolean("block3p", true)
        set(v) = sp.edit().putBoolean("block3p", v).apply()
    var httpsOnly: Boolean
        get() = sp.getBoolean("httpsOnly", true)
        set(v) = sp.edit().putBoolean("httpsOnly", v).apply()
    var suggestions: Boolean
        get() = sp.getBoolean("suggestions", true)
        set(v) = sp.edit().putBoolean("suggestions", v).apply()
    var gpc: Boolean
        get() = sp.getBoolean("gpc", true)
        set(v) = sp.edit().putBoolean("gpc", v).apply()
    var autoHideBar: Boolean
        get() = sp.getBoolean("autoHideBar", true)
        set(v) = sp.edit().putBoolean("autoHideBar", v).apply()
    var blockPopups: Boolean
        get() = sp.getBoolean("blockPopups", true)
        set(v) = sp.edit().putBoolean("blockPopups", v).apply()
    var listsUpdated: Long
        get() = sp.getLong("listsUpdated", 0)
        set(v) = sp.edit().putLong("listsUpdated", v).apply()
    var blockedTotal: Long
        get() = sp.getLong("blockedTotal", 0)
        set(v) = sp.edit().putLong("blockedTotal", v).apply()
    var session: String
        get() = sp.getString("session", "")!!
        set(v) = sp.edit().putString("session", v).apply()
    var lat: Float
        get() = sp.getFloat("lat", Float.NaN)
        set(v) = sp.edit().putFloat("lat", v).apply()
    var lon: Float
        get() = sp.getFloat("lon", Float.NaN)
        set(v) = sp.edit().putFloat("lon", v).apply()
    var askedLocation: Boolean
        get() = sp.getBoolean("askedLoc", false)
        set(v) = sp.edit().putBoolean("askedLoc", v).apply()
    var readerTheme: String
        get() = sp.getString("readerTheme", "auto")!!
        set(v) = sp.edit().putString("readerTheme", v).apply()
    var readerSize: Int
        get() = sp.getInt("readerSize", 19)
        set(v) = sp.edit().putInt("readerSize", v).apply()
    var readerSerif: Boolean
        get() = sp.getBoolean("readerSerif", true)
        set(v) = sp.edit().putBoolean("readerSerif", v).apply()
    var playerSpeed: Float
        get() = sp.getFloat("playerSpeed", 1f)
        set(v) = sp.edit().putFloat("playerSpeed", v).apply()

    fun searchUrl(q: String): String {
        val e = java.net.URLEncoder.encode(q, "UTF-8")
        return when (engine) {
            "ddg" -> "https://duckduckgo.com/?q=$e"
            "bing" -> "https://www.bing.com/search?q=$e"
            "brave" -> "https://search.brave.com/search?q=$e"
            "startpage" -> "https://www.startpage.com/do/search?query=$e"
            else -> "https://www.google.com/search?q=$e"
        }
    }

    companion object {
        val ENGINES = listOf("google" to "Google", "ddg" to "DuckDuckGo", "brave" to "Brave Search", "bing" to "Bing", "startpage" to "Startpage")
    }
}
