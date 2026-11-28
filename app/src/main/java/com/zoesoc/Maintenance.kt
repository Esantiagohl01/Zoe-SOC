package com.zoesoc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Posición de los sensores de presión. El mapa son 4 dígitos, uno por posición
 * (del. izq., del. der., tras. izq., tras. der.), con el sensor que hay ahí:
 * 0 = DI, 1 = DD, 2 = TI, 3 = TD (donde estaba de fábrica). "0123" = sin rotar.
 */
object TireMap {
    val SENSOR = arrayOf("DI", "DD", "TI", "TD")
    val POS = arrayOf("Del. izquierda", "Del. derecha", "Tras. izquierda", "Tras. derecha")

    fun valid(m: String) = m.length == 4 && m.toSet() == setOf('0', '1', '2', '3')

    fun raw(t: Tires?, sensor: Int): Double? = when (sensor) {
        0 -> t?.fl; 1 -> t?.fr; 2 -> t?.rl; else -> t?.rr
    }

    fun apply(t: Tires?, m: String): Tires? {
        if (t == null || !valid(m) || m == "0123") return t
        fun at(i: Int) = raw(t, m[i] - '0')
        return Tires(at(0), at(1), at(2), at(3))
    }

    fun swap(m: String, a: Int, b: Int): String {
        val c = m.toCharArray(); val x = c[a]; c[a] = c[b]; c[b] = x
        return String(c)
    }

    /** Las delanteras pasan atrás y las traseras adelante, cada una por su mismo lado. */
    fun frontBack(m: String) = swap(swap(m, 0, 2), 1, 3)

    /** En X: cada llanta pasa al eje contrario y al lado contrario. */
    fun cross(m: String) = swap(swap(m, 0, 3), 1, 2)
}

class MaintenanceActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var map: TireMapView
    private val listener: () -> Unit = { map.postInvalidate() }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(14), px(18), px(20))
        }
        fun add(v: View, top: Int = 8, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = px(top) })
        }
        fun label(size: Float, color: Int, s: String, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color); text = s
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        add(Button(this).apply { text = "← Volver"; setOnClickListener { finish() } }, 0)
        add(label(18f, 0xFFE9EEF3.toInt(), "Mantenimiento · Sensores de llantas", true), 10)
        add(label(13f, 0xFF8D97A1.toInt(), "Toca una llanta y luego otra para intercambiarlas. " +
            "DI, DD, TI, TD = donde estaba cada sensor de fábrica. La presión te ayuda a reconocerlos."), 4)
        map = TireMapView(this, prefs)
        add(map, 10, px(300))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: (String) -> String) = row.addView(Button(this).apply {
            text = t
            setOnClickListener { prefs.tireMap = f(prefs.tireMap); map.selected = -1; changed() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btn("Adelante ↔ atrás") { TireMap.frontBack(it) }
        btn("Cruzada") { TireMap.cross(it) }
        btn("Restablecer") { "0123" }
        add(row, 10)

        map.onChange = { changed() }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0F1215.toInt()); addView(col) })
    }

    /** Aplica el mapa nuevo de una vez a los datos publicados (burbuja, tablero, detalle). */
    private fun changed() {
        val d = ZoeState.data
        if (d?.rawTires != null) ZoeState.publish(d.copy(tires = TireMap.apply(d.rawTires, prefs.tireMap)), ZoeState.status)
        else ZoeState.touch()
        map.invalidate()
    }

    override fun onResume() {
        super.onResume()
        ZoeState.appVisible = true
        ZoeState.touch()
        ZoeState.addListener(listener)
    }

    override fun onPause() {
        ZoeState.appVisible = false
        ZoeState.touch()
        ZoeState.removeListener(listener)
        super.onPause()
    }
}

/** Carro visto desde arriba con las 4 posiciones y el sensor asignado a cada una. */
@SuppressLint("ViewConstructor")
class TireMapView(ctx: Context, private val prefs: Prefs) : View(ctx) {
    var selected = -1
    var onChange: (() -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxes = Array(4) { RectF() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2
        // Carro
        val cw = 70 * d; val ch = 220 * d; val top = (h - ch) / 2
        fill.color = 0xFFD9DEE3.toInt()
        c.drawRoundRect(cx - cw / 2, top, cx + cw / 2, top + ch, 30 * d, 30 * d, fill)
        fill.color = 0xFF2A3038.toInt()
        c.drawRoundRect(cx - cw / 2 + 10 * d, top + 40 * d, cx + cw / 2 - 10 * d, top + 76 * d, 8 * d, 8 * d, fill)
        c.drawRoundRect(cx - cw / 2 + 10 * d, top + 170 * d, cx + cw / 2 - 10 * d, top + 196 * d, 8 * d, 8 * d, fill)
        tp.textSize = 12 * d; tp.color = 0xFF8D97A1.toInt(); tp.textAlign = Paint.Align.CENTER; tp.typeface = Typeface.DEFAULT
        c.drawText("adelante", cx, top - 6 * d, tp)

        val data = ZoeState.data
        val m = prefs.tireMap
        val ref = prefs.refPsi
        val yF = top + 30 * d; val yR = top + 160 * d
        val tw = 14 * d; val th = 36 * d
        val bw = minOf(170 * d, (w - cw) / 2 - 30 * d); val bh = 60 * d
        for (i in 0 until 4) {
            val left = i % 2 == 0
            val y = if (i < 2) yF else yR
            val sensor = m[i] - '0'
            val v = TireMap.raw(data?.rawTires ?: data?.tires?.takeIf { m == "0123" }, sensor)
            val col = if (v == null) 0xFF8D97A1.toInt() else Colors.tire(v, ref)
            // llanta
            fill.color = col
            val tx = if (left) cx - cw / 2 - tw + 2 * d else cx + cw / 2 - 2 * d
            c.drawRoundRect(tx, y, tx + tw, y + th, 5 * d, 5 * d, fill)
            // recuadro
            val bx = if (left) cx - cw / 2 - 24 * d - bw else cx + cw / 2 + 24 * d
            val r = boxes[i]; r.set(bx, y + th / 2 - bh / 2, bx + bw, y + th / 2 + bh / 2)
            fill.color = 0xFF1E242B.toInt()
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            if (i == selected) { stroke.color = 0xFF4A90D9.toInt(); c.drawRoundRect(r, 10 * d, 10 * d, stroke) }
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = 13 * d; tp.color = 0xFF8D97A1.toInt(); tp.typeface = Typeface.DEFAULT
            c.drawText(TireMap.POS[i], r.left + 14 * d, r.top + 23 * d, tp)
            tp.textSize = 18 * d; tp.typeface = Typeface.DEFAULT_BOLD
            tp.color = 0xFFE9EEF3.toInt()
            val label = "Sensor " + TireMap.SENSOR[sensor] + " · "
            c.drawText(label, r.left + 14 * d, r.top + 48 * d, tp)
            tp.color = col
            c.drawText(v?.let { Math.round(it).toString() } ?: "--", r.left + 14 * d + tp.measureText(label), r.top + 48 * d, tp)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val hit = boxes.indexOfFirst { it.contains(e.x, e.y) }
        if (hit < 0) { selected = -1; invalidate(); return true }
        when (selected) {
            -1 -> selected = hit
            hit -> selected = -1
            else -> { prefs.tireMap = TireMap.swap(prefs.tireMap, selected, hit); selected = -1; onChange?.invoke() }
        }
        invalidate()
        return true
    }
}
