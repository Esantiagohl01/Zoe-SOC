package com.zoesoc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Burbuja flotante arrastrable que se ve encima de cualquier app. Tocarla muestra/oculta el detalle. */
class Overlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(ctx)
    private var root: LinearLayout? = null
    private var tvSoc: TextView? = null
    private var tvSub: TextView? = null
    private var expanded = true

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null || !Settings.canDrawOverlays(ctx)) return
        val dp = ctx.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            cornerRadius = 18 * dp
            setColor(0xCC111418.toInt())
        }
        val soc = TextView(ctx).apply {
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            text = "--%"
            gravity = Gravity.CENTER
        }
        val sub = TextView(ctx).apply {
            textSize = 12f
            setTextColor(0xFFB8C0C8.toInt())
            gravity = Gravity.CENTER
        }
        val l = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = bg
            val h = (14 * dp).toInt()
            val v = (6 * dp).toInt()
            setPadding(h, v, h, v)
            addView(soc)
            addView(sub)
        }
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
        l.setOnTouchListener(Drag(p))
        try {
            wm.addView(l, p)
            root = l
            tvSoc = soc
            tvSub = sub
        } catch (e: Exception) {
            ZoeState.log("No pude mostrar la burbuja: ${e.message}")
        }
    }

    fun update(d: ZoeData?, status: String) {
        val soc = tvSoc ?: return
        val sub = tvSub ?: return
        soc.text = Fmt.soc(d)
        val v = d?.displaySoc
        soc.setTextColor(
            when {
                v == null -> Color.WHITE
                v < 20 -> 0xFFFF5252.toInt()
                v < 50 -> 0xFFFFC107.toInt()
                else -> 0xFF69F0AE.toInt()
            },
        )
        val stale = d == null || System.currentTimeMillis() - d.time > 120_000
        root?.alpha = if (stale) 0.6f else 1f
        sub.text = if (status == "OK") Fmt.short(d) else status.take(28)
        sub.visibility = if (expanded && sub.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    fun hide() {
        val r = root ?: return
        try { wm.removeView(r) } catch (e: Exception) { }
        root = null
        tvSoc = null
        tvSub = null
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
                    } else {
                        expanded = !expanded
                        update(ZoeState.data, ZoeState.status)
                    }
                }
            }
            return true
        }
    }
}
