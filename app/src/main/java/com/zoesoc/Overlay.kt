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

/** Burbuja flotante arrastrable. Tocarla alterna entre vista completa (carro + velocidad) y compacta. */
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
        v.speed = d?.speedKmh
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

/** Dibujo de la burbuja: carro + porcentaje, 8 barritas como el tablero y la velocidad. */
class BubbleView(ctx: Context) : View(ctx) {
    var soc: Double? = null
    var speed: Double? = null
    var status: String = ""
    var compact = false

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9111418.toInt() }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = sp(26f) }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE; textSize = sp(20f) }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C0C8.toInt(); textSize = sp(11f) }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDE3EA.toInt() }
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A4450.toInt() }
    private val tire = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E1114.toInt() }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A949E.toInt() }

    // Silueta de un hatchback pequeño, dibujada en un espacio de 100 × 40.
    private val carPath = Path().apply {
        moveTo(3f, 30f); lineTo(3f, 23f); quadTo(4f, 18f, 12f, 17f); lineTo(27f, 15f)
        lineTo(39f, 6f); quadTo(42f, 4f, 47f, 4f); lineTo(73f, 4f); quadTo(80f, 4f, 84f, 9f)
        lineTo(93f, 17f); quadTo(98f, 19f, 98f, 24f); lineTo(98f, 30f); close()
    }
    private val glassPath = Path().apply {
        moveTo(31f, 15f); lineTo(41f, 8f); quadTo(43f, 7f, 46f, 7f); lineTo(57f, 7f); lineTo(57f, 15f); close()
        moveTo(60f, 15f); lineTo(60f, 7f); lineTo(72f, 7f); quadTo(77f, 7f, 80f, 10f); lineTo(85f, 15f); close()
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
        val w = if (compact) 118f else 196f
        val h = if (compact) 60f else 92f
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
            drawCar(c, pad, dp(10f), dp(60f))
            c.drawText(pct, pad + dp(70f), dp(36f), pctPaint)
            barsTop = dp(46f)
        }
        drawBars(c, pad, barsTop, w - pad, barsTop + dp(9f), col)

        if (!compact) {
            val base = dp(80f)
            val s = speed
            if (s != null) {
                val num = s.roundToInt().toString()
                c.drawText(num, pad, base, speedPaint)
                c.drawText(" km/h", pad + speedPaint.measureText(num), base, smallPaint)
            } else if (status != "OK") {
                c.drawText(status.take(26), pad, base, smallPaint)
            }
        }
    }

    private fun drawCar(c: Canvas, x: Float, y: Float, width: Float) {
        val k = width / 100f
        c.save()
        c.translate(x, y)
        c.scale(k, k)
        c.drawPath(carPath, body)
        c.drawPath(glassPath, glass)
        for (cx in floatArrayOf(23f, 77f)) {
            c.drawCircle(cx, 30f, 8f, tire)
            c.drawCircle(cx, 30f, 3.5f, hub)
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
