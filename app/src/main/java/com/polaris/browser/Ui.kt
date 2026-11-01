package com.polaris.browser

import android.animation.ValueAnimator
import androidx.core.animation.doOnEnd
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.widget.*
import kotlin.math.abs

val Context.density get() = resources.displayMetrics.density
fun Context.dp(v: Number): Int = (v.toFloat() * density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

val EASE_OUT = PathInterpolator(0.2f, 0f, 0f, 1f)
val SPRING = OvershootInterpolator(1.1f)

/** Accent presets: key, name, accent(light), accent(dark), aurora light, aurora dark. */
data class Accent(val key: String, val name: String, val light: Int, val dark: Int, val auroraL: IntArray, val auroraD: IntArray)

val ACCENTS = listOf(
    Accent("ocean", "Океан", 0xFF2F6BFF.toInt(), 0xFF7FA6FF.toInt(),
        intArrayOf(0xFF8FB0FF.toInt(), 0xFFB79CFF.toInt(), 0xFF8EE6D8.toInt()), intArrayOf(0xFF2343D6.toInt(), 0xFF5B34D1.toInt(), 0xFF0D6E68.toInt())),
    Accent("violet", "Фиалка", 0xFF7A3DFF.toInt(), 0xFFB79BFF.toInt(),
        intArrayOf(0xFFC2A8FF.toInt(), 0xFFF2A6F5.toInt(), 0xFFA9B7FF.toInt()), intArrayOf(0xFF4E1FB0.toInt(), 0xFF8E1A6B.toInt(), 0xFF2E2F9E.toInt())),
    Accent("rose", "Роза", 0xFFE2336F.toInt(), 0xFFFF8DB2.toInt(),
        intArrayOf(0xFFFFB3CF.toInt(), 0xFFFFB0A0.toInt(), 0xFFE2C4FF.toInt()), intArrayOf(0xFF97164A.toInt(), 0xFFB3243B.toInt(), 0xFF5E1F94.toInt())),
    Accent("mint", "Мята", 0xFF0B9E78.toInt(), 0xFF45E0B2.toInt(),
        intArrayOf(0xFF9CF0CF.toInt(), 0xFF96EDF2.toInt(), 0xFFB5DCFF.toInt()), intArrayOf(0xFF05624A.toInt(), 0xFF0B6F78.toInt(), 0xFF0B4F80.toInt())),
    Accent("sunset", "Закат", 0xFFEE6416.toInt(), 0xFFFFA25A.toInt(),
        intArrayOf(0xFFFFC796.toInt(), 0xFFFFADB0.toInt(), 0xFFFFE08A.toInt()), intArrayOf(0xFF9A3010.toInt(), 0xFF9C163A.toInt(), 0xFF7E4A0A.toInt())),
    Accent("ruby", "Рубин", 0xFFDE2F35.toInt(), 0xFFFF7B7E.toInt(),
        intArrayOf(0xFFFFA9A9.toInt(), 0xFFFFB5D6.toInt(), 0xFFFFC9A0.toInt()), intArrayOf(0xFF8F1717.toInt(), 0xFF7E1648.toInt(), 0xFF6E2A0F.toInt())),
    Accent("lime", "Лайм", 0xFF4F9A08.toInt(), 0xFFA8EA3C.toInt(),
        intArrayOf(0xFFD3F78E.toInt(), 0xFFB0F2C2.toInt(), 0xFFFBEA84.toInt()), intArrayOf(0xFF3A5E10.toInt(), 0xFF14613A.toInt(), 0xFF6B4B10.toInt())),
    Accent("graphite", "Графит", 0xFF3F4856.toInt(), 0xFFD5DCE6.toInt(),
        intArrayOf(0xFFDCE0E7.toInt(), 0xFFC9CED8.toInt(), 0xFFE6E9EF.toInt()), intArrayOf(0xFF20252E.toInt(), 0xFF323A47.toInt(), 0xFF15181E.toInt())),
)

fun accentByKey(k: String) = ACCENTS.firstOrNull { it.key == k } ?: ACCENTS[0]

/** Colour set for the current light/dark mode + accent + glass density. */
data class Palette(
    val dark: Boolean,
    val bg: Int,
    val glass: Int,
    val glassStrong: Int,
    val text: Int,
    val dim: Int,
    val accent: Int,
    val field: Int,
    val incognito: Int,
    val aurora: IntArray,
    val key: String,
) {
    val ripple get() = (accent and 0x00FFFFFF) or 0x40000000
    val onAccent get() = if (luma(accent) > 160) 0xFF111111.toInt() else Color.WHITE

    companion object {
        fun build(dark: Boolean, accentKey: String, glassLevel: String): Palette {
            val a = accentByKey(accentKey)
            val acc = if (dark) a.dark else a.light
            val base = if (dark) 0x141826 else 0xFFFFFF
            // tint the glass very slightly with the accent so it never looks grey/milky
            val tinted = mix(base, acc and 0xFFFFFF, if (dark) 0.10f else 0.05f)
            val alpha = when (glassLevel) {
                "clear" -> if (dark) 0x66 else 0x5C
                "solid" -> if (dark) 0xE0 else 0xE6
                else -> if (dark) 0x99 else 0x8C
            }
            return Palette(
                dark = dark,
                bg = if (dark) 0xFF090B12.toInt() else 0xFFF3F5FA.toInt(),
                glass = (alpha shl 24) or tinted,
                glassStrong = ((alpha + 0x30).coerceAtMost(0xF2) shl 24) or tinted,
                text = if (dark) 0xFFF5F7FA.toInt() else 0xFF0E1320.toInt(),
                dim = if (dark) 0xFFA3ACBB.toInt() else 0xFF4D5666.toInt(),
                accent = acc,
                field = if (dark) 0x1FFFFFFF else 0x12000000,
                incognito = if (dark) 0xFFC5CCD8.toInt() else 0xFF3F4856.toInt(),
                aurora = if (dark) a.auroraD else a.auroraL,
                key = "$dark|$accentKey|$glassLevel",
            )
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
            val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
            val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
            return (r shl 16) or (g shl 8) or bl
        }

        fun luma(c: Int) = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)

        var accentKey = "ocean"
        var glassLevel = "frost"
        val DARK: Palette get() = build(true, accentKey, glassLevel)
        val LIGHT: Palette get() = build(false, accentKey, glassLevel)
        var cur: Palette = build(false, "ocean", "frost")

        fun forPrefs(ctx: Context, prefs: Prefs): Palette {
            accentKey = prefs.accent; glassLevel = prefs.glass
            return build(Sun.darkNow(ctx, prefs), prefs.accent, prefs.glass)
        }
    }
}

