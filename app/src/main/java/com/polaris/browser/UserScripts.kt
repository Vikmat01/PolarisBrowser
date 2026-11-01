package com.polaris.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** An installed userscript (.user.js) or userstyle (.user.css). */
data class UScript(
    val id: Long,
    val kind: String,            // "js" | "css"
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val source: String,
    val enabled: Boolean,
    val matches: List<String>,   // human-readable @match/@include/@-moz-document targets
    val excludes: List<String>,
    val runAt: String,           // start | end | idle
    val noFrames: Boolean,
    val grants: List<String>,
    val updateUrl: String,
) {
    val isStyle get() = kind == "css"
}

class ScriptStore(ctx: Context) : SQLiteOpenHelper(ctx, "scripts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE scripts(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, name TEXT, ns TEXT, version TEXT, descr TEXT, " +
                "source TEXT, enabled INTEGER, matches TEXT, excludes TEXT, runat TEXT, noframes INTEGER, grants TEXT, upd TEXT, ts INTEGER)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun all(): List<UScript> = readableDatabase.rawQuery("SELECT * FROM scripts ORDER BY ts", null).use { c ->
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
        fun arr(n: String) = JSONArray(s(n).ifEmpty { "[]" }).let { a -> List(a.length()) { a.getString(it) } }
        buildList {
            while (c.moveToNext()) add(UScript(
                c.getLong(c.getColumnIndexOrThrow("id")), s("kind"), s("name"), s("ns"), s("version"), s("descr"), s("source"),
                c.getInt(c.getColumnIndexOrThrow("enabled")) == 1, arr("matches"), arr("excludes"), s("runat"),
                c.getInt(c.getColumnIndexOrThrow("noframes")) == 1, arr("grants"), s("upd")))
        }
    }

    fun find(name: String, ns: String): UScript? = all().firstOrNull { it.name == name && it.namespace == ns }

    /** Insert or replace (same name + namespace = update, keeps the on/off state). */
    fun save(s: UScript): Long {
        val old = find(s.name, s.namespace)
        val v = ContentValues().apply {
            put("kind", s.kind); put("name", s.name); put("ns", s.namespace); put("version", s.version); put("descr", s.description)
            put("source", s.source); put("enabled", if (old?.enabled ?: s.enabled) 1 else 0)
            put("matches", JSONArray(s.matches).toString()); put("excludes", JSONArray(s.excludes).toString())
            put("runat", s.runAt); put("noframes", if (s.noFrames) 1 else 0); put("grants", JSONArray(s.grants).toString())
            put("upd", s.updateUrl); put("ts", System.currentTimeMillis())
        }
        return if (old != null) { writableDatabase.update("scripts", v, "id=?", arrayOf(old.id.toString())); old.id }
        else writableDatabase.insert("scripts", null, v)
    }

    fun setEnabled(id: Long, on: Boolean) {
        writableDatabase.update("scripts", ContentValues().apply { put("enabled", if (on) 1 else 0) }, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) { writableDatabase.delete("scripts", "id=?", arrayOf(id.toString())) }
}

object UserScripts {

    fun isScriptUrl(path: String?) = path != null && (path.endsWith(".user.js", true) || path.endsWith(".user.css", true))

