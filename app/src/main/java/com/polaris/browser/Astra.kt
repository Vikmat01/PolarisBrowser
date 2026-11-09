package com.polaris.browser

import android.content.Context
import android.content.Intent

/** Link to the Astra AI assistant (separate app, com.polaris.asta). */
object Astra {
    const val PKG = "com.polaris.asta"
    const val ACTION = "com.polaris.asta.ASK"

    fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    fun ask(ctx: Context, title: String, url: String, text: String) {
        val i = Intent(ACTION).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("kind", "page").putExtra("title", title).putExtra("url", url).putExtra("text", text)
        try { ctx.startActivity(i) } catch (_: Exception) { toast(ctx, "Обнови Astra AI, чтобы спрашивать её о страницах") }
    }
}
