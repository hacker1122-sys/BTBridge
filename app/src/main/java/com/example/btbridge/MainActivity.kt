package com.example.btbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var engine: BridgeEngine
    private lateinit var tvStatus: TextView
    private lateinit var etToken: EditText
    private lateinit var tvLogs: TextView
    private lateinit var progressBar: ProgressBar

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvStatus = findViewById(R.id.tvStatus)
        etToken = findViewById(R.id.etToken)
        tvLogs = findViewById(R.id.tvLogs)
        progressBar = findViewById(R.id.progressBar)

        val prefs = getSharedPreferences("btbridge_prefs", Context.MODE_PRIVATE)
        etToken.setText(prefs.getString("token", "SecureRelay2026"))

        requestAppPermissions()

        engine = BridgeEngine(
            context = this,
            onStatus = { msg, isConnected ->
                runOnUiThread {
                    tvStatus.text = "Status: $msg"
                    tvStatus.setTextColor(if (isConnected) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
                }
            },
            onLog = { logMsg ->
                runOnUiThread {
                    tvLogs.append("> $logMsg\n")
                }
            },
            onProgress = { pct ->
                runOnUiThread { progressBar.progress = pct }
            }
        )

        findViewById<Button>(R.id.btnM1Mode).setOnClickListener {
            val token = saveAndGetToken()
            engine.startM1Mode(token)
        }

        findViewById<Button>(R.id.btnRepeaterMode).setOnClickListener {
            val token = saveAndGetToken()
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            engine.startRepeaterMode(btManager.adapter, token)
        }

        findViewById<Button>(R.id.btnClientMode).setOnClickListener {
            val token = saveAndGetToken()
            showPairedDevicesDialog(token)
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            engine.stopAll()
        }

        findViewById<Button>(R.id.btnSendFile).setOnClickListener {
            filePicker.launch("*/*")
        }
    }

    private fun saveAndGetToken(): String {
        val token = etToken.text.toString().trim()
        getSharedPreferences("btbridge_prefs", Context.MODE_PRIVATE).edit().putString("token", token).apply()
        return token
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevicesDialog(token: String) {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val paired = btManager.adapter?.bondedDevices?.toList() ?: emptyList()
        if (paired.isEmpty()) {
            Toast.makeText(this, "Pair 'M1' in Bluetooth settings first!", Toast.LENGTH_LONG).show()
            return
        }
        val names = paired.map { "${it.name} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select 'M1' Repeater Device")
            .setItems(names) { _, which ->
                engine.startClientMode(paired[which], token)
            }
            .show()
    }

    private fun handleSelectedFile(uri: Uri) {
        var name = "file.bin"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx != -1) name = cursor.getString(nameIdx)
                if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
            }
        }
        val stream = contentResolver.openInputStream(uri) ?: return
        engine.sendFileStream(name, size, stream)
    }

    private fun requestAppPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
        }
    }
}
