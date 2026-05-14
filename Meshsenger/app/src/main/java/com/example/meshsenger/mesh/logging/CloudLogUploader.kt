package com.example.meshsenger.mesh.logging

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CloudLogUploader(
    private val context: Context,
) {
    suspend fun uploadLogs(
        endpointUrl: String,
        secretToken: String,
        nodeId: String,
        displayName: String,
        appVersion: String,
        status: String,
        logs: String,
    ): Result<CloudLogUploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedUrl = endpointUrl.trim()
            val trimmedToken = secretToken.trim()

            require(trimmedUrl.isNotBlank()) { "URL загрузки логов пустой" }
            require(trimmedToken.isNotBlank()) { "Token загрузки логов пустой" }

            val payload = JSONObject()
                .put("token", trimmedToken)
                .put("deviceName", deviceName())
                .put("nodeId", nodeId)
                .put("displayName", displayName)
                .put("appVersion", appVersion)
                .put("status", status)
                .put("logs", logs)

            val responseText = postJson(trimmedUrl, payload.toString())
            val responseJson = JSONObject(responseText)

            val ok = responseJson.optBoolean("ok", false)
            if (!ok) {
                val errorMessage = responseJson.optString("error", "Неизвестная ошибка Apps Script")
                throw IllegalStateException(errorMessage)
            }

            CloudLogUploadResult(
                fileName = responseJson.optString("fileName"),
                fileUrl = responseJson.optString("fileUrl"),
                logsLength = responseJson.optLong("logsLength", logs.length.toLong()),
                rawResponse = responseText,
            )
        }
    }

    private fun postJson(endpointUrl: String, body: String): String {
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val responseText = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            if (code !in 200..299) {
                error("HTTP $code: $responseText")
            }

            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "Android device" }
    }
}

data class CloudLogUploadResult(
    val fileName: String,
    val fileUrl: String,
    val logsLength: Long,
    val rawResponse: String,
)
