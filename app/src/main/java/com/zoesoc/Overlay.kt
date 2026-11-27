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
        } catch (e: Exception) {
            ZoeState.log("No pude mostrar la burbuja: ${e.message}")
        }
    }

    fun update(d: ZoeData?, status: String) {
        val v = view ?: return
        v.data = d
        v.refPsi = prefs.refPsi
        v.lightsMode = prefs.lightsMode
        if (v.status != status && (v.status == "OK" || status == "OK")) v.requestLayout()
        v.status = status
        v.alpha = if (d == null || System.currentTimeMillis() - d.time > 120_000) 0.6f else 1f
        v.invalidate()
    }

    fun hide() {
        val v = view ?: return
        try { wm.removeView(v) } catch (e: Exception) { }
        view = null
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

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0111418.toInt() }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C0C8.toInt() }
    private val boltP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Colors.AMBER }
    private val bolt = Path().apply {
        moveTo(7f, 0f); lineTo(0f, 13f); lineTo(5.5f, 13f); lineTo(4f, 22f); lineTo(12f, 8f); lineTo(6.5f, 8f); lineTo(9f, 0f); close()
    }

    fun widthPx(): Int = dp(if (compact) 100f else 206f).roundToInt()

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
            c.drawText(s?.let { "${it.roundToInt()}%" } ?: "--%", dp(10f), dp(36f), num)
            small.textSize = sp(12f)
            if (status != "OK") c.drawText(status.take(14), dp(10f), dp(56f), small)
            return
        }

        val charging = d?.charging == true
        // Latido suave de la batería mientras carga.
        val alpha = if (charging) {
            val t = (System.currentTimeMillis() % 1600L) / 1600.0
            (150 + 105 * (0.5 + 0.5 * kotlin.math.cos(t * 2 * Math.PI))).toInt()
        } else 255
        val carH = dp(94f)
        CarArt.draw(
            c, dp(12f), dp(5f), carH, s, col, CarArt.tireColors(d, refPsi),
            packAlpha = alpha, lights = CarArt.lightsOn(d, lightsMode),
        )

        // Porcentaje: entero grande, decimal y % pequeños.
        val x0 = dp(74f)
        val base = dp(42f)
        var x = x0
        if (s != null) {
            val (i, dec) = Fmt.socParts(s)
            num.textSize = sp(31f); c.drawText(i, x, base, num); x += num.measureText(i)
            num.textSize = sp(18f); c.drawText(",$dec", x, base, num); x += num.measureText(",$dec")
            num.textSize = sp(20f); c.drawText("%", x, base, num); x += num.measureText("%")
        } else {
            num.textSize = sp(31f); c.drawText("--%", x, base, num); x += num.measureText("--%")
        }
        if (charging) {
            c.save()
            c.translate(x + dp(5f), base - dp(21f))
            val k = dp(20f) / 22f
            c.scale(k, k)
            c.drawPath(bolt, boltP)
            c.restore()
        }

        small.textSize = sp(13f)
        when {
            status != "OK" -> c.drawText(status.take(18), x0, dp(66f), small)
            charging && d != null -> {
                val kw = d.chargingKw?.let { Fmt.f(it, 1) + " kW" } ?: "Cargando"
                val tmp = d.tempC?.let { " · " + Fmt.f(it, 0) + " °C" } ?: ""
                c.drawText(kw + tmp, x0, dp(66f), small)
                val eta = d.minutesTo(80.0)?.let { "80 % en " + Fmt.dur(it) }
                    ?: d.minutesTo(100.0)?.let { "100 % en " + Fmt.dur(it) }
                if (eta != null) c.drawText(eta, x0, dp(86f), small)
            }
            else -> d?.tempC?.let { c.drawText("Batería " + Fmt.f(it, 0) + " °C", x0, dp(66f), small) }
        }
        if (charging) postInvalidateDelayed(60)
    }
}
