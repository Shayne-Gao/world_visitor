package io.shayne.fogvisitor

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

class JsBridge(
    private val context: Context,
    private val onStartTracking: () -> Unit,
    private val onStopTracking: () -> Unit,
    private val onPickImportReplace: () -> Unit,
    private val onPickImportMerge: () -> Unit
) {

    private val trackStore by lazy { NativeTrackStore(context) }

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
    fun getAppFlavor(): String = "android-apk-shell"

    @JavascriptInterface
    fun getNativeTrackingStatus(): String = trackStore.getStatusJson()

    @JavascriptInterface
    fun exportNativeArchiveJson(): String = trackStore.exportArchiveJson()

    @JavascriptInterface
    fun exportNativeArchiveToDownloads(): String = trackStore.exportArchiveToDownloads()

    @JavascriptInterface
    fun importNativeArchiveJson(rawJson: String, merge: Boolean): String =
        trackStore.importArchiveJson(rawJson, merge)

    @JavascriptInterface
    fun getNativeArchiveSummary(): String = trackStore.getArchiveSummaryJson()

    @JavascriptInterface
    fun getNativeArchiveTracks(): String = trackStore.getArchiveTracksJson()

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
}
