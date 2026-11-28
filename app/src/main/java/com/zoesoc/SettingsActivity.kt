package com.zoesoc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Permisos de Bluetooth y notificaciones, compartidos por las dos pantallas. */
object Perms {
    private fun bt(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Solo revisa, sin pedir nada. */
    fun hasBt(a: Activity): Boolean = bt().all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Pide los que falten; devuelve true si ya están los de Bluetooth. */
    fun ensure(a: Activity): Boolean {
        val all = bt() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = all.filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) a.requestPermissions(missing.toTypedArray(), 1)
        return bt().all { a.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}

/** Ajustes: scanner, burbuja, arranque, intervalos, presión, luces, iniciar/detener y registro. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNight: TextView
    private lateinit var tvLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }

    private val bgColor = 0xFF0F1215.toInt()
    private val textMain = 0xFFE9EEF3.toInt()
    private val textDim = 0xFF8D97A1.toInt()

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(14), px(18), px(20))
        }
        fun add(v: View, top: Int = 8) {
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })
        }

        add(Button(this).apply { text = "← Volver al tablero"; setOnClickListener { finish() } }, 0)
        add(text(18f, textMain).apply { text = "Ajustes"; typeface = Typeface.DEFAULT_BOLD }, 10)
        tvStatus = text(14f, textDim)
        add(tvStatus)
        tvNight = text(14f, textDim)
        add(tvNight)
        tvDevice = text(14f, textMain)
        add(tvDevice)
        add(Button(this).apply { text = "Elegir scanner"; setOnClickListener { pickDevice() } })
        add(Button(this).apply {
            text = "Permitir burbuja flotante"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        add(check("Mostrar burbuja flotante", prefs.overlay) { prefs.overlay = it; ZoeState.touch() })
        add(check("Iniciar automáticamente al encender", prefs.autostart) { prefs.autostart = it })
        add(check("Luces siempre encendidas (si no, siguen al carro)", prefs.lightsMode == 1) {
            prefs.lightsMode = if (it) 1 else 0; ZoeState.touch()
        })
        add(numberField("Presión recomendada (PSI):", prefs.refPsi) { prefs.refPsi = it.coerceIn(20, 60); ZoeState.touch() })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply { text = "Iniciar"; setOnClickListener { startReading() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply { text = "Detener"; setOnClickListener { ZoeService.stop(this@SettingsActivity) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(btns)
        add(text(13f, textDim).apply {
            text = "Mantenimiento ›"
            setPadding(0, px(10), 0, px(6))
            setOnClickListener { startActivity(Intent(this@SettingsActivity, MaintenanceActivity::class.java)) }
        }, 10)

        add(text(15f, textMain).apply { text = "Registro"; typeface = Typeface.DEFAULT_BOLD }, 16)
        tvLog = text(11f, textDim).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        add(tvLog, 4)

        setContentView(ScrollView(this).apply { setBackgroundColor(bgColor); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        ZoeState.appVisible = true
        ZoeState.touch()
        ZoeState.addListener(listener)
        refresh()
    }

    override fun onPause() {
        ZoeState.appVisible = false
        ZoeState.touch()
        ZoeState.removeListener(listener)
        super.onPause()
    }

    private fun text(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label; setTextColor(textMain); isChecked = value
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun numberField(label: String, value: Int, onChange: (Int) -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.addView(text(14f, textMain).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(textMain)
            setText(value.toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.let(onChange)
                }
            })
        }, LinearLayout.LayoutParams(px(80), ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    private fun refresh() {
        tvStatus.text = "Estado: " + (if (ZoeService.running) ZoeState.status else "detenido")
        tvNight.text = "Modo noche del R-Link: " + NightMode.describe(this)
        tvDevice.text = "Scanner: " + (prefs.deviceName ?: "no elegido")
        tvLog.text = ZoeState.logText()
    }

    private fun startReading() {
        if (!Perms.ensure(this)) return
        if (prefs.address == null) {
            toast("Primero elige el scanner")
            return
        }
        if (prefs.overlay && !Settings.canDrawOverlays(this)) toast("Falta el permiso de burbuja flotante")
        ZoeService.start(this)
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!Perms.ensure(this)) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Activa el Bluetooth")
            return
        }
        val found = LinkedHashMap<String, String>() // dirección → etiqueta
        try {
            adapter.bondedDevices?.forEach { found[it.address] = "${it.name ?: "?"}  (${it.address}) · emparejado" }
        } catch (e: SecurityException) { }

        val scanner = adapter.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.device.name ?: r.scanRecord?.deviceName ?: return
                if (!found.containsKey(r.device.address)) found[r.device.address] = "$name  (${r.device.address}) · BLE"
            }
        }
        val wait = AlertDialog.Builder(this).setMessage("Buscando scanners… (6 s)\nEl carro debe estar encendido.").setCancelable(false).show()
        try { scanner?.startScan(cb) } catch (e: Exception) { ZoeState.log("Escaneo BLE: ${e.message}") }
        handler.postDelayed({
            try { scanner?.stopScan(cb) } catch (e: Exception) { }
            wait.dismiss()
            showList(found)
        }, 6000)
    }

    private fun showList(found: Map<String, String>) {
        if (found.isEmpty()) {
            toast("No encontré dispositivos")
            return
        }
        val hints = listOf("vlink", "vgate", "icar", "obd", "elm", "ios")
        val entries = found.entries.sortedBy { e -> if (hints.any { e.value.lowercase().contains(it) }) 0 else 1 }
        val labels = entries.map { it.value }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elige el Vgate")
            .setItems(labels) { _, i ->
                prefs.address = entries[i].key
                prefs.deviceName = entries[i].value
                refresh()
                if (ZoeService.running) {
                    ZoeService.stop(this)
                    handler.postDelayed({ ZoeService.start(this) }, 800)
                } else {
                    startReading()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