/**
 * Liquid-glass surface: tinted translucent fill, a specular sheen on the
 * upper edge, and a light-catching rim (bright top-left → faint middle →
 * soft bottom-right), plus a hairline for definition. Drawn by hand so
 * edges stay crisp at any density.
 */
class GlassDrawable(
    private val radius: Float,
    private val fill: Int,
    private val dark: Boolean,
    private val density: Float,
    private val glowTint: Int = Color.WHITE,
) : Drawable(), GlassSurface {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * density }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density
        color = if (dark) 0x55000000 else 0x220B1530
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF(); private val ri = RectF(); private val rin = RectF()
    private val m = Matrix()
    private var rimShader: SweepGradient? = null
    private var innerShader: SweepGradient? = null
    private var sheenShader: RadialGradient? = null
    private var glowShader: RadialGradient? = null

    // Highlights rest at the top-left; while a finger is on the glass they swing
    // toward it and the sheen slides under it, then flow back on release.
    private val restAngle = Math.toRadians(-118.0).toFloat()
    private val restSx = 0.32f; private val restSy = 0.0f
    private var angle = restAngle; private var tAngle = restAngle
    private var sx = restSx; private var sy = restSy; private var tSx = restSx; private var tSy = restSy
    private var tx = 0f; private var ty = 0f; private var glowA = 0f; private var tGlow = 0f
    private var ticking = false
    private val ticker = android.animation.TimeAnimator().apply {
        setTimeListener { anim, _, dt ->
            val k = (dt / 16f * 0.16f).coerceIn(0.05f, 0.5f)
            var d = tAngle - angle
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            angle += d * k
            sx += (tSx - sx) * k; sy += (tSy - sy) * k
            glowA += (tGlow - glowA) * (if (tGlow > glowA) 0.35f else 0.1f)
            invalidateSelf()
            if (abs(d) < 0.003f && abs(tSx - sx) < 0.002f && abs(tSy - sy) < 0.002f && abs(tGlow - glowA) < 0.004f) {
                glowA = tGlow; anim.end(); ticking = false
            }
        }
    }

    override fun onBoundsChange(b: Rect) {
        r.set(b)
        val i = rim.strokeWidth / 2
        ri.set(b.left + i, b.top + i, b.right - i, b.bottom - i)
        val j = inner.strokeWidth / 2 + rim.strokeWidth
        rin.set(b.left + j, b.top + j, b.right - j, b.bottom - j)
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        // Two highlights on opposite sides (light + its reflection), like real glass.
        val hi = if (dark) 0xCCFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val mid = if (dark) 0x14FFFFFF else 0x33FFFFFF
        val ref = if (dark) 0x66FFFFFF else 0xB3FFFFFF.toInt()
        rimShader = SweepGradient(cx, cy, intArrayOf(hi, mid, mid, ref, mid, mid, hi),
            floatArrayOf(0f, 0.14f, 0.36f, 0.5f, 0.64f, 0.86f, 1f))
        innerShader = SweepGradient(cx, cy, intArrayOf(if (dark) 0x30FFFFFF else 0x66FFFFFF, 0x00FFFFFF, 0x00FFFFFF,
            if (dark) 0x14FFFFFF else 0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF, if (dark) 0x30FFFFFF else 0x66FFFFFF),
            floatArrayOf(0f, 0.12f, 0.38f, 0.5f, 0.62f, 0.88f, 1f))
        sheenShader = RadialGradient(0f, 0f, 1f, intArrayOf(if (dark) 0x2EFFFFFF else 0x73FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        val gt = glowTint and 0x00FFFFFF
        glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(gt or (if (dark) 0x2E000000 else 0x40000000), gt or 0x0C000000, gt), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        rim.shader = rimShader; inner.shader = innerShader; sheen.shader = sheenShader; glow.shader = glowShader
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rr = minOf(radius, r.height() / 2)
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val deg = Math.toDegrees(angle.toDouble()).toFloat()

        c.drawRoundRect(r, rr, rr, fillPaint)

        // finger glow (subtle)
        if (glowA > 0.01f) {
            m.reset()
            val gr = density * 64f
            m.setScale(gr, gr); m.postTranslate(tx, ty)
            glowShader?.setLocalMatrix(m)
            glow.alpha = (glowA * 160).toInt()
            c.drawRoundRect(r, rr, rr, glow)
        }

        // one even hairline, a touch brighter on top — no streaks, no bevel
        if (edgeShaderH != h) {
            edgeShaderH = h
            edge.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                if (dark) 0x2EFFFFFF else 0x1F000000, if (dark) 0x14FFFFFF else 0x14000000, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(ri, rr, rr, edge)
    }

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1f, density * 0.75f) }
    private var edgeShaderH = -1f

    /** Feed touches from the host view: highlights and sheen follow the finger. */
    override fun touch(e: MotionEvent) {
        val b = bounds
        if (b.isEmpty) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                tx = e.x; ty = e.y
                tAngle = kotlin.math.atan2(e.y - b.exactCenterY(), e.x - b.exactCenterX())
                tSx = ((e.x - b.left) / b.width()).coerceIn(-0.1f, 1.1f)
                tSy = ((e.y - b.top) / b.height()).coerceIn(-0.2f, 1.2f)
                // big panels (sheets) only get a whisper of glow
                tGlow = if (b.height() > density * 120) 0.35f else 1f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tAngle = restAngle; tSx = restSx; tSy = restSy; tGlow = 0f
            }
        }
        if (!ticking) { ticking = true; ticker.start() }
    }

    override fun getOutline(o: Outline) { o.setRoundRect(bounds, minOf(radius, bounds.height() / 2f)) }
    override fun setAlpha(a: Int) { fillPaint.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Containers that forward touches to their glass background (finger glow) and optionally wobble like jelly. */
class LiquidFrame(ctx: Context, private val jelly: Boolean = false) : FrameLayout(ctx) {
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        (background as? GlassSurface)?.touch(e)
        if (jelly) jellyTouch(this, e)
        return super.dispatchTouchEvent(e)
    }
}

