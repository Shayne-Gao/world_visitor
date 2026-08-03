package io.shayne.fogvisitor

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

class JsBridge(
    private val context: Context,
    private val onStartTracking: () -> Unit,
    private val onStopTracking: () -> Unit,
    private val onPickExport: () -> Unit,
    private val onPickImportReplace: () -> Unit,
    private val onPickImportMerge: () -> Unit
) {

    private val trackStore by lazy { NativeTrackStore(context) }
    private val cloudPrefs by lazy { context.getSharedPreferences("fog_cloud_archive", Context.MODE_PRIVATE) }

    @JavascriptInterface
    fun startBackgroundTracking() {
        onStartTracking.invoke()
    }

    @JavascriptInterface
    fun stopBackgroundTracking() {
        onStopTracking.invoke()
    }

    @JavascriptInterface
    fun showNativeToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    fun copyTextToClipboard(label: String, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

    @JavascriptInterface
    fun getAppFlavor(): String = "android-apk-shell"

    @JavascriptInterface
    fun getNativeTrackingStatus(): String = trackStore.getStatusJson()

    @JavascriptInterface
    fun exportNativeArchiveJson(): String = trackStore.exportArchiveJson()

    @JavascriptInterface
    fun exportNativeArchiveToDownloads(): String = trackStore.exportArchiveToDownloads()

    @JavascriptInterface
    fun pickNativeArchiveExport() {
        onPickExport.invoke()
    }

    @JavascriptInterface
    fun importNativeArchiveJson(rawJson: String, merge: Boolean): String =
        trackStore.importArchiveJson(rawJson, merge)

    @JavascriptInterface
    fun appendNativeTrackJson(rawJson: String): String =
        trackStore.appendTrackJson(rawJson)

    @JavascriptInterface
    fun replaceNativeTracksJson(rawJson: String): String =
        trackStore.replaceTracksJson(rawJson)

    @JavascriptInterface
    fun getNativeArchiveSummary(): String = trackStore.getArchiveSummaryJson()

    @JavascriptInterface
    fun getNativeArchiveTracks(): String = trackStore.getArchiveTracksJson()

    @JavascriptInterface
    fun getNativeArchiveTracksSince(afterTimestamp: Long): String = trackStore.getArchiveTracksSinceJson(afterTimestamp)

    @JavascriptInterface
    fun clearNativeArchive() {
        trackStore.clearArchive()
    }

    @JavascriptInterface
    fun getNativeRecoveryStatus(): String = trackStore.getRecoveryStatusJson()

    @JavascriptInterface
    fun recoverDraftAsTrack(): String {
        val track = trackStore.recoverDraftAsTrack()
        return if (track != null) {
            """{"ok":true,"trackId":"${track.id}"}"""
        } else {
            """{"ok":false,"reason":"no_draft"}"""
        }
    }

    @JavascriptInterface
    fun pickNativeArchiveForReplace() {
        onPickImportReplace.invoke()
    }

    @JavascriptInterface
    fun pickNativeArchiveForMerge() {
        onPickImportMerge.invoke()
    }

    @JavascriptInterface
    fun getCloudArchiveConfig(): String {
        return JSONObject()
            .put("baseUrl", cloudPrefs.getString("baseUrl", "") ?: "")
            .put("slotId", cloudPrefs.getString("slotId", "") ?: "")
            .put("hasToken", !cloudPrefs.getString("token", "").isNullOrBlank())
            .toString()
    }

    @JavascriptInterface
    fun saveCloudArchiveConfig(baseUrl: String, token: String, slotId: String): String {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedSlotId = slotId.trim()
        if (normalizedBaseUrl.isBlank() || token.isBlank() || normalizedSlotId.isBlank()) {
            return JSONObject().put("ok", false).put("error", "baseUrl/token/slotId 不能为空").toString()
        }
        cloudPrefs.edit()
            .putString("baseUrl", normalizedBaseUrl)
            .putString("token", token.trim())
            .putString("slotId", normalizedSlotId)
            .apply()
        return JSONObject().put("ok", true).toString()
    }

    @JavascriptInterface
    fun uploadCloudArchive(rawJson: String, note: String): String {
        return runCatching {
            val config = readCloudConfig()
            val slot = encodePath(config.slotId)
            val url = URL("${config.baseUrl}/api/saves/$slot")
            val bytes = rawJson.toByteArray(StandardCharsets.UTF_8)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Filename", "fog-visitor-save-v2.json")
                setRequestProperty("X-Save-Note", note.ifBlank { "apk-cloud-upload" })
            }
            connection.outputStream.use { it.write(bytes) }
            readCloudResponse(connection)
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName).toString()
        }
    }

    @JavascriptInterface
    fun downloadLatestCloudArchive(): String {
        return runCatching {
            val config = readCloudConfig()
            val slot = encodePath(config.slotId)
            val url = URL("${config.baseUrl}/api/saves/$slot")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
            val status = connection.responseCode
            val body = readStreamText(if (status in 200..299) connection.inputStream else connection.errorStream)
            JSONObject()
                .put("ok", status in 200..299)
                .put("status", status)
                .put("body", body)
                .toString()
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName).toString()
        }
    }

    @JavascriptInterface
    fun getCloudArchiveVersions(): String {
        return runCatching {
            val config = readCloudConfig()
            val slot = encodePath(config.slotId)
            val url = URL("${config.baseUrl}/api/saves/$slot/versions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
            readCloudResponse(connection)
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName).toString()
        }
    }

    private data class CloudConfig(val baseUrl: String, val token: String, val slotId: String)

    private fun readCloudConfig(): CloudConfig {
        val baseUrl = cloudPrefs.getString("baseUrl", "")?.trim()?.trimEnd('/') ?: ""
        val token = cloudPrefs.getString("token", "")?.trim() ?: ""
        val slotId = cloudPrefs.getString("slotId", "")?.trim() ?: ""
        require(baseUrl.isNotBlank() && token.isNotBlank() && slotId.isNotBlank()) {
            "请先配置云存档服务"
        }
        return CloudConfig(baseUrl, token, slotId)
    }

    private fun readCloudResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val body = readStreamText(if (status in 200..299) connection.inputStream else connection.errorStream)
        return JSONObject()
            .put("ok", status in 200..299)
            .put("status", status)
            .put("body", body)
            .toString()
    }

    private fun readStreamText(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
