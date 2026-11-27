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
import kotlin.math.ceil
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
        v.soc = d?.displaySoc
        v.tempC = d?.tempC
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
                        v.compact = !v.compact
                        prefs.compact = v.compact
                        v.requestLayout()
                        v.invalidate()
                    }
                }
            }
            return true
        }
    }
}

/** Dibujo de la burbuja: carro + porcentaje, 8 barritas como el tablero y temperatura de la batería. */
class BubbleView(ctx: Context) : View(ctx) {
    var soc: Double? = null
    var tempC: Double? = null
    var status: String = ""
    var compact = false

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9111418.toInt() }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = sp(26f) }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C0C8.toInt(); textSize = sp(12f) }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDE3EA.toInt() }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4450.toInt() }
    private val tire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1114.toInt() }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A949E.toInt() }

    private val lamp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9FD4FF.toInt() }
    private val tail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE24B4A.toInt() }

    // Silueta corta y alta estilo Zoe (frente a la izquierda), dibujada en un espacio de 90 × 40.
    private val carPath = Path().apply {
        moveTo(4f, 31f); lineTo(3f, 25f)
        quadTo(3f, 19f, 9f, 18f)                       // nariz redonda
        cubicTo(14f, 17f, 18f, 16f, 22f, 13f)          // capó corto
        cubicTo(27f, 7f, 32f, 3f, 40f, 2.5f)           // parabrisas inclinado
        cubicTo(55f, 1.5f, 68f, 2f, 76f, 5f)           // techo
        cubicTo(82f, 7f, 85f, 11f, 86f, 18f)           // cola corta, casi vertical
        lineTo(87f, 27f); quadTo(87f, 31f, 84f, 31f)
        close()
    }
    private val glassPath = Path().apply {
        moveTo(26f, 14f); cubicTo(30f, 9f, 35f, 5.5f, 41f, 5f); lineTo(51f, 5f); lineTo(51f, 14f); close()
        moveTo(54f, 14f); lineTo(54f, 5f); lineTo(65f, 5f); cubicTo(72f, 5.5f, 78f, 8f, 81f, 14f); close()
    }

    private fun socColor(): Int {
        val s = soc ?: return Color.WHITE
        return when {
            s < 20 -> 0xFFFF5252.toInt()
            s < 50 -> 0xFFFFC107.toInt()
            else -> 0xFF69F0AE.toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (compact) 118f else 160f
        // Completa: siempre deja una línea abajo (temperatura o estado). Compacta: solo si hay estado.
        val h = if (compact) 60f + (if (status != "OK") 16f else 0f) else 80f
        setMeasuredDimension(dp(w).roundToInt(), dp(h).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f)
        c.drawRoundRect(0f, 0f, w, h, dp(16f), dp(16f), bg)

        val col = socColor()
        pctPaint.color = col
        val pct = soc?.let { "${it.roundToInt()}%" } ?: "--%"

        val barsTop: Float
        if (compact) {
            c.drawText(pct, pad, dp(34f), pctPaint)
            barsTop = dp(42f)
        } else {
            drawCar(c, pad + dp(2f), dp(11f), dp(52f))
            c.drawText(pct, pad + dp(66f), dp(36f), pctPaint)
            barsTop = dp(46f)
        }
        drawBars(c, pad, barsTop, w - pad, barsTop + dp(9f), col)

        val lineY = barsTop + dp(23f)
        if (status != "OK") {
            c.drawText(status.take(if (compact) 16 else 24), pad, lineY, smallPaint)
        } else if (!compact) {
            val t = tempC
            if (t != null) c.drawText("Batería ${t.roundToInt()} °C", pad, lineY, smallPaint)
        }
    }

    private fun drawCar(c: Canvas, x: Float, y: Float, width: Float) {
        val k = width / 90f
        c.save()
        c.translate(x, y)
        c.scale(k, k)
        c.drawPath(carPath, body)
        c.drawPath(glassPath, glass)
        c.drawOval(4.5f, 19.5f, 11.5f, 22.5f, lamp)
        c.drawRoundRect(84.5f, 14f, 86.5f, 19f, 1f, 1f, tail)
        for (cx in floatArrayOf(19f, 71f)) {
            c.drawCircle(cx, 31f, 8.5f, tire)
            c.drawCircle(cx, 31f, 3.8f, hub)
        }
        c.restore()
    }

    /** 8 segmentos, igual que el tablero del Zoe: 35 % → 3 encendidos. */
    private fun drawBars(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, col: Int) {
        val n = 8
        val gap = dp(3f)
        val segW = (right - left - gap * (n - 1)) / n
        val on = soc?.let { ceil(it / 12.5).toInt().coerceIn(0, n) } ?: 0
        val r = dp(2f)
        for (i in 0 until n) {
            val l = left + i * (segW + gap)
            segPaint.color = if (i < on) col else 0x33FFFFFF
            c.drawRoundRect(l, top, l + segW, bottom, r, r, segPaint)
        }
    }
}
