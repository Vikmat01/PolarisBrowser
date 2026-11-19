package com.polaris.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Polaris' own downloader. The system DownloadManager on some firmwares (Honor/Huawei among
 * them) leaves jobs sitting "pending" forever; this one runs in our foreground service,
 * follows redirects, resumes after network drops and writes straight into Downloads.
 */
object Downloader {
    const val QUEUED = 0; const val RUNNING = 1; const val DONE = 2; const val FAILED = 3; const val RETRYING = 4

    class Job(
        val id: Long, val url: String, @Volatile var name: String, @Volatile var mime: String,
        val headers: Map<String, String>, val record: Boolean,
    ) {
        @Volatile var state = QUEUED
        @Volatile var done = 0L
        @Volatile var total = -1L
        @Volatile var uri: Uri? = null
        @Volatile var cancelled = false
        @Volatile var error = ""
    }

    val jobs = ConcurrentHashMap<Long, Job>()
    private val pool = Executors.newFixedThreadPool(3)
    private val tmpIds = AtomicLong(-1)
    private const val CH = "downloads"

    fun active() = jobs.values.count { it.state == QUEUED || it.state == RUNNING || it.state == RETRYING }

    /** Start a download; returns its job id (the Db row id when recorded). */
    fun start(ctx: Context, db: Db?, url: String, name: String, mime: String, headers: Map<String, String>): Long {
        val app = ctx.applicationContext
        val id = db?.addDownload(0, name, url, mime) ?: tmpIds.getAndDecrement()
        val j = Job(id, url, name, mime, headers, db != null)
        jobs[id] = j
        DownloadService.ping(app)
        pool.execute { run(app, db, j) }
        return id
    }

    fun cancel(id: Long) { jobs[id]?.cancelled = true }

    private fun run(ctx: Context, db: Db?, j: Job) {
        var attempt = 0
        while (!j.cancelled) {
            try {
                j.state = RUNNING
                fetch(ctx, j)
                if (j.cancelled) break
                j.state = DONE
                db?.finishDownload(j.id, j.name, j.mime, j.total.coerceAtLeast(j.done), j.uri.toString(), DONE)
                notifyDone(ctx, j)
                break
            } catch (e: Exception) {
                if (j.cancelled) break
                attempt++
                if (attempt > 5 || e is HttpError) {
                    j.state = FAILED
                    j.error = (e as? HttpError)?.message ?: "Нет соединения"
                    db?.finishDownload(j.id, j.name, j.mime, j.total, j.uri?.toString() ?: "", FAILED)
                    notifyFailed(ctx, j)
                    break
                }
                j.state = RETRYING
                try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
            }
        }
        if (j.cancelled) {
            j.uri?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
            jobs.remove(j.id)
        }
        DownloadService.ping(ctx)
    }

    private class HttpError(msg: String) : Exception(msg)

    private fun open(j: Job, from: Long): HttpURLConnection {
        var url = URL(j.url)
        repeat(10) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 30_000
            j.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw HttpError("Ошибка сервера ($code)")
                c.disconnect()
                url = URL(url, loc)
                return@repeat
            }
            if (code == 416 && from > 0) { c.disconnect(); return open(j, 0) }
            if (code >= 400) { c.disconnect(); throw HttpError("Ошибка сервера ($code)") }
            return c
        }
        throw HttpError("Слишком много перенаправлений")
    }

    private fun fetch(ctx: Context, j: Job) {
        val resume = j.uri != null && j.done > 0
        val c = open(j, if (resume) j.done else 0)
        try {
            val partial = c.responseCode == 206
            if (!partial) j.done = 0
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1
            j.total = if (len < 0) -1 else if (partial) j.done + len else len
            if (j.mime.isEmpty() || j.mime == "application/octet-stream") {
                c.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }?.let { j.mime = it }
            }
            val cr = ctx.contentResolver
            if (j.uri == null) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, j.name)
                    put(MediaStore.Downloads.MIME_TYPE, j.mime.ifEmpty { "application/octet-stream" })
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                j.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw HttpError("Нет доступа к папке «Загрузки»")
                // the system may have renamed it ("file (1).apk")
                try {
                    cr.query(j.uri!!, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { q ->
                        if (q.moveToFirst()) q.getString(0)?.let { j.name = it }
                    }
                } catch (_: Exception) {}
            }
            val out = cr.openOutputStream(j.uri!!, if (partial) "wa" else "wt") ?: throw HttpError("Не удалось записать файл")
            out.use { o ->
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        if (j.cancelled) return
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        j.done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 700) { last = now; DownloadService.ping(ctx) }
                    }
                }
            }
            if (j.total > 0 && j.done < j.total) throw java.io.IOException("short read")
            cr.update(j.uri!!, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } finally {
            c.disconnect()
        }
    }

    // ───────── notifications ─────────

    fun channel(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        return CH
    }

    fun openIntent(ctx: Context, j: Job): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(j.uri, j.mime.ifEmpty { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notifyDone(ctx: Context, j: Job) {
        val pi = PendingIntent.getActivity(ctx, j.id.toInt(), openIntent(ctx, j), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, channel(ctx))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(j.name).setContentText("Загрузка завершена")
            .setContentIntent(pi).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(("d" + j.id).hashCode(), n) } catch (_: Exception) {}
    }

    private fun notifyFailed(ctx: Context, j: Job) {
        val n = Notification.Builder(ctx, channel(ctx))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(j.name).setContentText("Не удалось скачать: ${j.error}")
            .setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(("d" + j.id).hashCode(), n) } catch (_: Exception) {}
    }

    fun fmt(ctx: Context, b: Long): String = android.text.format.Formatter.formatShortFileSize(ctx, b)

    fun status(ctx: Context, j: Job): String = when (j.state) {
        QUEUED -> "Подключение…"
        RUNNING -> if (j.total > 0) "Загрузка ${j.done * 100 / j.total}% · ${fmt(ctx, j.done)} из ${fmt(ctx, j.total)}"
                   else "Загрузка · ${fmt(ctx, j.done)}"
        RETRYING -> "Ждём сеть… · ${fmt(ctx, j.done)}"
        DONE -> "Готово · ${fmt(ctx, j.total.coerceAtLeast(j.done))}"
        else -> "Ошибка: ${j.error} · нажмите, чтобы повторить"
    }
}

/** Keeps downloads alive while the app is in the background; shows overall progress. */
class DownloadService : Service() {
    companion object {
        fun ping(ctx: Context) {
            val i = Intent(ctx, DownloadService::class.java)
            try {
                if (Downloader.active() > 0) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val act = Downloader.jobs.values.filter { it.state != Downloader.DONE && it.state != Downloader.FAILED && !it.cancelled }
        if (act.isEmpty()) {
            // we may have been started with startForegroundService: satisfy it, then go away
            try { goForeground(Notification.Builder(this, Downloader.channel(this))
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Загрузки").build()) } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val done = act.sumOf { it.done }
        val total = if (act.all { it.total > 0 }) act.sumOf { it.total } else -1L
        val title = if (act.size == 1) act[0].name else "Загрузка файлов: ${act.size}"
        val tap = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, Downloader.channel(this))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(tap)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false)
            .setContentText("${Downloader.fmt(this, done)} из ${Downloader.fmt(this, total)}")
        else b.setProgress(0, 0, true).setContentText(Downloader.fmt(this, done))
        try { goForeground(b.build()) } catch (_: Exception) {}
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(77, n)
    }
}
