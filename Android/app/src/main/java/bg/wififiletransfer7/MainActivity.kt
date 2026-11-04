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

            return try {
                // NanoHTTPD 2.3.1 обработва суров POST по особен начин:
                // parseBody() може да постави самите байтове в "postData",
                // а не път към временен файл. Затова четем директно потока
                // и точно Content-Length байта, без да буферираме целия файл в RAM.
                val contentLength = session.headers["content-length"]?.toLongOrNull()
                    ?: return newFixedLengthResponse(Response.Status.LENGTH_REQUIRED, "text/plain", "Missing Content-Length")
                if (contentLength < 0L) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid Content-Length")
                }

                val rawName = session.headers["x-file-name"] ?: "received_file.bin"
                val decoded = try { java.net.URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
                val safeName = File(decoded).name
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "received_file.bin" }

                if (!folder.exists() && !folder.mkdirs()) {
                    throw IOException("Не може да се създаде папката: ${folder.absolutePath}")
                }

                // При повторно изпращане добавяме номер, вместо да презаписваме файл.
                val base = safeName.substringBeforeLast('.', safeName)
                val ext = if (safeName.contains('.')) "." + safeName.substringAfterLast('.') else ""
                var target = File(folder, safeName)
                var index = 1
                while (target.exists()) {
                    target = File(folder, "$base ($index)$ext")
                    index++
                }

                session.inputStream.use { input ->
                    java.io.FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = contentLength
                        while (remaining > 0L) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) throw IOException("Връзката прекъсна. Остават ${remaining} байта.")
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        output.flush()
                    }
                }

                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
