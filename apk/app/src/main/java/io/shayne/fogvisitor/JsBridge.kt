package io.shayne.fogvisitor

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

class JsBridge(
    private val context: Context,
    private val onStartTracking: () -> Unit,
    private val onStopTracking: () -> Unit
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
}
