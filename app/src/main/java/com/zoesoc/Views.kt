package com.zoesoc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Carro visto desde arriba (frente arriba), dibujado en un espacio de 60 × 116:
 * llantas de colores según la presión, batería en el piso que se llena según el SOC, faros y stops encendidos.
 */
object CarArt {
    const val W = 60f
    const val H = 116f

    private val body = Path().apply {
        moveTo(18f, 4f); quadTo(30f, 1f, 42f, 4f); quadTo(50f, 7f, 51f, 16f)
        lineTo(52f, 96f); quadTo(52f, 108f, 42f, 110f); lineTo(18f, 110f)
        quadTo(8f, 108f, 8f, 96f); lineTo(9f, 16f); quadTo(10f, 7f, 18f, 4f); close()
    }
    private val windshield = Path().apply {
        moveTo(14f, 26f); quadTo(30f, 20f, 46f, 26f); lineTo(45f, 31f); lineTo(15f, 31f); close()
    }
    private val rearGlass = Path().apply {
        moveTo(15f, 86f); lineTo(45f, 86f); lineTo(46f, 94f); quadTo(30f, 98f, 14f, 94f); close()
    }
    private val heads = Path().apply {
        moveTo(11f, 10f); quadTo(13f, 5f, 21f, 4.5f); lineTo(22f, 7.5f); quadTo(15f, 8f, 12.5f, 11f); close()
        moveTo(49f, 10f); quadTo(47f, 5f, 39f, 4.5f); lineTo(38f, 7.5f); quadTo(45f, 8f, 47.5f, 11f); close()
    }
    private val tails = Path().apply {
        moveTo(10f, 101f); quadTo(11f, 107f, 18f, 109f); lineTo(19f, 106f); quadTo(14f, 105f, 12.5f, 100f); close()
        moveTo(50f, 101f); quadTo(49f, 107f, 42f, 109f); lineTo(41f, 106f); quadTo(46f, 105f, 47.5f, 100f); close()
    }

    private val tireP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2171B20.toInt() }
    private val glassP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val glassLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0x80C8D2DC.toInt()
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f; color = 0xFFC8D2DC.toInt(); strokeJoin = Paint.Join.ROUND
    }
    private val packEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val packFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val packLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFFE6ECF2.toInt()
    }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val headGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
    private val tailP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B3B.toInt() }
    private val tailGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF3B3B }

    /** tires = colores [del. izq., del. der., tras. izq., tras. der.]; packAlpha permite el latido al cargar. */
    fun draw(
        c: Canvas, left: Float, top: Float, height: Float,
        soc: Double?, socColor: Int, tires: IntArray, showPack: Boolean = true, packAlpha: Int = 255,
    ) {
        val k = height / H
        c.save()
        c.translate(left, top)
        c.scale(k, k)
        val tp = arrayOf(floatArrayOf(3f, 18f), floatArrayOf(50f, 18f), floatArrayOf(3f, 79f), floatArrayOf(50f, 79f))
        for (i in 0 until 4) {
            tireP.color = tires[i]
            c.drawRoundRect(tp[i][0], tp[i][1], tp[i][0] + 7f, tp[i][1] + 15f, 2.5f, 2.5f, tireP)
        }
        c.drawPath(body, bodyP)
        if (showPack) {
            val l = 16f; val r = 44f; val t = 34f; val b = 80f
            c.drawRoundRect(l, t, r, b, 3f, 3f, packEmpty)
            val s = soc
            if (s != null) {
                val y = b - (b - t) * (s.coerceIn(0.0, 100.0) / 100.0).toFloat()
                packFill.color = socColor
                packFill.alpha = packAlpha
                c.drawRoundRect(l, y, r, b, 3f, 3f, packFill)
            }
            c.drawRoundRect(l, t, r, b, 3f, 3f, packLine)
        }
        c.drawPath(windshield, glassP); c.drawPath(windshield, glassLine)
        c.drawPath(rearGlass, glassP); c.drawPath(rearGlass, glassLine)
        c.drawPath(body, outline)
        c.drawOval(10f, 3f, 24f, 11f, headGlow); c.drawOval(36f, 3f, 50f, 11f, headGlow)
        c.drawPath(heads, headP)
        c.drawOval(8f, 102f, 22f, 110f, tailGlow); c.drawOval(38f, 102f, 52f, 110f, tailGlow)
        c.drawPath(tails, tailP)
        c.restore()
    }

    fun tireColors(d: ZoeData?, ref: Int): IntArray {
        val t = d?.tires
        return intArrayOf(
            Colors.tire(t?.fl, ref), Colors.tire(t?.fr, ref), Colors.tire(t?.rl, ref), Colors.tire(t?.rr, ref),
        )
    }
}

