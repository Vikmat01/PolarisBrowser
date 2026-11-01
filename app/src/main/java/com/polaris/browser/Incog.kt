package com.polaris.browser

import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Everything incognito-specific in one place: profile, cookies, screenshot protection. */
object Incog {
    const val PROFILE = "polaris-incognito"
    val profiles get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Cookie jar that belongs to the tab: the incognito profile's own one when available. */
    fun cookies(incognito: Boolean): CookieManager =
        if (incognito && profiles) try { ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager }
        catch (_: Exception) { CookieManager.getInstance() }
        else CookieManager.getInstance()

    fun cookie(incognito: Boolean, url: String): String? = try { cookies(incognito).getCookie(url) } catch (_: Exception) { null }

    fun attach(w: WebView) {
        if (!profiles) return
        try { ProfileStore.getInstance().getOrCreateProfile(PROFILE); WebViewCompat.setProfile(w, PROFILE) } catch (_: Exception) {}
    }

    /** Delete the whole incognito profile (cookies, storage, cache). Safe when no incognito WebView exists. */
    fun wipe() {
        if (!profiles) return
        try { ProfileStore.getInstance().deleteProfile(PROFILE) } catch (_: Exception) {}
    }

    /** Block screenshots/screen recording and blank the Recents thumbnail. */
    fun secure(w: Window?, on: Boolean) {
        w ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun isSecure(w: Window?) = w != null && (w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
}
