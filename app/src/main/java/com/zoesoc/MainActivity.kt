package com.zoesoc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** Tablero a pantalla completa. Deslizar cambia de diseño; el engranaje abre los ajustes. */
class MainActivity : Activity() {
    private lateinit var dash: DashView
    private lateinit var prefs: Prefs
    private val listener: () -> Unit = { dash.update(ZoeState.data, ZoeState.status) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dash = DashView(this)
        dash.onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        dash.onHome = {
            // Muestra la pantalla de inicio del Tbox (la app sigue leyendo en segundo plano).
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        setContentView(dash)

        // Acceso directo a AutoKit: se busca entre las apps instaladas por su nombre.
        val ak = findAutoKit()
        if (ak != null) {
            dash.hasAutoKit = true
            dash.onAutoKit = {
                packageManager.getLaunchIntentForPackage(ak)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            }
        }

        val ok = Perms.ensure(this)
        when {
            prefs.address == null -> startActivity(Intent(this, SettingsActivity::class.java))
            ok && !ZoeService.running -> ZoeService.start(this)
        }
    }

    /** Paquete de AutoKit, buscando una app cuyo nombre o paquete contenga "autokit". */
    private fun findAutoKit(): String? {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(launcher, 0)
        return apps.firstOrNull { ri ->
            val label = ri.loadLabel(packageManager).toString().lowercase()
            val pkg = ri.activityInfo.packageName.lowercase()
            label.contains("autokit") || pkg.contains("autokit")
        }?.activityInfo?.packageName
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (prefs.address != null && !ZoeService.running && Perms.hasBt(this)) ZoeService.start(this)
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onResume() {
        super.onResume()
        ZoeState.appVisible = true
        ZoeState.touch() // oculta la burbuja
        ZoeState.addListener(listener)
        dash.update(ZoeState.data, ZoeState.status)
    }

    override fun onPause() {
        ZoeState.appVisible = false
        ZoeState.touch() // vuelve a mostrar la burbuja
        ZoeState.removeListener(listener)
        super.onPause()
    }
}
