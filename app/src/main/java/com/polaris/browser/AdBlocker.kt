package com.polaris.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Network-level ad blocking built from EasyList + RuAdList ("||domain^" rules),
 * plus element hiding (generic and per-site "##selector" rules).
 */
object AdBlocker {
    @Volatile private var block: Set<String> = emptySet()
    @Volatile private var allow: Set<String> = emptySet()
    @Volatile var genericCss: String = ""; private set
    @Volatile private var siteCss: Map<String, String> = emptyMap()
    @Volatile var ruleCount = 0; private set
    @Volatile var updating = false; private set

    private val LISTS = listOf(
        "https://easylist-downloads.adblockplus.org/ruadlist+easylist.txt",
        "https://easylist.to/easylist/easylist.txt",
    )

    fun init(ctx: Context, prefs: Prefs) {
        thread(name = "adblock-init") {
            val seed = ctx.assets.open("adblock_seed.txt").bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            block = seed; ruleCount = seed.size
            val cache = File(ctx.filesDir, "filters.txt")
            if (cache.exists()) parse(cache.readLines(), seed)
            val week = 7L * 24 * 3600 * 1000
            if (!cache.exists() || System.currentTimeMillis() - prefs.listsUpdated > week) update(ctx, prefs, null)
        }
    }

    fun update(ctx: Context, prefs: Prefs, done: ((Boolean) -> Unit)?) {
        if (updating) return
        updating = true
        thread(name = "adblock-update") {
            var ok = false
            for (u in LISTS) {
                try {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 PolarisBrowser")
                    if (c.responseCode == 200) {
                        val lines = c.inputStream.bufferedReader().readLines()
                        if (lines.size > 1000) {
                            File(ctx.filesDir, "filters.txt").writeText(lines.joinToString("\n"))
                            val seed = ctx.assets.open("adblock_seed.txt").bufferedReader().readLines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            parse(lines, seed)
                            prefs.listsUpdated = System.currentTimeMillis()
                            ok = true
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
            updating = false
            done?.invoke(ok)
        }
    }

    private val WHOLE_HOST_OPTS = setOf("third-party", "3p", "important", "all", "document", "doc")

    private val SIMPLE_SEL = Regex("^[#.\\[a-zA-Z][^{}]*$")

    private fun parse(lines: List<String>, seed: Set<String>) {
        val b = HashSet<String>(seed.size + 40000)
        b.addAll(seed)
        val a = HashSet<String>()
        val generic = StringBuilder()
        val site = HashMap<String, StringBuilder>()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '[') continue
            when {
                l.startsWith("@@||") -> domainOf(l.substring(4))?.let { a.add(it) }
                l.startsWith("||") -> {
                    // Only whole-host rules ("||ads.example.com^"). Rules with a path
                    // ("||cdn.site.ru/banners/") or limited to some request types must not
                    // block the entire host — that broke sites (e.g. DNS lost its CSS).
                    val body = l.substring(2).substringBefore('$')
                    val opts = l.substringAfter('$', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (!opts.all { it in WHOLE_HOST_OPTS }) continue
                    val d = domainOf(body) ?: continue
                    val rest = body.substring(d.length)
                    if (rest.isNotEmpty() && rest != "^" && rest != "^|") continue
                    b.add(d)
                }
                l.startsWith("##") -> {
                    val sel = l.substring(2)
                    if (sel.length < 200 && SIMPLE_SEL.matches(sel) && !sel.contains(":-abp") && !sel.contains(":has-text") &&
                        !sel.contains(":contains") && !sel.contains(":xpath") && !sel.contains(":upward") &&
                        !sel.contains(":remove") && !sel.contains(":style")
                    ) generic.append(sel).append("{display:none!important}\n")
                }
                l.contains("##") && !l.contains("#@#") && !l.contains("#?#") && !l.contains("#$#") -> {
                    val doms = l.substringBefore("##")
                    val sel = l.substringAfter("##")
                    if (sel.contains(":-abp") || sel.contains(":has-text") || sel.contains(":contains") ||
                        sel.contains(":xpath") || sel.contains(":upward") || sel.contains(":remove") || sel.contains(":style")) continue
                    for (d in doms.split(',')) {
                        val dd = d.trim()
                        if (dd.isEmpty() || dd.startsWith("~") || dd.contains('*')) continue
                        site.getOrPut(dd) { StringBuilder() }.append(sel).append("{display:none!important}\n")
                    }
                }
            }
        }
        block = b; allow = a
        genericCss = generic.toString()
        siteCss = site.mapValues { it.value.toString() }
        ruleCount = b.size + site.size
    }

    private fun domainOf(s: String): String? {
        val end = s.indexOfFirst { it == '^' || it == '/' || it == '$' || it == ':' || it == '|' }
        val d = (if (end >= 0) s.substring(0, end) else s).lowercase()
        if (d.isEmpty() || d.contains('*') || !d.contains('.')) return null
        return d
    }

    /** Rough registrable domain: a.dns-shop.ru and www.dns-shop.ru are the same site. */
    private fun site(h: String): String {
        val p = h.split('.')
        if (p.size <= 2) return h
        val sld = p[p.size - 2]
        val n = if (sld.length <= 3 && sld in setOf("co", "com", "net", "org", "gov", "edu", "ac", "msk", "spb")) 3 else 2
        return p.takeLast(n).joinToString(".")
    }

    private fun matches(set: Set<String>, host: String): Boolean {
        var h = host
        while (true) {
            if (set.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0 || i == h.length - 1) return false
            h = h.substring(i + 1)
            if (!h.contains('.')) return false
        }
    }

    fun isAd(url: Uri, pageHost: String?): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (pageHost != null && site(host) == site(pageHost.lowercase())) {
            // First-party requests: block only if the exact host is listed (e.g. ads.site.com)
            if (!block.contains(host)) return false
        }
        if (matches(allow, host)) return false
        return matches(block, host)
    }

    fun cssFor(host: String?): String {
        if (host == null) return ""
        val sb = StringBuilder()
        var h = host.lowercase()
        while (h.contains('.')) {
            siteCss[h]?.let { sb.append(it) }
            h = h.substringAfter('.')
        }
        return sb.toString()
    }

    fun empty(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    fun injectJs(css: String, id: String): String =
        "(function(){if(document.getElementById('$id'))return;var s=document.createElement('style');s.id='$id';" +
                "s.textContent=${org.json.JSONObject.quote(css)};(document.head||document.documentElement).appendChild(s);})();"
}
