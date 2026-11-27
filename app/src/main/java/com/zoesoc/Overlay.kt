package com.zoesoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/** Burbuja flotante arrastrable. Tocarla alterna entre vista completa (con carro) y compacta. */
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private var view: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val v = BubbleView(ctx).apply { compact = prefs.compact }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = prefs.bubbleY
        }
        v.setOnTouchListener(Drag(p))
        try {
            wm.addView(v, p)
            view = v
            params = p
        } catch (e: Exception) {
            ZoeState.log("No pude mostrar la burbuja: ${e.message}")
        }
    }

    fun update(d: ZoeData?, status: String) {
        val v = view ?: return
        v.data = d
        v.refPsi = prefs.refPsi
        v.lightsMode = prefs.lightsMode
        val oldW = v.width
        v.status = status
        // El ancho se ajusta al contenido; se mantiene fijo el borde derecho.
        val newW = v.widthPx()
        val p = params
        if (oldW > 0 && newW != oldW && p != null) {
            p.x += oldW - newW
            prefs.bubbleX = p.x
            try { wm.updateViewLayout(v, p) } catch (e: Exception) { }
        }
        v.requestLayout()
        v.alpha = if (d == null || System.currentTimeMillis() - d.time > 120_000) 0.6f else 1f
        v.invalidate()
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (e: Exception) { }
        view = null
        params = null
    }

    private inner class Drag(private val p: WindowManager.LayoutParams) : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        p.x = sx + dx
                        p.y = sy + dy
                        try { wm.updateViewLayout(v, p) } catch (x: Exception) { }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.bubbleX = p.x
                        prefs.bubbleY = p.y
                    } else if (v is BubbleView) {
                        // Cambia de tamaño manteniendo fijo el borde derecho.
                        val oldW = v.width
                        v.compact = !v.compact
                        prefs.compact = v.compact
                        p.x += oldW - v.widthPx()
                        prefs.bubbleX = p.x
                        try { wm.updateViewLayout(v, p) } catch (x: Exception) { }
                        v.requestLayout()
                        v.invalidate()
                    }
                }
            }
            return true
        }
    }
}

/**
 * Burbuja: carro visto desde arriba (batería que se llena, llantas de colores, luces),
 * SOC con decimal pequeño y temperatura; cargando muestra rayo, kW y tiempo estimado.
 */