class LiquidLinear(ctx: Context, vertical: Boolean, private val jelly: Boolean = false) : LinearLayout(ctx) {
    init { orientation = if (vertical) VERTICAL else HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        (background as? GlassSurface)?.touch(e)
        if (jelly) jellyTouch(this, e)
        return super.dispatchTouchEvent(e)
    }
}

private fun jellyTouch(v: View, e: MotionEvent) {
    when (e.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            v.pivotX = e.x; v.pivotY = e.y
            v.animate().scaleX(1.012f).scaleY(0.988f).setDuration(110).setInterpolator(DecelerateInterpolator()).start()
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
            v.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(4f)).start()
    }
}

fun glass(ctx: Context, radiusDp: Float, p: Palette = Palette.cur, strong: Boolean = false, tint: Int? = null): GlassDrawable =
    GlassDrawable(ctx.dp(radiusDp).toFloat(), tint ?: if (strong) p.glassStrong else p.glass, p.dark, ctx.density,
        Palette.mix(0xFFFFFF, p.accent and 0xFFFFFF, 0.35f))

fun rounded(ctx: Context, color: Int, radiusDp: Float) = GradientDrawable().apply {
    cornerRadius = ctx.dp(radiusDp).toFloat(); setColor(color)
}

fun ripple(ctx: Context, content: Drawable?, radiusDp: Float = 14f, color: Int = Palette.cur.ripple): Drawable {
    val mask = rounded(ctx, Color.WHITE, radiusDp)
    return RippleDrawable(ColorStateList.valueOf(color), content, mask)
}

