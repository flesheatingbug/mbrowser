package app.mbrowser

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : IOException(message)

class Api(private val base: String, private val token: String, private val device: String) {

    fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-Device", device)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("error", text) } catch (_: Exception) { text.take(200) }
                val hint = if (code == 401 && text.isNotEmpty() && !text.trimStart().startsWith("{"))
                    " (a proxy in front of the server may be asking for its own login)" else ""
                throw ApiException(code, "HTTP $code: $msg$hint")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    fun tabsGet(): JSONObject = request("GET", "/api/tabs?exclude=" + URLEncoder.encode(device, "UTF-8"))
}
