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

/**
 * Dibujo de la burbuja: carro de lado en estilo "rayos X" con el paquete de batería bajo el piso,
 * que se llena según el SOC, más el porcentaje y la temperatura de la batería.
 */
class BubbleView(ctx: Context) : View(ctx) {
    var soc: Double? = null
    var tempC: Double? = null
    var status: String = ""
    var compact = false

    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD9111418.toInt() }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = sp(28f) }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C0C8.toInt(); textSize = sp(12f) }

    // Pinturas del carro (en unidades del dibujo de 90 × 40).
    private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.3f; color = 0xFFC8D2DC.toInt()
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val thin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.8f; color = 0x99C8D2DC.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val packEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val packFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val packLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.9f; color = 0xFFE6ECF2.toInt()
    }

    // Silueta corta y alta, techo plano y cola recta (frente a la izquierda).
    private val carPath = Path().apply {
        moveTo(4f, 31f); lineTo(3f, 25f)
        quadTo(3f, 19f, 9f, 18f)                       // nariz redonda
        cubicTo(14f, 17f, 18f, 16f, 22f, 13f)          // capó corto
        cubicTo(27f, 7f, 32f, 3.2f, 40f, 2.6f)         // parabrisas
        lineTo(72f, 2.4f)                              // techo plano
        quadTo(77f, 2.4f, 79f, 4.5f)                   // esquina trasera
        lineTo(84f, 15f)                               // portón casi recto
        lineTo(86f, 27f); quadTo(86f, 31f, 83f, 31f)
        close()
    }
    private val glassPath = Path().apply {
        moveTo(26f, 14f); cubicTo(30f, 9f, 35f, 5.6f, 41f, 5.2f); lineTo(51f, 5.1f); lineTo(51f, 14f); close()
        moveTo(54f, 14f); lineTo(54f, 5.1f); lineTo(71f, 5f); quadTo(75f, 5f, 77f, 7f); lineTo(80f, 14f); close()
    }

    private fun socColor(): Int {
        val s = soc ?: return 0xFFC8D2DC.toInt()
        return when {
            s < 20 -> 0xFFFF5252.toInt()
            s < 50 -> 0xFFFFC107.toInt()
            else -> 0xFF69F0AE.toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (compact) 100f else 210f
        val h = if (compact) 48f + (if (status != "OK") 16f else 0f) else 64f
        setMeasuredDimension(dp(w).roundToInt(), dp(h).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(10f)
        c.drawRoundRect(0f, 0f, w, h, dp(16f), dp(16f), bg)

        val col = socColor()
        pctPaint.color = if (soc == null) Color.WHITE else col
        val pct = soc?.let { "${it.roundToInt()}%" } ?: "--%"
        val sub = if (status != "OK") status.take(if (compact) 14 else 16)
        else tempC?.let { "Batería ${it.roundToInt()} °C" } ?: ""

        if (compact) {
            c.drawText(pct, pad, dp(36f), pctPaint)
            if (status != "OK") c.drawText(sub, pad, dp(56f), smallPaint)
        } else {
            drawCar(c, pad, dp(9f), dp(104f), col)
            val x = pad + dp(110f)
            c.drawText(pct, x, dp(35f), pctPaint)
            if (sub.isNotEmpty()) c.drawText(sub, x, dp(53f), smallPaint)
        }
    }

    private fun drawCar(c: Canvas, x: Float, y: Float, width: Float, col: Int) {
        val k = width / 90f
        c.save()
        c.translate(x, y)
        c.scale(k, k)
        // carrocería transparente
        c.drawPath(carPath, bodyFill)
        c.drawPath(glassPath, glassFill)
        c.drawPath(glassPath, thin)
        // línea del piso y motor adelante
        c.drawLine(28f, 23f, 62f, 23f, thin)
        c.drawRoundRect(9f, 21.5f, 16f, 26.5f, 1.2f, 1.2f, thin)
        // paquete de batería bajo el piso, lleno según el SOC
        val l = 27f; val r = 63f; val t = 24.2f; val b = 29.8f
        c.drawRoundRect(l, t, r, b, 1.2f, 1.2f, packEmpty)
        val s = soc
        if (s != null) {
            val fr = l + (r - l) * (s.coerceIn(0.0, 100.0) / 100.0).toFloat()
            packFill.color = col
            c.drawRoundRect(l, t, fr, b, 1.2f, 1.2f, packFill)
        }
        c.drawRoundRect(l, t, r, b, 1.2f, 1.2f, packLine)
        // contorno y ruedas
        c.drawPath(carPath, line)
        for (cx in floatArrayOf(19f, 71f)) {
            c.drawCircle(cx, 31f, 7.8f, bg)
            c.drawCircle(cx, 31f, 7.8f, line)
            c.drawCircle(cx, 31f, 4.2f, thin)
        }
        c.restore()
    }
}
