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
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var tvSoc: TextView
    private lateinit var tvDetails: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvLog: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(20))
        }
        fun add(v: android.view.View) {
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        }

        tvSoc = TextView(this).apply { textSize = 56f; typeface = Typeface.DEFAULT_BOLD }
        tvDetails = TextView(this).apply { textSize = 16f }
        tvStatus = TextView(this).apply { textSize = 14f }
        tvDevice = TextView(this).apply { textSize = 14f }
        add(tvSoc); add(tvDetails); add(tvStatus); add(tvDevice)

        add(Button(this).apply { text = "Elegir scanner"; setOnClickListener { pickDevice() } })
        add(Button(this).apply {
            text = "Permitir burbuja flotante"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        add(CheckBox(this).apply {
            text = "Mostrar burbuja flotante"
            isChecked = prefs.overlay
            setOnCheckedChangeListener { _, c -> prefs.overlay = c; ZoeState.touch() }
        })
        add(CheckBox(this).apply {
            text = "Iniciar automáticamente al encender"
            isChecked = prefs.autostart
            setOnCheckedChangeListener { _, c -> prefs.autostart = c }
        })
        add(TextView(this).apply { text = "Leer cada (segundos):" })
        add(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.intervalSec.toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.let { prefs.intervalSec = it.coerceIn(3, 300) }
                }
            })
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Iniciar"; setOnClickListener { startReading() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Detener"; setOnClickListener { ZoeService.stop(this@MainActivity) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(row)

        add(TextView(this).apply { text = "Registro"; typeface = Typeface.DEFAULT_BOLD; setPadding(0, px(12), 0, 0) })
        tvLog = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        add(tvLog)

        setContentView(ScrollView(this).apply { addView(col) })
        ensurePerms()
    }

    override fun onResume() {
        super.onResume()
        ZoeState.addListener(listener)
        refresh()
    }

    override fun onPause() {
        ZoeState.removeListener(listener)
        super.onPause()
    }

    private fun refresh() {
        val d = ZoeState.data
        tvSoc.text = Fmt.soc(d)
        tvDetails.text = Fmt.details(d)
        tvStatus.text = "Estado: " + (if (ZoeService.running) ZoeState.status else "detenido")
        tvDevice.text = "Scanner: " + (prefs.deviceName ?: "no elegido")
        tvLog.text = ZoeState.logText()
    }

    private fun startReading() {
        if (!ensurePerms()) return
        if (prefs.address == null) {
            toast("Primero elige el scanner")
            return
        }
        if (prefs.overlay && !Settings.canDrawOverlays(this)) toast("Falta el permiso de burbuja flotante")
        ZoeService.start(this)
    }

    /** Pide permisos; devuelve true si ya están los de Bluetooth. */
    private fun ensurePerms(): Boolean {
        val bt = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val all = bt + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = all.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        return bt.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!ensurePerms()) return
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
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
