package bg.wififiletransfer7

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.*
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var server: PhoneServer? = null
    private lateinit var ipInput: EditText
    private lateinit var status: TextView
    private lateinit var receiveButton: Button
    private val pickFile = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }
        layout.addView(TextView(this).apply {
            text = "WiFi File Transfer 7"
            textSize = 25f
        })
        layout.addView(TextView(this).apply {
            text = "Директен обмен на файлове с Windows през Wi-Fi"
            textSize = 15f
            setPadding(0, 8, 0, 24)
        })
        layout.addView(TextView(this).apply { text = "IP адрес на компютъра:" })
        ipInput = EditText(this).apply {
            hint = "Например 192.168.1.20"
            setSingleLine(true)
        }
        layout.addView(ipInput)
        val send = Button(this).apply {
            text = "Избери файл и изпрати към компютъра"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, pickFile)
            }
        }
        layout.addView(send)
        receiveButton = Button(this).apply {
            text = "Стартирай приемането от компютъра"
            setOnClickListener { toggleServer() }
        }
        layout.addView(receiveButton)
        status = TextView(this).apply {
            text = "Готово. За трансфер двете устройства трябва да са в една Wi-Fi мрежа."
            textSize = 14f
            setPadding(0, 22, 0, 0)
        }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun toggleServer() {
        if (server != null) {
            server?.stop()
            server = null
            receiveButton.text = "Стартирай приемането от компютъра"
            status.text = "Приемането е спряно."
            return
        }
        try {
            val folder = File(getExternalFilesDir(null), "Received").apply { mkdirs() }
            server = PhoneServer(folder).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            receiveButton.text = "Спри приемането"
            status.text = "Телефонът приема файлове на порт 8766. IP адресът на телефона се вижда в настройките на Wi-Fi."
        } catch (e: Exception) {
            server = null
            status.text = "Грешка при стартиране: ${e.message}"
        }
    }

    @Deprecated("Deprecated in Android API; kept for compatibility with Android 10")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickFile && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            sendFile(uri)
        }
    }

    private fun sendFile(uri: Uri) {
        val host = ipInput.text.toString().trim()
        if (host.isEmpty()) { status.text = "Въведете IP адреса на компютъра."; return }
        status.text = "Изпращане…"
        thread {
            try {
                val name = queryName(uri)
                val connection = (URL("http://$host:8765/upload").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8"))
                }
                contentResolver.openInputStream(uri).use { input ->
                    connection.outputStream.use { output -> input!!.copyTo(output, 64 * 1024) }
                }
                val code = connection.responseCode
                connection.disconnect()
                runOnUiThread { status.text = if (code in 200..299) "Файлът е изпратен успешно: $name" else "Грешка при изпращане. HTTP $code" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Неуспешно изпращане: ${e.message}" }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "file.bin"
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return name
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private class PhoneServer(private val folder: File) : NanoHTTPD(8766) {
        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET && session.uri == "/health")
                return newFixedLengthResponse(Response.Status.OK, "text/plain", "WiFi File Transfer 7")
            if (session.method != Method.POST || session.uri != "/upload")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")

            val temp = HashMap<String, String>()
            return try {
                session.parseBody(temp)
                val rawName = session.headers["x-file-name"] ?: "received_file.bin"
                val decoded = try { java.net.URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
                val safeName = File(decoded).name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "received_file.bin" }
                val target = File(folder, safeName)
                val uploadPath = temp["postData"]
                if (uploadPath != null) {
                    // NanoHTTPD записва тялото във временен файл; копираме го в папката за получени файлове.
                    File(uploadPath).copyTo(target, overwrite = false)
                } else {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Missing file")
                }
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed: ${e.message}")
            }
        }
    }
}
