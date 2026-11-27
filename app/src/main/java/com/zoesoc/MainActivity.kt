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
import android.graphics.drawable.GradientDrawable
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

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var tvHeader: TextView
    private lateinit var tvAge: TextView
    private lateinit var gauge: GaugeView
    private lateinit var spark: SparkView
    private lateinit var tires: TireView
    private lateinit var tvDevice: TextView
    private lateinit var tvLog: TextView
    private val cards = HashMap<String, TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { refresh() }

    private val bgColor = 0xFF0F1215.toInt()
    private val cardColor = 0xFF1B1F24.toInt()
    private val textMain = 0xFFE9EEF3.toInt()
    private val textDim = 0xFF8D97A1.toInt()

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = bgColor

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(12), px(14), px(20))
        }
        fun add(v: View, top: Int = 8) {
            col.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(top) })
        }

        // Encabezado
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvHeader = text(12f, textDim)
        tvAge = text(12f, textDim).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END }
        head.addView(tvHeader, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tvAge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(head, 0)

        gauge = GaugeView(this)
        add(gauge, 4)

        // Tarjetas: en vivo y batería
        add(section("En vivo"), 6)
        add(row(card("power", "Potencia"), card("speed", "Velocidad")))
        add(row(card("inst", "Consumo instantáneo"), card("avg", "Consumo promedio")))
        add(section("Batería"), 14)
        add(row(card("temp", "Temp. batería"), card("soh", "Salud (SOH)")))
        add(row(card("v12", "Batería 12 V"), card("cells", "Diferencia celdas")))
        add(row(card("plug", "Carga"), card("last", "Última carga")))

        val sohBox = box()
        sohBox.addView(text(11f, textDim).apply { text = "SOH en el tiempo" })
        spark = SparkView(this)
        sohBox.addView(spark)
        add(sohBox)

        add(section("Presión de llantas (PSI)"), 14)
        val tireBox = box()
        tires = TireView(this)
        tireBox.addView(tires)
        add(tireBox)

        // Ajustes
        add(section("Ajustes"), 18)
        tvDevice = text(14f, textMain)
        add(tvDevice)
        add(Button(this).apply { text = "Elegir scanner"; setOnClickListener { pickDevice() } })
        add(Button(this).apply {
            text = "Permitir burbuja flotante"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        add(CheckBox(this).apply {
            text = "Mostrar burbuja flotante"; setTextColor(textMain)
            isChecked = prefs.overlay
            setOnCheckedChangeListener { _, c -> prefs.overlay = c; ZoeState.touch() }
        })
        add(CheckBox(this).apply {
            text = "Iniciar automáticamente al encender"; setTextColor(textMain)
            isChecked = prefs.autostart
            setOnCheckedChangeListener { _, c -> prefs.autostart = c }
        })
        add(numberField("Leer la batería cada (segundos):", prefs.intervalSec) { prefs.intervalSec = it.coerceIn(5, 300) })
        add(numberField("Presión recomendada (PSI):", prefs.refPsi) { prefs.refPsi = it.coerceIn(20, 60); ZoeState.touch() })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply { text = "Iniciar"; setOnClickListener { startReading() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply { text = "Detener"; setOnClickListener { ZoeService.stop(this@MainActivity) } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(btns)

        add(section("Registro"), 14)
        tvLog = text(11f, textDim).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        add(tvLog, 4)

        setContentView(ScrollView(this).apply { setBackgroundColor(bgColor); addView(col) })
        ensurePerms()
    }

    override fun onResume() {
        super.onResume()
        ZoeState.appVisible = true
        ZoeState.addListener(listener)
        refresh()
    }

    override fun onPause() {
        ZoeState.appVisible = false
        ZoeState.removeListener(listener)
        super.onPause()
    }

    // ---------- piezas de la interfaz ----------

    private fun text(size: Float, color: Int) = TextView(this).apply { textSize = size; setTextColor(color) }

    private fun section(title: String) = text(15f, textMain).apply { text = title; typeface = Typeface.DEFAULT_BOLD }

    private fun box() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(cardColor); cornerRadius = px(10).toFloat() }
        setPadding(px(10), px(8), px(10), px(8))
    }

    private fun card(key: String, label: String): View {
        val b = box()
        b.addView(text(11f, textDim).apply { text = label })
        val v = text(18f, textMain).apply { text = "--"; typeface = Typeface.DEFAULT_BOLD }
        b.addView(v)
        cards[key] = v
        return b
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(4) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(4) })
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

    private fun put(key: String, value: String?, color: Int = textMain) {
        cards[key]?.apply { text = value ?: "--"; setTextColor(if (value == null) textDim else color) }
    }

    // ---------- datos ----------

    private fun refresh() {
        val d = ZoeState.data
        val running = ZoeService.running
        tvHeader.text = "Zoe · " + (if (running) ZoeState.status.let { if (it == "OK") "conectado" else it } else "detenido")
        tvAge.text = d?.let { "hace ${(System.currentTimeMillis() - it.time) / 1000} s" } ?: ""
        gauge.data = d
        gauge.invalidate()

        val live = d != null && System.currentTimeMillis() - d.fastTime < 10_000
        put("power", if (live) d?.powerKw?.let { Fmt.f(it, 1) + " kW" } else null,
            if ((d?.powerKw ?: 0.0) < 0) Colors.GREEN else textMain)
        put("speed", if (live) d?.speedKmh?.let { Fmt.f(it, 0) + " km/h" } else null)
        put("inst", if (live) d?.instantConsumption?.let { Fmt.f(it, 1) + " kWh/100" } else null)
        put("avg", d?.avgConsumption?.let { Fmt.f(it, 1) + " kWh/100" })
        put("temp", d?.tempC?.let { Fmt.f(it, 0) + " °C" })
        put("soh", d?.soh?.let { Fmt.f(it, 1) + " %" })
        put("v12", d?.v12?.let { Fmt.f(it, 1) + " V" }, if ((d?.v12 ?: 13.0) < 12.0) Colors.AMBER else textMain)
        put("cells", d?.cellDiffMv?.let { "$it mV" }, if ((d?.cellDiffMv ?: 0) > 50) Colors.AMBER else textMain)
        put("plug", when {
            d == null || d.plugged == null -> null
            d.charging -> "Cargando"
            d.plugged == true -> "Conectado"
            else -> "Desconectado"
        }, if (d?.charging == true) Colors.AMBER else textMain)
        put("last", prefs.lastCharge)

        spark.points = prefs.sohHistory.map { it.second }
        spark.invalidate()
        tires.data = d
        tires.ref = prefs.refPsi
        tires.invalidate()

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
