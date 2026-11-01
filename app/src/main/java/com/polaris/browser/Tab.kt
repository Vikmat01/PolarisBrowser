package com.polaris.browser

import android.graphics.Bitmap
import android.webkit.WebView

class Tab(
    val id: Long,
    val incognito: Boolean,
    var pendingUrl: String? = null,
    var parentId: Long = -1,
) {
    var web: WebView? = null
    var url: String = pendingUrl ?: ""
    var title: String = ""
    var progress: Int = 100
    var favicon: Bitmap? = null
    var thumb: Bitmap? = null
    var blocked: Int = 0
    var desktop: Boolean = false
    var readerable: Boolean = false
    var themeColor: Int? = null
    var lastActive: Long = System.currentTimeMillis()
    /** Popup target: WebView must stay empty until the opener hands over the transport. */
    var noAutoLoad = false
    /** GM_registerMenuCommand names registered by userscripts on the current page */
    val cmds = mutableListOf<String>()

    val isHome get() = url.isEmpty() || url.startsWith(HOME)

    val host: String
        get() = try { android.net.Uri.parse(url).host?.removePrefix("www.") ?: "" } catch (_: Exception) { "" }

    val displayTitle: String
        get() = when {
            isHome -> if (incognito) "Инкогнито" else "Новая вкладка"
            title.isNotBlank() -> title
            else -> host.ifEmpty { url }
        }

    companion object {
        const val HOME = "file:///android_asset/home.html"
        private var seq = System.currentTimeMillis()
        fun nextId() = ++seq
    }
}
