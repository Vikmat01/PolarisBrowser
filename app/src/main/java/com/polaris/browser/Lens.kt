package com.polaris.browser

import android.animation.TimeAnimator
import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import kotlin.math.abs

/** Anything glassy that wants the finger's position. */
interface GlassSurface { fun touch(e: MotionEvent) }

object Lens {
    /** Android 13+ and the shader actually compiles on this GPU driver. */
    val supported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 33 && try { RuntimeShader(SHADER); true } catch (_: Throwable) { false }
    }

    // AGSL lens over a snapshot of the page.
    // Near the rim the page is pulled inward (magnified, bent like a droplet).
    // The bend is capped below the rim width so the image never folds/mirrors.
    const val SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float2 origin;
uniform float vscale;
uniform float scale;
uniform float blur;
uniform float edge;
uniform float strength;
uniform float chroma;
uniform half4 tint;
uniform float sat;
uniform float2 touch;
uniform float touchA;
uniform float lightAng;
uniform float rimK;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 px(float2 p) {
    return content.eval((origin + p * vscale) * scale);
}

half4 frosted(float2 p) {
    if (blur < 0.5) { return px(p); }
    half4 acc = px(p) * 2.0;
    float ws = 2.0;
    for (int i = 0; i < 10; i++) {
        float fi = float(i);
        float r = sqrt((fi + 0.5) / 10.0) * blur;
        float a = fi * 2.39996;
        acc += px(p + float2(cos(a), sin(a)) * r);
        ws += 1.0;
    }
    return acc / ws;
}

