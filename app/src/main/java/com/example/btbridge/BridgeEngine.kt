package com.example.btbridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class BridgeEngine(
    private val context: Context,
    private val onStatus: (String, Boolean) -> Unit,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    private val executor = Executors.newCachedThreadPool()
    private var isRunning = false
    private var activeOutStream: OutputStream? = null
    private var btServerSocket: BluetoothServerSocket? = null
    private var wifiServerSocket: ServerSocket? = null
    private var activeSocket: java.io.Closeable? = null

    fun stopAll() {
        isRunning = false
        try { btServerSocket?.close() } catch (_: Exception) {}
        try { wifiServerSocket?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        activeOutStream = null
        onStatus("Disconnected", false)
        onLog("Service stopped.")
    }

    fun startM1Mode(token: String) {
        stopAll()
        isRunning = true
        onStatus("M1 Mode Active (Waiting for Repeater)", true)
        executor.execute {
            try {
                wifiServerSocket = ServerSocket(BridgeProtocol.WIFI_PORT)
                onLog("M1 Server listening on port ${BridgeProtocol.WIFI_PORT}")
                while (isRunning) {
                    val client = wifiServerSocket?.accept() ?: break
                    onLog("Repeater connected: ${client.inetAddress.hostAddress}")
                    if (!BridgeProtocol.verifyHandshake(client.getInputStream(), token)) {
                        onLog("Security Reject: Invalid Token!")
                        client.close()
                        continue
                    }
                    onLog("Handshake Verified! Link ready.")
                    activeSocket = client
                    activeOutStream = client.getOutputStream()
                    listenForFiles(client.getInputStream())
                }
            } catch (e: Exception) {
                if (isRunning) onLog("M1 Error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRepeaterMode(btAdapter: BluetoothAdapter?, token: String) {
        stopAll()
        if (btAdapter == null || !btAdapter.isEnabled) {
            onLog("Error: Enable Bluetooth first!")
            return
        }
        isRunning = true
        onStatus("Repeater Mode Active", true)

        executor.execute {
            try {
                val gatewayIp = getWifiGatewayIp()
                if (gatewayIp == "0.0.0.0") {
                    onLog("Error: Not connected to M1 Hotspot!")
                    stopAll()
                    return@execute
                }
                onLog("M1 Hotspot Gateway IP: $gatewayIp")

                btServerSocket = btAdapter.listenUsingRfcommWithServiceRecord("BTBridge", BridgeProtocol.APP_UUID)
                onLog("Bluetooth server listening as 'M1'")

                while (isRunning) {
                    val btSocket = btServerSocket?.accept() ?: break
                    onLog("Client connected via Bluetooth: ${btSocket.remoteDevice.name}")

                    executor.execute {
                        try {
                            val wifiSocket = Socket(gatewayIp, BridgeProtocol.WIFI_PORT)
                            onLog("Transparent Pipe Established: Client <--> Repeater <--> M1")
                            
                            val t1 = Thread { pipeStreams(btSocket.inputStream, wifiSocket.outputStream) }
                            val t2 = Thread { pipeStreams(wifiSocket.inputStream, btSocket.outputStream) }
                            t1.start(); t2.start()
                            t1.join(); t2.join()
                            
                            wifiSocket.close()
                            btSocket.close()
                            onLog("Client disconnected.")
                        } catch (e: Exception) {
                            onLog("Pipe Error: ${e.message}")
                            try { btSocket.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) onLog("Repeater Error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startClientMode(device: BluetoothDevice, token: String) {
        stopAll()
        isRunning = true
        onStatus("Connecting to ${device.name}...", false)

        executor.execute {
            try {
                val btSocket: BluetoothSocket = device.createRfcommSocketToServiceRecord(BridgeProtocol.APP_UUID)
                btSocket.connect()
                BridgeProtocol.sendHandshake(btSocket.outputStream, token)
                
                activeSocket = btSocket
                activeOutStream = btSocket.outputStream
                onStatus("Connected to ${device.name}", true)
                onLog("Link established successfully!")
                listenForFiles(btSocket.inputStream)
            } catch (e: Exception) {
                onStatus("Connection Failed", false)
                onLog("Failed: ${e.message}")
            }
        }
    }

    private fun listenForFiles(inStream: InputStream) {
        try {
            while (isRunning) {
                val ok = BridgeProtocol.receiveFile(inStream, onLog, onProgress)
                if (!ok) break
            }
        } catch (e: Exception) {
            if (isRunning) onLog("Link closed: ${e.message}")
        } finally {
            if (isRunning) onStatus("Disconnected", false)
        }
    }

    fun sendFileStream(fileName: String, fileSize: Long, inputStream: InputStream) {
        val out = activeOutStream
        if (out == null) {
            onLog("Error: Connect link first!")
            return
        }
        executor.execute {
            try {
                onLog("Sending: $fileName...")
                BridgeProtocol.sendFile(out, fileName, fileSize, inputStream, onProgress)
                onLog("Sent Successfully: $fileName")
                onProgress(0)
            } catch (e: Exception) {
                onLog("Send Failed: ${e.message}")
            }
        }
    }

    private fun pipeStreams(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            var bytes: Int
            while (input.read(buffer).also { bytes = it } != -1) {
                output.write(buffer, 0, bytes)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getWifiGatewayIp(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifiManager.dhcpInfo
        return Formatter.formatIpAddress(dhcp.gateway)
    }
}
