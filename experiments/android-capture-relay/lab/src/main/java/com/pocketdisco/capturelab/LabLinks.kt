package com.pocketdisco.capturelab

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.pocketdisco.capturelab.protocol.RelayNode
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class LabLinks(
    context: Context,
    private val node: RelayNode,
    private val event: (String) -> Unit,
) : Closeable {
    private val bluetooth = context.applicationContext.getSystemService(BluetoothManager::class.java).adapter
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Closeable>()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val connecting = AtomicBoolean(false)
    private var tcpServer: ServerSocket? = null
    private var bluetoothServer: BluetoothServerSocket? = null

    @Synchronized
    fun listenTcp() {
        check(!closed.get())
        if (tcpServer != null) return
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 3)
        } catch (error: Exception) {
            discard(server)
            throw error
        }
        tcpServer = server
        sockets.add(server)
        launch("tcp-listener") {
            try {
                event("USB/TCP listening on device loopback:$PORT")
                while (!closed.get()) {
                    val socket = server.accept()
                    if (!socket.inetAddress.isLoopbackAddress) {
                        discard(socket)
                        continue
                    }
                    try {
                        attachTcp(socket)
                    } catch (error: Exception) {
                        report(error)
                    }
                }
            } finally {
                discard(server)
                synchronized(this) {
                    if (tcpServer === server) tcpServer = null
                }
            }
        }
    }

    fun connectEmulator() = connect("USB/TCP") {
        val socket = Socket()
        if (!register(socket)) return@connect
        try {
            socket.connect(InetSocketAddress("10.0.2.2", PORT), 5000)
            attachTcp(socket)
        } catch (error: Exception) {
            discard(socket)
            throw error
        }
    }

    @Synchronized
    fun listenBluetooth() {
        check(!closed.get())
        if (bluetoothServer != null) return
        val adapter = bluetooth ?: error("Bluetooth is unavailable")
        check(adapter.isEnabled) { "Enable Bluetooth in Android settings first" }
        val server = adapter.listenUsingRfcommWithServiceRecord("PocketDisco Capture Lab", SERVICE_ID)
        bluetoothServer = server
        sockets.add(server)
        launch("bluetooth-listener") {
            try {
                event("Paired Bluetooth listener ready")
                while (!closed.get()) {
                    val socket = server.accept()
                    try {
                        attachBluetooth(socket)
                    } catch (error: Exception) {
                        report(error)
                    }
                }
            } finally {
                discard(server)
                synchronized(this) {
                    if (bluetoothServer === server) bluetoothServer = null
                }
            }
        }
    }

    fun connectBluetooth(address: String) = connect("Bluetooth") {
        val adapter = bluetooth ?: error("Bluetooth is unavailable")
        check(adapter.isEnabled) { "Enable Bluetooth in Android settings first" }
        val device = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: error("Pair this phone in Android settings first")
        val socket = device.createRfcommSocketToServiceRecord(SERVICE_ID)
        if (!register(socket)) return@connect
        var deadline: ScheduledFuture<*>? = null
        try {
            deadline = timer.schedule({ discard(socket) }, 10, TimeUnit.SECONDS)
            socket.connect()
            deadline.cancel(false)
            attachBluetooth(socket)
        } catch (error: Exception) {
            discard(socket)
            throw error
        } finally {
            deadline?.cancel(false)
        }
    }

    private fun attachTcp(socket: Socket) {
        if (!register(socket)) return
        try {
            socket.tcpNoDelay = true
            node.attach(socket.getInputStream(), socket.getOutputStream()) { discard(socket) }
        } catch (error: Exception) {
            discard(socket)
            throw error
        }
    }

    private fun attachBluetooth(socket: BluetoothSocket) {
        if (!register(socket)) return
        try {
            node.attach(socket.inputStream, socket.outputStream) { discard(socket) }
        } catch (error: Exception) {
            discard(socket)
            throw error
        }
    }

    private fun register(socket: Closeable): Boolean {
        val accepted = synchronized(this) {
            if (closed.get()) false else {
                sockets.add(socket)
                true
            }
        }
        if (!accepted) discard(socket)
        return accepted
    }

    private fun discard(socket: Closeable) {
        sockets.remove(socket)
        runCatching { socket.close() }
    }

    private fun connect(name: String, action: () -> Unit) {
        check(!closed.get())
        check(connecting.compareAndSet(false, true)) { "A connection is already in progress" }
        launch("connect") {
            try {
                event("Connecting $name")
                action()
            } finally {
                connecting.set(false)
            }
        }
    }

    private fun launch(name: String, action: () -> Unit) {
        Thread({
            try {
                action()
            } catch (error: Exception) {
                report(error)
            }
        }, "lab-$name").apply { isDaemon = true }.start()
    }

    private fun report(error: Exception) {
        if (!closed.get()) event(error.message ?: "Link closed")
    }

    override fun close() {
        val active = synchronized(this) {
            if (!closed.compareAndSet(false, true)) return
            tcpServer = null
            bluetoothServer = null
            sockets.toList().also { sockets.clear() }
        }
        active.forEach { runCatching { it.close() } }
        timer.shutdownNow()
        node.close()
    }

    companion object {
        const val PORT = 18990
        val SERVICE_ID: UUID = UUID.fromString("d35c0d15-5c28-48a5-ae72-d4f926d3a6d0")
    }
}