/** Springy press feedback: shrink on touch, bounce back on release. */
@SuppressLint("ClickableViewAccessibility")
fun View.pressable(scale: Float = 0.9f): View {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3.2f)).start()
        }
        false
    }
    return this
}

fun View.pop() {
    animate().cancel()
    scaleX = 0.7f; scaleY = 0.7f
    animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
}

fun View.animateIn(index: Int, dy: Float = 18f) {
    alpha = 0f; translationY = dp(dy).toFloat()
    animate().alpha(1f).translationY(0f).setStartDelay(30L + index * 22L).setDuration(320).setInterpolator(EASE_OUT).start()
}

fun icon(ctx: Context, path: String, color: Int = Palette.cur.text) = IconDrawable(path, color)

fun iconButton(ctx: Context, path: String, sizeDp: Int = 44, iconDp: Int = 22, color: Int = Palette.cur.text, onClick: (View) -> Unit): ImageView =
    ImageView(ctx).apply {
        setImageDrawable(icon(ctx, path, color))
        val pad = ctx.dp((sizeDp - iconDp) / 2f)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
        background = ripple(ctx, null, sizeDp / 2f)
        isClickable = true; isFocusable = true
        setOnClickListener(onClick)
        pressable(0.84f)
    }

fun label(ctx: Context, text: CharSequence, sp: Float = 15f, color: Int = Palette.cur.text, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }

fun vbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
fun hbox(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun blurAvailable(act: Activity) = Build.VERSION.SDK_INT >= 31 && act.windowManager.isCrossWindowBlurEnabled

/**
 * Bottom sheet in its own window: the system blurs everything behind it,
 * the card itself is liquid glass. Springs in, slides out, can be dragged
 * down by its handle.
 */
class GlassSheet(val act: Activity, val fullHeight: Boolean = false) : Dialog(act, android.R.style.Theme_Translucent_NoTitleBar) {
    val content: LinearLayout = vbox(act)
    private val p = Palette.cur
    private val root = FrameLayout(act)
    private val card: LiquidLinear
    private var closing = false
    private var rows = 0
    private var bar: GlassBar? = null
    private var barRect: Rect? = null
    private var baseTy = 0f
    private var morph: ValueAnimator? = null

    /** Grow the sheet out of the address-bar capsule instead of sliding up from the screen edge. */
    fun fromBar(b: GlassBar): GlassSheet {
        val r = b.capsuleRect() ?: return this
        bar = b; barRect = r
        val sw = act.resources.displayMetrics.widthPixels
        (card.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = r.left; rightMargin = sw - r.right }
        return this
    }

    private fun morphStep(f: Float) {
        val w = card.width; val h = card.height
        val bh = barRect?.height() ?: h
        val top = (h - bh) * (1f - f)
        card.background?.setBounds(0, top.toInt(), w, h)
        card.invalidateOutline(); card.invalidate()
        // cross-fade with the capsule at the small end so the hand-off is seamless
        card.alpha = (f / 0.3f).coerceIn(0f, 1f)
        val a = ((f - 0.4f) / 0.5f).coerceIn(0f, 1f)
        for (i in 0 until card.childCount) card.getChildAt(i).apply { alpha = a; translationY = (1f - a) * act.dp(18) }
    }

    private val snap: Backdrop? = if (Lens.supported) Backdrop(act, 0.5f, makeSoft = true) else null

    /** Clear droplet lens for small controls placed on this sheet (null when unsupported). */
    fun lensFor(host: View, radiusDp: Float): Drawable? {
        val b = snap ?: return null
        if (Build.VERSION.SDK_INT < 33) return null
        return LensDrawable(host, b, radiusDp, 6f, if (p.dark) 0xFF1A1F2E.toInt() else Color.WHITE,
            if (p.dark) 0.5f else 0.55f, p.dark, soft = true).apply { strengthDp = 10f; edgeDp = 26f; chroma = 0f; saturation = 1.3f; rimScale = 0.85f }
    }

    init {
        val blur = blurAvailable(act)
        card = LiquidLinear(act, vertical = true, jelly = true).apply {
            gravity = Gravity.NO_GRAVITY
            background = if (snap != null && Build.VERSION.SDK_INT >= 33)
                LensDrawable(this, snap, 32f, 12f,
                    if (p.dark) 0xFF12151F.toInt() else 0xFFF6F7FA.toInt(),
                    if (p.dark) 0.66f else 0.7f, p.dark, soft = true).apply { strengthDp = 14f; edgeDp = 30f; chroma = 0f; saturation = 1.4f }
            else glass(act, 32f, p, strong = !blur)
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(14))
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            isClickable = true
        }
        val handle = FrameLayout(act)
        handle.addView(View(act).apply { background = rounded(act, if (p.dark) 0x66FFFFFF else 0x33000000, 3f) },
            FrameLayout.LayoutParams(act.dp(38), act.dp(5), Gravity.CENTER))
        card.addView(handle, lp(MATCH, act.dp(22)))
        attachDrag(handle)
        card.addView(content, lp(MATCH, if (fullHeight) 0 else WRAP, if (fullHeight) 1f else 0f))
        root.addView(card, FrameLayout.LayoutParams(MATCH, if (fullHeight) MATCH else WRAP, Gravity.BOTTOM).apply {
            setMargins(act.dp(8), act.dp(if (fullHeight) 40 else 8), act.dp(8), act.dp(8))
        })
        root.setOnClickListener { dismiss() }
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, MATCH)
            setDecorFitsSystemWindows(false)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (p.dark) 0.32f else 0.14f)
            if (blur && snap == null) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = act.dp(22) }
            }
            setWindowAnimations(0)
            if (Incog.isSecure(act.window)) Incog.secure(this, true)
            decorView.setOnApplyWindowInsetsListener { _, ins ->
                val nb = ins.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime())
                root.setPadding(0, ins.getInsets(WindowInsets.Type.statusBars()).top, 0, nb.bottom)
                ins
            }
            androidx.core.view.WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightNavigationBars = !p.dark
            }
        }
    }

    override fun onStart() {
        super.onStart()
        snap?.let { b ->
            val dv = act.window.decorView
            b.capture(Rect(0, 0, dv.width, dv.height))
        }
        card.alpha = 0f
        if (barRect != null) card.post {
            val loc = IntArray(2); card.getLocationOnScreen(loc)
            baseTy = (barRect!!.bottom - (loc[1] + card.height)).toFloat()
            card.translationY = baseTy
            morphStep(0f)
            bar?.fadeContent(false)
            morph = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { morphStep(it.animatedValue as Float) }
                start()
            }
        } else card.post {
            card.translationY = card.height * 0.6f + act.dp(40)
            card.scaleX = 0.96f; card.scaleY = 0.96f
            card.alpha = 1f
            card.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(460)
                .setInterpolator(OvershootInterpolator(0.85f)).start()
        }
    }

    override fun dismiss() {
        if (closing) return
        closing = true
        if (barRect != null) {
            morph?.cancel()
            val from = card.background?.bounds?.let { if (card.height > 0) 1f - (it.top.toFloat() / (card.height - barRect!!.height()).coerceAtLeast(1)) else 1f } ?: 1f
            card.animate().translationY(baseTy).setDuration(200).start()
            var barBack = false
            morph = ValueAnimator.ofFloat(from.coerceIn(0f, 1.1f), 0f).apply {
                duration = 340
                interpolator = PathInterpolator(0.3f, 0f, 0.2f, 1f)
                addUpdateListener {
                    val v = it.animatedValue as Float
                    if (!barBack && v < 0.4f) { barBack = true; bar?.fadeContent(true) }
                    morphStep(v)
                }
                doOnEnd { try { super.dismiss() } catch (_: Exception) {} }
                start()
            }
            window?.let { w ->
                ValueAnimator.ofFloat(w.attributes.dimAmount, 0f).apply {
                    duration = 300
                    addUpdateListener { w.setDimAmount(it.animatedValue as Float) }
                    start()
                }
            }
            return
        }
        card.animate().translationY(card.height.toFloat() + act.dp(60)).setDuration(240)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction { try { super.dismiss() } catch (_: Exception) {} }.start()
        window?.let { w ->
            ValueAnimator.ofFloat(w.attributes.dimAmount, 0f).apply {
                duration = 240
                addUpdateListener { w.setDimAmount(it.animatedValue as Float) }
                start()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View) {
        var startY = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> { card.translationY = baseTy + (e.rawY - startY).coerceAtLeast(0f); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (card.translationY - baseTy > act.dp(110)) dismiss()
                    else card.animate().translationY(baseTy).setDuration(300).setInterpolator(OvershootInterpolator(1.2f)).start()
                    true
                }
                else -> false
            }
        }
    }

    fun title(text: String): GlassSheet {
        content.addView(label(act, text, 19f, p.text, true).apply {
            setPadding(act.dp(8), act.dp(2), act.dp(8), act.dp(12))
        })
        return this
    }

    fun row(iconPath: String?, text: String, sub: String? = null, color: Int = p.text, onClick: () -> Unit): LinearLayout {
        val r = hbox(act).apply {
            setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
            background = ripple(act, null, 18f)
            setOnClickListener { onClick() }
            pressable(0.97f)
        }
        if (iconPath != null) r.addView(ImageView(act).apply {
            setImageDrawable(icon(act, iconPath, color))
        }, lp(act.dp(22), act.dp(22)).apply { marginEnd = act.dp(16) })
        val col = vbox(act)
        col.addView(label(act, text, 16f, color))
        if (sub != null) col.addView(label(act, sub, 13f, p.dim).apply { maxLines = 1; setPadding(0, act.dp(3), 0, 0) })
        r.addView(col, lp(0, WRAP, 1f))
        content.addView(r, lp(MATCH, WRAP))
        r.animateIn(rows++)
        return r
    }
}