private fun View.dp(v: Float) = v * resources.displayMetrics.density
private fun View.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Medidor circular de la app: SOC con decimal pequeño, autonomía y línea de carga. */
class GaugeView(ctx: Context) : View(ctx) {
    var data: ZoeData? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF262B31.toInt()
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C0C8.toInt(); textAlign = Paint.Align.CENTER }
    private val chg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.AMBER; textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(230f).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val d = data
        val s = d?.displaySoc
        val col = Colors.soc(s)
        val size = min(width.toFloat(), dp(210f))
        val stroke = dp(14f)
        val cx = width / 2f
        val cy = dp(112f)
        val r = size / 2 - stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        arc.color = col
        c.drawArc(rect, 135f, 270f, false, track)
        if (s != null) c.drawArc(rect, 135f, max(1f, 270f * (s.coerceIn(0.0, 100.0) / 100.0).toFloat()), false, arc)

        // Número: entero grande, decimal y % pequeños, centrado.
        big.color = col
        val (i, dec) = if (s != null) Fmt.socParts(s) else "--" to ""
        val a = sp(46f); val b = sp(26f); val pc = sp(28f)
        big.textSize = a; val wi = big.measureText(i)
        big.textSize = b; val wd = if (dec.isEmpty()) 0f else big.measureText(",$dec")
        big.textSize = pc; val wp = big.measureText("%")
        var x = cx - (wi + wd + wp) / 2
        val base = cy + dp(14f)
        big.textSize = a; c.drawText(i, x, base, big); x += wi
        if (dec.isNotEmpty()) { big.textSize = b; c.drawText(",$dec", x, base, big); x += wd }
        big.textSize = pc; c.drawText("%", x, base, big)

        sub.textSize = sp(15f)
        d?.rangeKm?.let { c.drawText("$it km", cx, cy + dp(44f), sub) }
        if (d != null && d.charging) {
            chg.textSize = sp(13f)
            c.drawText("⚡ " + Fmt.chargeLine(d), cx, cy + dp(70f), chg)
        }
    }
}

/** Gráfica simple del SOH en el tiempo. */
class SparkView(ctx: Context) : View(ctx) {
    var points: List<Double> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density
        color = Colors.GREEN; strokeJoin = Paint.Join.ROUND
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8D97A1.toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(56f).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        txt.textSize = sp(12f)
        if (points.size < 2) {
            c.drawText("Se va llenando con un punto por día", 0f, dp(30f), txt)
            return
        }
        val lo = (points.minOrNull() ?: 0.0) - 0.5
        val hi = (points.maxOrNull() ?: 100.0) + 0.5
        val w = width.toFloat()
        val h = height.toFloat() - dp(6f)
        val path = Path()
        points.forEachIndexed { idx, v ->
            val x = w * idx / (points.size - 1)
            val y = dp(3f) + h * (1 - ((v - lo) / (hi - lo)).toFloat())
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, line)
    }
}

/** Sección de llantas de la app: carro desde arriba con la presión al lado de cada llanta. */
class TireView(ctx: Context) : View(ctx) {
    var data: ZoeData? = null
    var ref = 35

    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(210f).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val carH = dp(190f)
        val carW = carH * CarArt.W / CarArt.H
        val top = dp(10f)
        CarArt.draw(c, (width - carW) / 2, top, carH, null, 0, CarArt.tireColors(data, ref), showPack = false)
        val t = data?.tires
        val yF = top + carH * 25.5f / CarArt.H
        val yR = top + carH * 86.5f / CarArt.H
        corner(c, t?.fl, "Del. izq.", dp(4f), yF, Paint.Align.LEFT)
        corner(c, t?.fr, "Del. der.", width - dp(4f), yF, Paint.Align.RIGHT)
        corner(c, t?.rl, "Tras. izq.", dp(4f), yR, Paint.Align.LEFT)
        corner(c, t?.rr, "Tras. der.", width - dp(4f), yR, Paint.Align.RIGHT)
    }

    private fun corner(c: Canvas, psi: Double?, name: String, x: Float, y: Float, align: Paint.Align) {
        val col = Colors.tire(psi, ref)
        value.textAlign = align; value.textSize = sp(24f); value.color = if (psi == null) 0xFF8D97A1.toInt() else col
        c.drawText(psi?.roundToInt()?.toString() ?: "--", x, y, value)
        val state = when {
            psi == null -> ""
            col == Colors.RED -> " · muy baja"
            col == Colors.AMBER -> " · baja"
            else -> ""
        }
        label.textAlign = align; label.textSize = sp(11f)
        label.color = if (state.isEmpty()) 0xFF8D97A1.toInt() else col
        c.drawText(name + state, x, y + dp(16f), label)
    }
}