half4 main(float2 p) {
    float2 c = size * 0.5;
    float2 q = p - c;
    float d = sdRound(q, c, radius);
    if (d > 0.75) { return half4(0.0); }
    float aa = clamp(0.5 - d, 0.0, 1.0);
    float inside = max(-d, 0.0);

    float e = 1.0;
    float2 g = float2(sdRound(q + float2(e, 0.0), c, radius) - sdRound(q - float2(e, 0.0), c, radius),
                      sdRound(q + float2(0.0, e), c, radius) - sdRound(q - float2(0.0, e), c, radius));
    float2 n = g / max(length(g), 0.0001);

    float t = clamp(1.0 - inside / edge, 0.0, 1.0);
    float k = t * t;
    float2 off = n * k * strength;

    half3 col;
    if (k > 0.03 && chroma > 0.0) {
        float2 ca = n * k * k * strength * chroma;
        col = half3(frosted(p - off - ca).r, frosted(p - off).g, frosted(p - off + ca).b);
    } else {
        col = frosted(p - off).rgb;
    }

    half l = dot(col, half3(0.299, 0.587, 0.114));
    col = mix(half3(l), col, half(sat));
    col = mix(col, tint.rgb, tint.a);
    col += half(k * k * 0.07 * rimK);

    float band = clamp(1.0 - inside / 1.8, 0.0, 1.0);
    float ang = atan(n.y, n.x);
    float f1 = pow(0.5 + 0.5 * cos(ang - lightAng), 4.0);
    float f2 = pow(0.5 + 0.5 * cos(ang - lightAng - 3.14159), 4.0);
    col += half(band * (0.14 + 0.62 * f1 + 0.36 * f2) * rimK);

    float dt = length(p - touch);
    col += half(touchA * 0.09 * exp(-dt * dt / 4500.0));

    return half4(clamp(col, 0.0, 1.0) * half(aa), half(aa));
}
"""
}

/**
 * A snapshot of the browser's own window (floating glass windows live in their
 * own surfaces, so they never see themselves). Optionally also keeps a
 * pre-smoothed copy for heavily frosted surfaces, so they stay grain-free.
 */
class Backdrop(private val act: Activity, private val scale: Float = 0.5f, private val makeSoft: Boolean = false) {
    var bitmap: Bitmap? = null; private set
    var shader: BitmapShader? = null; private set
    var softShader: BitmapShader? = null; private set
    var softScale = scale / 8f; private set
    val rect = Rect()
    private val pool = ArrayDeque<Bitmap>()
    private var busy = false
    private val listeners = mutableListOf<() -> Unit>()
    val pixelScale get() = scale

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun linear(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        if (Build.VERSION.SDK_INT >= 33) filterMode = BitmapShader.FILTER_MODE_LINEAR
    }

    /** Copy [src] (window coordinates) asynchronously; listeners fire when ready. */
    fun capture(src: Rect, done: ((Boolean) -> Unit)? = null) {
        if (busy || src.isEmpty) { done?.invoke(false); return }
        val w = (src.width() * scale).toInt().coerceAtLeast(1)
        val h = (src.height() * scale).toInt().coerceAtLeast(1)
        while (pool.isNotEmpty() && (pool.first().width != w || pool.first().height != h)) pool.removeFirst()
        val target = if (pool.size >= 2) pool.removeFirst() else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        busy = true
        val copy = Rect(src)
        try {
            PixelCopy.request(act.window, copy, target, { res ->
                busy = false
                if (res == PixelCopy.SUCCESS) {
                    bitmap?.let { pool.addLast(it) }
                    bitmap = target
                    rect.set(copy)
                    shader = linear(target)
                    if (makeSoft) {
                        // successive halving with bilinear filtering ≈ smooth gaussian
                        var s: Bitmap = target
                        var f = 1
                        while (f < 8 && s.width > 8 && s.height > 8) {
                            s = Bitmap.createScaledBitmap(s, s.width / 2, s.height / 2, true); f *= 2
                        }
                        softScale = scale * s.width / target.width
                        softShader = linear(s)
                    }
                    listeners.toList().forEach { it() }
                    done?.invoke(true)
                } else { pool.addLast(target); done?.invoke(false) }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { busy = false; done?.invoke(false) }
    }
}

/**
 * Liquid glass that refracts what is under it on screen. Highlights rest at the
 * top-left; with a finger on the glass the rim light swings toward it and a
 * soft specular follows.
 */
@RequiresApi(33)
class LensDrawable(
    private val host: View,
    private var backdrop: Backdrop,
    private val radiusDp: Float,
    var blurDp: Float,
    var glassTint: Int,
    var tintAmount: Float,
    var dark: Boolean,
    var soft: Boolean = false,
) : Drawable(), GlassSurface {
    private val d = host.resources.displayMetrics.density
    private val rs = RuntimeShader(Lens.SHADER)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG)
    private val loc = IntArray(2)
    private val lastLoc = IntArray(2)
    var strengthDp = 10f
    var edgeDp = 22f
    var chroma = 0.25f
    var saturation = 1.4f
    var rimScale = 1f

    private val restAngle = Math.toRadians(-125.0).toFloat()
    private var angle = restAngle; private var tAngle = restAngle
    private var tx = 0f; private var ty = 0f; private var ta = 0f; private var tta = 0f
    private var ticking = false
    private val ticker = TimeAnimator().apply {
        setTimeListener { a, _, dt ->
            val k = (dt / 16f * 0.18f).coerceIn(0.05f, 0.6f)
            var dd = tAngle - angle
            while (dd > Math.PI) dd -= (2 * Math.PI).toFloat()
            while (dd < -Math.PI) dd += (2 * Math.PI).toFloat()
            angle += dd * k
            ta += (tta - ta) * (if (tta > ta) 0.35f else 0.09f)
            invalidateSelf()
            if (abs(dd) < 0.003f && abs(tta - ta) < 0.004f) { ta = tta; a.end(); ticking = false }
        }
    }

    private val onBackdrop: () -> Unit = { invalidateSelf() }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        host.getLocationOnScreen(loc)
        if (loc[0] != lastLoc[0] || loc[1] != lastLoc[1]) { lastLoc[0] = loc[0]; lastLoc[1] = loc[1]; invalidateSelf() }
        true
    }

    init {
        backdrop.addListener(onBackdrop)
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
                backdrop.removeListener(onBackdrop); backdrop.addListener(onBackdrop)
            }
            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                backdrop.removeListener(onBackdrop)
                ticker.cancel()
            }
        })
        if (host.isAttachedToWindow) host.viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val r = minOf(radiusDp * d, h / 2f, w / 2f)
        val bs = if (soft) backdrop.softShader else backdrop.shader
        if (bs == null) {
            fallback.color = (glassTint and 0x00FFFFFF) or 0xE6000000.toInt()
            c.drawRoundRect(RectF(b), r, r, fallback)
            return
        }
        host.getLocationOnScreen(loc)
        lastLoc[0] = loc[0]; lastLoc[1] = loc[1]
        val vs = if (host.scaleX > 0f) host.scaleX else 1f
        val ox = loc[0] + b.left * vs - backdrop.rect.left
        val oy = loc[1] + b.top * vs - backdrop.rect.top
        val edge = minOf(edgeDp * d, h * 0.45f, w * 0.45f)
        rs.setInputShader("content", bs)
        rs.setFloatUniform("size", w, h)
        rs.setFloatUniform("radius", r)
        rs.setFloatUniform("origin", ox, oy)
        rs.setFloatUniform("vscale", vs)
        rs.setFloatUniform("scale", if (soft) backdrop.softScale else backdrop.pixelScale)
        rs.setFloatUniform("blur", blurDp * d)
        rs.setFloatUniform("edge", edge)
        // k = t², max slope 2/edge → strength < edge/2 keeps the mapping monotonic (no mirroring)
        rs.setFloatUniform("strength", minOf(strengthDp * d, edge * 0.46f))
        rs.setFloatUniform("chroma", chroma)
        rs.setFloatUniform("tint", Color.red(glassTint) / 255f, Color.green(glassTint) / 255f, Color.blue(glassTint) / 255f, tintAmount)
        rs.setFloatUniform("sat", saturation)
        rs.setFloatUniform("touch", tx, ty)
        rs.setFloatUniform("touchA", if (h > 160 * d) ta * 0.5f else ta)
        rs.setFloatUniform("lightAng", angle)
        rs.setFloatUniform("rimK", (if (dark) 0.6f else 0.95f) * rimScale)
        paint.shader = rs
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.drawRect(0f, 0f, w, h, paint)
        c.restore()
    }

    override fun touch(e: MotionEvent) {
        val b = bounds
        if (b.isEmpty) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                tx = e.x - b.left; ty = e.y - b.top
                tAngle = kotlin.math.atan2(e.y - b.exactCenterY(), e.x - b.exactCenterX())
                tta = 1f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tAngle = restAngle; tta = 0f }
        }
        if (!ticking) { ticking = true; ticker.start() }
    }

    override fun getOutline(o: Outline) { o.setRoundRect(bounds, minOf(radiusDp * d, bounds.height() / 2f)) }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