/**
 * The bottom address bar lives in its own small window. On Android 13+ it
 * refracts a live copy of the page under it through the lens shader (real
 * liquid glass); older versions fall back to system blur.
 */
class GlassBar(val act: Activity) : Dialog(act, android.R.style.Theme_Translucent_NoTitleBar) {
    private val pad = act.dp(10)
    private val frame = FrameLayout(act)
    val content = LiquidFrame(act, jelly = Lens.supported)
    val backdrop = Backdrop(act, 0.5f, makeSoft = true)
    private var lens: Drawable? = null
    private var shownY = 0
    private var hidden = false
    private var anim: ValueAnimator? = null
    var radiusDp = 30f
    private var fill = GradientDrawable()

    init {
        setCancelable(false)
        frame.setPadding(pad, pad, pad, pad)
        frame.clipChildren = false; frame.clipToPadding = false
        frame.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        setContentView(frame)
        window?.apply {
            fill = GradientDrawable().apply { cornerRadius = act.dp(radiusDp).toFloat() }
            setBackgroundDrawable(if (Lens.supported) ColorDrawable(Color.TRANSPARENT) else fill)
            setDecorFitsSystemWindows(false)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setElevation(0f)
            attributes = attributes.apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                height = WindowManager.LayoutParams.WRAP_CONTENT
                windowAnimations = 0
                fitInsetsTypes = 0
            }
        }
        setStyle(Palette.cur, false)
    }

    fun setStyle(p: Palette, incognito: Boolean) {
        Incog.secure(window, incognito)
        if (Lens.supported && Build.VERSION.SDK_INT >= 33) {
            val ld = (lens as? LensDrawable) ?: LensDrawable(content, backdrop, radiusDp, 2.5f, Color.WHITE, 0.22f, p.dark)
                .apply { strengthDp = 11f; edgeDp = 24f; chroma = 0.08f; saturation = 1.4f }.also { lens = it }
            val dk = p.dark || incognito
            ld.glassTint = if (incognito) 0xFF22262E.toInt() else if (p.dark) 0xFF141826.toInt() else Color.WHITE
            // Glass level from Settings → Стекло
            val (amt, blurDp) = when (Palette.glassLevel) {
                "clear" -> 0.06f to 0f
                "solid" -> 0.55f to 4f
                else -> 0.22f to 2.5f
            }
            ld.tintAmount = if (incognito) maxOf(amt, 0.4f) else amt + (if (dk) 0.06f else 0f)
            ld.blurDp = blurDp
            ld.soft = Palette.glassLevel == "solid"
            ld.dark = dk
            content.background = ld
            ld.invalidateSelf()
            return
        }
        val blur = blurAvailable(act)
        fill.setColor(if (incognito) (if (blur) 0xB01E2229.toInt() else 0xF01E2229.toInt()) else if (blur) p.glass else p.glassStrong)
        window?.let { w -> if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(if (blur) act.dp(26) else 0) }
        content.background = GlassDrawable(act.dp(radiusDp).toFloat(), Color.TRANSPARENT, p.dark || incognito, act.density,
            Palette.mix(0xFFFFFF, p.accent and 0xFFFFFF, 0.35f))
    }

    /** Place the pill [bottomPx] above the screen's bottom edge, [sidePx] from the sides. */
    fun place(bottomPx: Int, sidePx: Int) {
        shownY = bottomPx - pad
        val w = window ?: return
        val a = w.attributes
        a.width = act.resources.displayMetrics.widthPixels - sidePx * 2 + pad * 2
        if (!hidden && anim?.isRunning != true) a.y = shownY
        w.attributes = a
        kick(300)
    }

    fun setHidden(hide: Boolean) {
        if (hide == hidden) return
        hidden = hide
        val w = window ?: return
        val from = w.attributes.y
        val to = if (hide) -(frame.height + act.dp(24)) else shownY
        anim?.cancel()
        kick(600)
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = if (hide) 220 else 340
            interpolator = if (hide) PathInterpolator(0.4f, 0f, 1f, 1f) else OvershootInterpolator(0.9f)
            addUpdateListener {
                val a = w.attributes; a.y = it.animatedValue as Int
                try { w.attributes = a } catch (_: Exception) {}
            }
            start()
        }
    }

    val isHiddenNow get() = hidden

    /** Screen rect of the capsule, or null while the bar is hidden / not laid out. */
    fun capsuleRect(): Rect? {
        if (hidden || !isShowing || content.width == 0) return null
        val l = IntArray(2); content.getLocationOnScreen(l)
        return Rect(l[0], l[1], l[0] + content.width, l[1] + content.height)
    }

    /** Fade the capsule out while a sheet grows from it, and back in when it shrinks back. */
    fun fadeContent(show: Boolean) {
        content.animate().cancel()
        content.animate().alpha(if (show) 1f else 0f).setDuration(if (show) 220 else 120).start()
    }

    /** Matte "frosted" lens for the address field inside the capsule (variant A). */
    fun fieldLens(host: View, p: Palette, incognito: Boolean, radiusDp: Float = 25f): Drawable? {
        if (!Lens.supported || Build.VERSION.SDK_INT < 33) return null
        val dk = p.dark || incognito
        return LensDrawable(host, backdrop, radiusDp, 2f,
            if (incognito) 0xFF22262E.toInt() else if (dk) 0xFF1A1C2B.toInt() else Color.WHITE,
            if (incognito) 0.5f else if (dk) 0.42f else 0.38f, dk, soft = true)
            .apply { strengthDp = 4f; edgeDp = 10f; chroma = 0f; saturation = 1.2f; rimScale = 0.55f }
    }

    // ── live page copy under the bar ──
    private var liveUntil = 0L
    private var looping = false
    private val frameCb = Choreographer.FrameCallback { tick() }
    private val idle = Handler(Looper.getMainLooper())
    private var idleOn = false
    private val idleTick = object : Runnable {
        override fun run() { if (!idleOn) return; captureBand(); idle.postDelayed(this, 650) }
    }

    /** Keep refreshing the refraction every frame for [ms] (scrolling, loading, animations). */
    fun kick(ms: Long = 700) {
        if (!Lens.supported) return
        liveUntil = maxOf(liveUntil, SystemClock.uptimeMillis() + ms)
        if (!looping) { looping = true; Choreographer.getInstance().postFrameCallback(frameCb) }
    }

    fun setIdleRefresh(on: Boolean) {
        if (!Lens.supported) return
        idleOn = on
        idle.removeCallbacks(idleTick)
        if (on) idle.post(idleTick)
    }

    private fun tick() {
        captureBand()
        if (SystemClock.uptimeMillis() < liveUntil) Choreographer.getInstance().postFrameCallback(frameCb) else looping = false
    }

    private fun captureBand() {
        if (!isShowing || frame.visibility != View.VISIBLE || window?.decorView?.visibility != View.VISIBLE) return
        val dv = act.window.decorView
        val h = dv.height; val w = dv.width
        if (h <= 0 || w <= 0) return
        val wh = maxOf(frame.height, act.dp(80))
        val top = (h - maxOf(shownY, 0) - wh - act.dp(36)).coerceAtLeast(0)
        backdrop.capture(Rect(0, top, w, h))
    }

    override fun dismiss() { setIdleRefresh(false); liveUntil = 0; super.dismiss() }

    override fun onBackPressed() { act.onBackPressed() }
}

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

/** Small helper to fade-swap a label's text. */
fun TextView.swapText(t: CharSequence) {
    if (text.toString() == t.toString()) return
    animate().cancel()
    animate().alpha(0f).setDuration(80).withEndAction {
        text = t
        animate().alpha(1f).setDuration(140).start()
    }.start()
}

@Suppress("unused")
private fun absf(x: Float) = abs(x)
