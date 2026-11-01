package com.polaris.browser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/** Day/night by real sunrise & sunset (NOAA almanac algorithm). */
object Sun {

    private fun utHour(dayOfYear: Int, lat: Double, lon: Double, rise: Boolean): Double? {
        val zenith = 90.833
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rise) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l % 360 + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra % 360 + 360) % 360
        val lq = floor(l / 90) * 90
        val raq = floor(ra / 90) * 90
        ra = (ra + lq - raq) / 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(lat))) /
                (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1) return Double.NaN       // never rises (polar night)
        if (cosH < -1) return Double.POSITIVE_INFINITY // never sets (polar day)
        var h = if (rise) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))
        h /= 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        return ((tt - lngHour) % 24 + 24) % 24
    }

    fun isDay(lat: Double, lon: Double, now: Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))): Boolean {
        val doy = now.get(Calendar.DAY_OF_YEAR)
        val r = utHour(doy, lat, lon, true) ?: return true
        val s = utHour(doy, lat, lon, false) ?: return true
        if (r.isNaN()) return false
        if (r.isInfinite()) return true
        val h = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0
        return if (r < s) h in r..s else (h >= r || h < s)
    }

    fun location(ctx: Context, prefs: Prefs): Pair<Double, Double> {
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            try {
                val lm = ctx.getSystemService(LocationManager::class.java)
                val loc = lm.getProviders(true).mapNotNull {
                    @Suppress("MissingPermission") lm.getLastKnownLocation(it)
                }.maxByOrNull { it.time }
                if (loc != null) {
                    prefs.lat = loc.latitude.toFloat(); prefs.lon = loc.longitude.toFloat()
                }
            } catch (_: Exception) {}
        }
        if (!prefs.lat.isNaN()) return prefs.lat.toDouble() to prefs.lon.toDouble()
        // Fallback: longitude from timezone offset, latitude ~ Moscow
        val off = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000.0
        return 55.75 to (off * 15.0)
    }

    fun darkNow(ctx: Context, prefs: Prefs): Boolean = when (prefs.theme) {
        "light" -> false
        "dark" -> true
        "system" -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else -> { val (la, lo) = location(ctx, prefs); !isDay(la, lo) }
    }
}
