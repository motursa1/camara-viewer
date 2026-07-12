package com.alanleiva.camaraviewer

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkUtils {

    /**
     * Hace un GET simple y devuelve el cuerpo como texto.
     * Debe llamarse desde un hilo secundario (no el hilo principal).
     */
    fun getText(urlString: String, timeoutMs: Int = 8000): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.requestMethod = "GET"
        return try {
            val stream = connection.inputStream
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            reader.close()
            sb.toString()
        } finally {
            connection.disconnect()
        }
    }
}
