package com.zoesoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Falla de conexión con el scanner (se resuelve reconectando). */
class TransportException(msg: String) : Exception(msg)

/** Canal de texto con el ELM327: se escribe un comando y se lee hasta el prompt '>'. */
interface Transport {
    fun connect()
    fun write(s: String)
    fun readUntilPrompt(timeoutMs: Long): String
    fun clearInput()
    fun close()
}

abstract class BufferedTransport : Transport {
    private val buf = StringBuilder()
    private val lock = java.lang.Object()

    @Volatile
    protected var closed = false

    protected fun onData(s: String) {
        synchronized(lock) {
            buf.append(s)
            lock.notifyAll()
        }
    }

    protected fun markClosed() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
    }

    override fun clearInput() {
        synchronized(lock) { buf.setLength(0) }
    }

    override fun readUntilPrompt(timeoutMs: Long): String {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                val i = buf.indexOf(">")
                if (i >= 0) {
                    val r = buf.substring(0, i)
                    buf.delete(0, i + 1)
                    return r
                }
                if (closed) throw TransportException("Conexión cerrada")
                val left = end - System.currentTimeMillis()
                if (left <= 0) throw TransportException("El scanner no respondió")
                lock.wait(left)
            }
        }
    }
}

/** Bluetooth clásico (perfil serial SPP). */
@SuppressLint("MissingPermission")
class SppTransport(private val device: BluetoothDevice) : BufferedTransport() {
    private var socket: BluetoothSocket? = null

    override fun connect() {
        val s = openSocket()
        socket = s
        val input = s.inputStream
        thread(isDaemon = true, name = "spp-rx") {
            val b = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    if (n > 0) onData(String(b, 0, n, Charsets.US_ASCII))
                }
            } catch (e: IOException) {
                // conexión perdida
            }
            markClosed()
        }
    }

    private fun openSocket(): BluetoothSocket {
        val first = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            first.connect()
            return first
        } catch (e: IOException) {
            try { first.close() } catch (x: IOException) { }
        }
        // Algunos dongles solo aceptan el canal 1 directo.
        try {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val second = m.invoke(device, 1) as BluetoothSocket
            try {
                second.connect()
                return second
            } catch (e: IOException) {
                try { second.close() } catch (x: IOException) { }
                throw TransportException("SPP: ${e.message}")
            }
        } catch (e: TransportException) {
            throw e
        } catch (e: Exception) {
            throw TransportException("SPP: ${e.message}")
        }
    }

    override fun write(s: String) {
        val out = socket?.outputStream ?: throw TransportException("No conectado")
        try {
            out.write(s.toByteArray(Charsets.US_ASCII))
            out.flush()
        } catch (e: IOException) {
            markClosed()
            throw TransportException("Escritura falló: ${e.message}")
        }
    }

    override fun close() {
        markClosed()
        try { socket?.close() } catch (e: IOException) { }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** Bluetooth Low Energy (el Vgate iCar Pro 2S lo usa). Busca el servicio serial por sí solo. */
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : BufferedTransport() {

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CountDownLatch(1)
    private val writeSem = Semaphore(1)

    @Volatile private var error: String? = null
    @Volatile private var payload = 20

    private fun rx(v: ByteArray) = onData(String(v, Charsets.US_ASCII))
    private fun dead() = markClosed()

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(185)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (error == null) error = "BLE desconectado (código $status)"
                dead()
                ready.countDown()
                writeSem.release()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) payload = maxOf(20, mtu - 3)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            setup(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeSem.release()
        }

        @Deprecated("Android 12 o menos")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            rx(v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            rx(value)
        }
    }

    override fun connect() {
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(20, TimeUnit.SECONDS)) throw TransportException("BLE: tiempo agotado")
        error?.let { throw TransportException(it) }
        if (writeChar == null) throw TransportException("BLE: sin canal de escritura")
    }

    @Suppress("DEPRECATION")
    private fun setup(g: BluetoothGatt) {
        val known = listOf(
            "e7810a71-73ae-499d-8c15-faa9aef0c3f2", // Vgate
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "000018f0-0000-1000-8000-00805f9b34fb",
        )
        val services = g.services.sortedBy { s ->
            val i = known.indexOf(s.uuid.toString().lowercase())
            if (i < 0) 99 else i
        }
        var nc: BluetoothGattCharacteristic? = null
        var wc: BluetoothGattCharacteristic? = null
        for (s in services) {
            val id = s.uuid.toString().lowercase()
            if (id.startsWith("0000180")) continue // servicios genéricos (1800-180F)
            val n = s.characteristics.firstOrNull { (it.properties and (NOTIFY or INDICATE)) != 0 }
            val w = s.characteristics.firstOrNull { (it.properties and (WRITE or WRITE_NR)) != 0 }
            if (n != null && w != null) {
                nc = n
                wc = w
                log("BLE: servicio $id")
                break
            }
        }
        val n = nc
        val w = wc
        if (n == null || w == null) {
            error = "BLE: no encontré el servicio serial del scanner"
            ready.countDown()
            return
        }
        writeChar = w
        g.setCharacteristicNotification(n, true)
        val d = n.getDescriptor(CCCD)
        if (d == null) {
            ready.countDown()
            return
        }
        val v = if ((n.properties and NOTIFY) != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v)
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    override fun write(s: String) {
        val g = gatt ?: throw TransportException("No conectado")
        val c = writeChar ?: throw TransportException("No conectado")
        if (closed) throw TransportException("Conexión cerrada")
        val type = if ((c.properties and WRITE) != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val bytes = s.toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i < bytes.size) {
            val chunk = bytes.copyOfRange(i, minOf(i + payload, bytes.size))
            if (!writeSem.tryAcquire(3, TimeUnit.SECONDS)) throw TransportException("BLE: escritura sin confirmar")
            if (closed) throw TransportException("Conexión cerrada")
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, type) == BluetoothStatusCodes.SUCCESS
            } else {
                c.writeType = type
                c.value = chunk
                g.writeCharacteristic(c)
            }
            if (!ok) {
                writeSem.release()
                throw TransportException("BLE: escritura falló")
            }
            i += chunk.size
        }
    }

    override fun close() {
        markClosed()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) { }
        gatt = null
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        const val INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        const val WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    }
}

/** Elige BLE o clásico según lo que reporte el dispositivo, probando el otro si falla. */
object Connector {
    @SuppressLint("MissingPermission")
    fun open(ctx: Context, address: String, log: (String) -> Unit): Transport {
        val adapter: BluetoothAdapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportException("Este equipo no tiene Bluetooth")
        if (!adapter.isEnabled) throw TransportException("Bluetooth apagado")
        try { adapter.cancelDiscovery() } catch (e: SecurityException) { }
        val dev = adapter.getRemoteDevice(address)
        val order = when (dev.type) {
            BluetoothDevice.DEVICE_TYPE_LE -> listOf("BLE")
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf("SPP")
            BluetoothDevice.DEVICE_TYPE_DUAL -> listOf("BLE", "SPP")
            else -> if (dev.bondState == BluetoothDevice.BOND_BONDED) listOf("SPP", "BLE") else listOf("BLE", "SPP")
        }
        var last: Exception? = null
        for (kind in order) {
            val t: Transport = if (kind == "SPP") SppTransport(dev) else BleTransport(ctx, dev, log)
            try {
                log("Conectando por $kind…")
                t.connect()
                log("Conectado por $kind")
                return t
            } catch (e: Exception) {
                last = e
                log("$kind falló: ${e.message}")
                t.close()
            }
        }
        throw TransportException("No se pudo conectar: ${last?.message}")
    }
}
