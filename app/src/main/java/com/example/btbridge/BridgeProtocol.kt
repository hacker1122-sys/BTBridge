package com.example.btbridge

import android.os.Environment
import java.io.*
import java.util.UUID

object BridgeProtocol {
    val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val WIFI_PORT = 8989
    const val MAX_FILE_SIZE = 100L * 1024 * 1024 // 100 MB Limit

    fun sendHandshake(outStream: OutputStream, token: String) {
        val dos = DataOutputStream(outStream)
        dos.writeUTF("BTBRIDGE_V1")
        dos.writeUTF(token)
        dos.flush()
    }

    fun verifyHandshake(inStream: InputStream, expectedToken: String): Boolean {
        return try {
            val dis = DataInputStream(inStream)
            val header = dis.readUTF()
            val token = dis.readUTF()
            header == "BTBRIDGE_V1" && token == expectedToken
        } catch (e: Exception) {
            false
        }
    }

    fun sendFile(outStream: OutputStream, fileName: String, fileSize: Long, fileInput: InputStream, onProgress: (Int) -> Unit) {
        if (fileSize > MAX_FILE_SIZE) throw IOException("File 100MB se bari hai!")
        val safeName = File(fileName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        
        val dos = DataOutputStream(outStream)
        dos.writeUTF("FILE")
        dos.writeUTF(safeName)
        dos.writeLong(fileSize)

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalSent = 0L
        while (fileInput.read(buffer).also { bytesRead = it } != -1) {
            dos.write(buffer, 0, bytesRead)
            totalSent += bytesRead
            if (fileSize > 0) {
                onProgress(((totalSent * 100) / fileSize).toInt())
            }
        }
        dos.flush()
    }

    fun receiveFile(inStream: InputStream, onLog: (String) -> Unit, onProgress: (Int) -> Unit): Boolean {
        val dis = DataInputStream(inStream)
        val type = dis.readUTF()
        if (type != "FILE") return false

        val rawName = dis.readUTF()
        val fileSize = dis.readLong()
        if (fileSize > MAX_FILE_SIZE || fileSize < 0) return false

        val safeName = File(rawName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloadsDir, "BTBridge")
        if (!appDir.exists()) appDir.mkdirs()

        val outFile = File(appDir, "${System.currentTimeMillis()}_$safeName")
        FileOutputStream(outFile).use { fos ->
            val buffer = ByteArray(8192)
            var remaining = fileSize
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = dis.read(buffer, 0, toRead)
                if (read == -1) throw EOFException("Connection broken")
                fos.write(buffer, 0, read)
                remaining -= read
                if (fileSize > 0) {
                    onProgress((((fileSize - remaining) * 100) / fileSize).toInt())
                }
            }
        }
        onLog("Saved: Downloads/BTBridge/${outFile.name}")
        return true
    }
}