class BubbleView(ctx: Context) : View(ctx) {
    var data: ZoeData? = null
    var status: String = ""
    var compact = false
    var refPsi = 35
    var lightsMode = 0

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6111418.toInt() }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C0C8.toInt() }
    private val boltP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.AMBER }
    private val therm = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFD5DCE3.toInt() }
    private val thermFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Path().apply {
        moveTo(7f, 0f); lineTo(0f, 13f); lineTo(5.5f, 13f); lineTo(4f, 22f); lineTo(12f, 8f); lineTo(6.5f, 8f); lineTo(9f, 0f); close()
    }

    private val tick = Runnable { invalidate() }

    /** Ancho justo para lo que se muestra (sin espacio sobrante a la derecha). */
    fun widthPx(): Int {
        val d = data
        val s = d?.displaySoc
        if (compact) {
            num.textSize = sp(28f)
            val t = s?.let { "${it.roundToInt()}%" } ?: "--%"
            val st = if (status != "OK") { small.textSize = sp(12f); small.measureText(status.take(14)) } else 0f
            return (dp(10f) + maxOf(num.measureText(t), st) + dp(10f)).roundToInt()
        }
        var w: Float
        if (s != null) {
            val (i, dec) = Fmt.socParts(s)
            num.textSize = sp(30f); w = num.measureText(i)
            num.textSize = sp(17f); w += num.measureText(",$dec")
            num.textSize = sp(19f); w += num.measureText("%")
        } else {
            num.textSize = sp(30f); w = num.measureText("--%")
        }
        if (d?.charging == true) w += dp(18f)
        small.textSize = sp(12f)
        val sub = when {
            status != "OK" -> small.measureText(status.take(14))
            d?.charging == true -> maxOf(
                run { num.textSize = sp(16f); num.measureText((d!!.chargingKw?.let { Fmt.f(it, 1) } ?: "--") + " kW") },
                small.measureText("100 % · 9 h 59"),
            )
            else -> run { num.textSize = sp(21f); dp(15f) + num.measureText("-10 °C") }
        }
        return (dp(68f) + maxOf(w, sub) + dp(10f)).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val extra = if (status != "OK") 16f else 0f
        val h = if (compact) 48f + extra else 104f
        setMeasuredDimension(widthPx(), dp(h).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRoundRect(0f, 0f, w, h, dp(16f), dp(16f), bg)
        val d = data
        val s = d?.displaySoc
        val col = Colors.soc(s)
        num.color = col

        if (compact) {
            num.textSize = sp(28f)
            // Centrado vertical exacto según la altura real de los números.
            val fm = num.fontMetrics
            val boxH = dp(48f)
            val base = boxH / 2 - (fm.ascent + fm.descent) / 2
            c.drawText(s?.let { "${it.roundToInt()}%" } ?: "--%", dp(10f), base, num)
            small.textSize = sp(12f)
            if (status != "OK") c.drawText(status.take(14), dp(10f), dp(60f), small)
            return
        }

        val charging = d?.charging == true
        // Latido suave de la batería mientras carga.
        val alpha = if (charging) {
            val t = (System.currentTimeMillis() % 1600L) / 1600.0
            (150 + 105 * (0.5 + 0.5 * kotlin.math.cos(t * 2 * Math.PI))).toInt()
        } else 255
        val carH = dp(84f)
        CarArt.draw(
            c, dp(12f), dp(17f), carH, s, col, CarArt.tireColors(d, refPsi),
            packAlpha = alpha, lights = CarArt.lightsOn(d, lightsMode),
        )

        val x0 = dp(68f)
        small.textSize = sp(11f); small.color = 0xFFB8C0C8.toInt()
        c.drawText("Batería", x0, dp(17f), small)

        // Porcentaje: entero grande, decimal y % pequeños.
        val base = dp(46f)
        var x = x0
        if (s != null) {
            val (i, dec) = Fmt.socParts(s)
            num.textSize = sp(30f); c.drawText(i, x, base, num); x += num.measureText(i)
            num.textSize = sp(17f); c.drawText(",$dec", x, base, num); x += num.measureText(",$dec")
            num.textSize = sp(19f); c.drawText("%", x, base, num); x += num.measureText("%")
        } else {
            num.textSize = sp(30f); c.drawText("--%", x, base, num); x += num.measureText("--%")
        }
        if (charging) {
            c.save()
            c.translate(x + dp(4f), base - dp(19f))
            val k = dp(18f) / 22f
            c.scale(k, k)
            c.drawPath(bolt, boltP)
            c.restore()
        }

        when {
            status != "OK" -> {
                small.textSize = sp(12f); small.color = 0xFFD5DCE3.toInt()
                c.drawText(status.take(14), x0, dp(72f), small)
            }
            charging && d != null -> {
                num.textSize = sp(16f); num.color = Colors.AMBER
                c.drawText(d.chargingKw?.let { Fmt.f(it, 1) + " kW" } ?: "Cargando", x0, dp(72f), num)
                val eta = d.minutesTo(80.0)?.let { "80 % · " + Fmt.durShort(it) }
                    ?: d.minutesTo(100.0)?.let { "100 % · " + Fmt.durShort(it) }
                small.textSize = sp(12f); small.color = 0xFFD5DCE3.toInt()
                if (eta != null) c.drawText(eta, x0, dp(92f), small)
            }
            else -> d?.tempC?.let {
                // termómetro + temperatura grande
                val tx = x0; val ty = dp(57f)
                therm.strokeWidth = dp(1.5f)
                c.drawRoundRect(tx + dp(3f), ty, tx + dp(7f), ty + dp(13f), dp(2f), dp(2f), therm)
                val tc = Colors.batTemp(it)
                therm.color = tc
                thermFill.color = tc
                c.drawCircle(tx + dp(5f), ty + dp(15.5f), dp(3.8f), thermFill)
                c.drawRect(tx + dp(4.4f), ty + dp(4f), tx + dp(5.6f), ty + dp(14f), thermFill)
                num.textSize = sp(21f); num.color = tc
                c.drawText(Fmt.f(it, 0) + " °C", tx + dp(15f), dp(77f), num)
            }
        }
        removeCallbacks(tick)
        if (charging) postDelayed(tick, 60L)
    }
}