    fun fetch(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) PolarisBrowser")
        if (c.responseCode in 200..299) c.inputStream.readBytes().toString(Charsets.UTF_8) else null
    } catch (_: Exception) { null }

    // ───────────── parsing ─────────────

    fun parse(src: String, fromUrl: String = ""): UScript? {
        val js = Regex("==UserScript==([\\s\\S]*?)==/UserScript==").find(src)
        if (js != null) return parseMeta(js.groupValues[1], "js", src, fromUrl)
        val css = Regex("==UserStyle==([\\s\\S]*?)==/UserStyle==").find(src)
        if (css != null) return parseMeta(css.groupValues[1], "css", src, fromUrl)
        if (fromUrl.endsWith(".user.css", true) || src.contains("@-moz-document"))
            return parseMeta("", "css", src, fromUrl)
        return null
    }

    private fun parseMeta(block: String, kind: String, src: String, fromUrl: String): UScript {
        val meta = LinkedHashMap<String, MutableList<String>>()
        for (line in block.lines()) {
            val m = Regex("^\\s*(?://)?\\s*@([\\w:.-]+)\\s*(.*)$").find(line) ?: continue
            meta.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].trim())
        }
        fun one(k: String) = meta[k]?.firstOrNull() ?: ""
        val name = meta["name:ru"]?.firstOrNull() ?: one("name").ifEmpty { fromUrl.substringAfterLast('/').substringBefore(".user.") .ifEmpty { "Без названия" } }
        val desc = meta["description:ru"]?.firstOrNull() ?: one("description")
        val matches: List<String>
        val excludes: List<String>
        if (kind == "css") {
            matches = cssTargets(src).ifEmpty { listOf("*") }
            excludes = emptyList()
        } else {
            matches = ((meta["match"] ?: emptyList()) + (meta["include"] ?: emptyList())).ifEmpty { listOf("*") }
            excludes = (meta["exclude"] ?: emptyList()) + (meta["exclude-match"] ?: emptyList())
        }
        val runAt = when (one("run-at")) { "document-start" -> "start"; "document-end" -> "end"; else -> if (kind == "css") "start" else "idle" }
        return UScript(0, kind, name, one("namespace"), one("version"), desc, src, true, matches, excludes, runAt,
            meta.containsKey("noframes"), meta["grant"] ?: emptyList(),
            one("updateURL").ifEmpty { one("downloadURL") }.ifEmpty { one("updateUrl") }.ifEmpty { fromUrl })
    }

    /** Human-readable list of where a userstyle applies. */
    private fun cssTargets(src: String): List<String> {
        val out = mutableListOf<String>()
        Regex("@-moz-document\\s+([^{]+)\\{").findAll(src).forEach { m ->
            Regex("(url|url-prefix|domain|regexp)\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)").findAll(m.groupValues[1]).forEach {
                out.add(when (it.groupValues[1]) { "domain" -> it.groupValues[2]; "regexp" -> "/" + it.groupValues[2] + "/"; else -> it.groupValues[2] + if (it.groupValues[1] == "url-prefix") "*" else "" })
            }
        }
        return out.distinct()
    }

    // ───────────── matching (same rules as in the injected bootstrap) ─────────────

    /** Regex escaping that works in both Kotlin and JavaScript (no \\Q…\\E quoting). */
    private fun esc(s: String) = s.replace(Regex("[\\\\^$.|?*+()\\[\\]{}/-]")) { "\\" + it.value }

    fun patternToRegex(p: String): String {
        val s = p.trim()
        if (s == "*" || s == "<all_urls>") return "^(https?|file|ftp)://.*"
        if (s.length > 2 && s.startsWith("/") && s.endsWith("/")) return s.substring(1, s.length - 1)
        val m = Regex("^(\\*|https?|file|ftp)://([^/]*)(/.*)?$").find(s)
        if (m != null && !s.contains("**")) {
            val scheme = if (m.groupValues[1] == "*") "https?" else esc(m.groupValues[1])
            val host = m.groupValues[2]
            val hostRe = when {
                host == "*" -> "[^/]*"
                host.startsWith("*.") -> "(?:[^/]*\\.)?" + esc(host.substring(2))
                else -> esc(host).replace("\\*", "[^/]*")
            }
            val path = m.groupValues[3].ifEmpty { "/" }
            val pathRe = path.split("*").joinToString(".*") { if (it.isEmpty()) "" else esc(it) }
            return "^$scheme://$hostRe(?::\\d+)?$pathRe$"
        }
        // @include glob
        return "^" + s.split("*").joinToString(".*") { if (it.isEmpty()) "" else esc(it) } + "$"
    }

    fun matches(s: UScript, url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (s.isStyle) return styleMatches(s.source, url)
        fun any(list: List<String>) = list.any { p -> try { Regex(patternToRegex(p)).containsMatchIn(url) } catch (_: Exception) { false } }
        return any(s.matches) && !any(s.excludes)
    }

    private fun styleMatches(src: String, url: String): Boolean {
        if (!src.contains("@-moz-document")) return true
        val host = try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }
        return Regex("(url|url-prefix|domain|regexp)\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)").findAll(src).any {
            val v = it.groupValues[2]
            when (it.groupValues[1]) {
                "url" -> url == v
                "url-prefix" -> url.startsWith(v)
                "domain" -> host == v || host.endsWith(".$v")
                else -> try { Regex(v.replace("\\\\", "\\")).matches(url) } catch (_: Exception) { false }
            }
        }
    }

    // ───────────── bootstrap injected into every page ─────────────

    private fun q(s: String) = JSONObject.quote(s)

    /** One script per page load that decides by itself which userscripts apply to location.href. */
    fun bundle(list: List<UScript>, token: String): String {
        val on = list.filter { it.enabled }
        if (on.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("(function(){if(window.__pzUS)return;window.__pzUS=1;var T=").append(q(token)).append(",B=window.PolarisGM,H=location.href;")
        sb.append(GM_LIB)
        sb.append("var S=[")
        for (s in on) {
            sb.append("{id:").append(s.id).append(",n:").append(q(s.name)).append(",v:").append(q(s.version))
                .append(",d:").append(q(s.description)).append(",r:").append(q(s.runAt)).append(",nf:").append(s.noFrames)
            if (s.isStyle) {
                sb.append(",css:").append(q(s.source)).append("},")
            } else {
                sb.append(",m:[").append(s.matches.joinToString(",") { q(patternToRegex(it)) }).append("]")
                    .append(",x:[").append(s.excludes.joinToString(",") { q(patternToRegex(it)) }).append("]")
                    .append(",f:function(GM_info,GM,unsafeWindow,GM_addStyle,GM_getValue,GM_setValue,GM_deleteValue,GM_listValues,")
                    .append("GM_xmlhttpRequest,GM_openInTab,GM_setClipboard,GM_registerMenuCommand,GM_unregisterMenuCommand,GM_notification,GM_getResourceText,GM_log){\n")
                    .append(s.source).append("\n}},")
            }
        }
        sb.append("];")
        sb.append(RUNNER)
        sb.append("})();")
        return sb.toString()
    }

    private const val GM_LIB = """
function rx(a,u){for(var i=0;i<a.length;i++){try{if(new RegExp(a[i]).test(u))return true}catch(e){}}return false}
function addStyle(css){var s=document.createElement('style');s.textContent=css;
  var put=function(){var p=document.head||document.documentElement;if(p){p.appendChild(s);return true}return false};
  if(!put()){var mo=new MutationObserver(function(){if(put())mo.disconnect()});mo.observe(document,{childList:true,subtree:true})}return s}
var cbs={},seq=0;window.__pzXhrDone=function(id,res){var c=cbs[id];if(!c)return;delete cbs[id];try{
  if(res.error){c.onerror&&c.onerror(res);c.onloadend&&c.onloadend(res);return}
  if(c.responseType==='json'){try{res.response=JSON.parse(res.responseText)}catch(e){res.response=null}}else res.response=res.responseText;
  c.onload&&c.onload(res);c.onloadend&&c.onloadend(res)}catch(e){console.error(e)}};
window.__pzCmds=window.__pzCmds||{};
function api(s){
  var id=s.id;
  function gv(k,d){var r=B?B.get(T,id,String(k)):null;return r==null?d:JSON.parse(r)}
  function sv(k,v){B&&B.set(T,id,String(k),JSON.stringify(v))}
  function dv(k){B&&B.del(T,id,String(k))}
  function lv(){return B?JSON.parse(B.list(T,id)):[]}
  function xhr(o){var cid=++seq;cbs[cid]=o;B&&B.xhr(T,cid,JSON.stringify({method:o.method||'GET',url:o.url,headers:o.headers||{},data:o.data==null?null:String(o.data),timeout:o.timeout||0}));
    return{abort:function(){delete cbs[cid]}}}
  function open(u){B&&B.open(T,String(u))}
  function clip(t){B&&B.clip(T,String(t))}
  function cmd(n,fn){window.__pzCmds[n]=fn;B&&B.cmd(T,String(n));return n}
  function uncmd(n){delete window.__pzCmds[n]}
  function note(t){var x=typeof t==='object'?(t.text||t.title):t;B&&B.note(T,String(x))}
  var info={script:{name:s.n,version:s.v,description:s.d},scriptHandler:'Polaris',version:'1.0'};
  var P=function(f){return function(){var a=arguments;return new Promise(function(ok,no){try{ok(f.apply(null,a))}catch(e){no(e)}})}};
  var GM={info:info,getValue:P(gv),setValue:P(sv),deleteValue:P(dv),listValues:P(lv),addStyle:P(addStyle),openInTab:P(open),setClipboard:P(clip),
    registerMenuCommand:cmd,notification:note,
    xmlHttpRequest:function(o){return new Promise(function(ok,no){var oo=Object.assign({},o);oo.onload=function(r){o.onload&&o.onload(r);ok(r)};oo.onerror=function(r){o.onerror&&o.onerror(r);no(r)};xhr(oo)})}};
  return [info,GM,window,addStyle,gv,sv,dv,lv,xhr,open,clip,cmd,uncmd,note,function(){return ''},function(){console.log.apply(console,arguments)}];
}
function cssFor(src,u){
  var host=location.hostname,out='',i=0,re=/@-moz-document\s+([^{]+)\{/g,m,last=0,any=false;
  while((m=re.exec(src))){any=true;out+=src.slice(last,m.index);var depth=1,j=re.lastIndex;
    while(j<src.length&&depth>0){var ch=src[j];if(ch==='{')depth++;else if(ch==='}')depth--;j++}
    var body=src.slice(re.lastIndex,j-1),ok=false,cr=/(url|url-prefix|domain|regexp)\(\s*['"]?([^'")]+)['"]?\s*\)/g,c;
    while((c=cr.exec(m[1]))){var v=c[2];if(c[1]==='url'&&u===v)ok=true;else if(c[1]==='url-prefix'&&u.indexOf(v)===0)ok=true;
      else if(c[1]==='domain'&&(host===v||host.endsWith('.'+v)))ok=true;else if(c[1]==='regexp'){try{if(new RegExp('^(?:'+v.replace(/\\\\/g,'\\')+')$').test(u))ok=true}catch(e){}}}
    if(ok)out+=body;last=j;re.lastIndex=j}
  out+=src.slice(last);return any||out.trim()?out:''}
"""

    private const val RUNNER = """
var top_=window.top===window;
S.forEach(function(s){
  if(s.nf&&!top_)return;
  if(s.css!=null){var c=cssFor(s.css,H);if(c.trim()){var go=function(){addStyle(c)};go()}return}
  if(!rx(s.m,H)||rx(s.x,H))return;
  var run=function(){try{s.f.apply(window,api(s))}catch(e){console.error('[Polaris userscript] '+s.n,e)}};
  if(s.r==='start')run();
  else if(s.r==='end'){if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run()}
  else{var idle=function(){setTimeout(run,0)};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',idle,{once:true});else idle()}
});
"""
}
