package io.shayne.fogvisitor

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.net.HttpURLConnection
import java.net.URL

class TrackingForegroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var trackStore: NativeTrackStore
    private var locationCallback: LocationCallback? = null
    private var isExplicitStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        trackStore = NativeTrackStore(this)
        //#region debug-point apk-ui-storage-regression-service-create
        reportDebugEvent("service_on_create", emptyMap())
        //#endregion
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //#region debug-point apk-ui-storage-regression-service-start-command
        reportDebugEvent(
            "service_on_start_command",
            mapOf("action" to (intent?.action ?: "null"))
        )
        //#endregion
        when (intent?.action) {
            ACTION_START -> startInForeground()
            ACTION_STOP -> stopSelfSafely()
        }
        return START_STICKY
    }

    private fun startInForeground() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isExplicitStop = false
        trackStore.markTrackingRequested(true)
        trackStore.markTrackingRunning(true, shouldTrack = true)
        //#region debug-point apk-ui-storage-regression-service-foreground
        reportDebugEvent("service_start_in_foreground", emptyMap())
        //#endregion
        startLocationUpdates()
    }

    private fun stopSelfSafely() {
        isExplicitStop = true
        stopLocationUpdates()
        trackStore.finalizeDraftToTrack()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        if (!isExplicitStop && trackStore.shouldTrack()) {
            scheduleSelfRestart()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (trackStore.shouldTrack()) {
            scheduleSelfRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_notification_title))
            .setContentText(getString(R.string.tracking_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun startLocationUpdates() {
        //#region debug-point apk-ui-storage-regression-service-location-start
        reportDebugEvent(
            "service_start_location_updates",
            mapOf(
                "hasPermission" to hasLocationPermission().toString(),
                "hasCallback" to (locationCallback != null).toString()
            )
        )
        //#endregion
        if (!hasLocationPermission()) return
        if (locationCallback != null) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateDistanceMeters(10f)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    //#region debug-point apk-ui-storage-regression-service-location-result
                    reportDebugEvent(
                        "service_location_result",
                        mapOf(
                            "lat" to location.latitude.toString(),
                            "lng" to location.longitude.toString(),
                            "acc" to location.accuracy.toString()
                        )
                    )
                    //#endregion
                    trackStore.appendDraftPoint(
                        lng = location.longitude,
                        lat = location.latitude
                    )
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback as LocationCallback,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        val callback = locationCallback ?: return
        fusedLocationClient.removeLocationUpdates(callback)
        locationCallback = null
        trackStore.markTrackingRunning(false, shouldTrack = !isExplicitStop && trackStore.shouldTrack())
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fog Visitor Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun scheduleSelfRestart() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val restartIntent = Intent(this, TrackingForegroundService::class.java).apply {
            action = ACTION_START
        }
        val pendingIntent = PendingIntent.getService(
            this,
            2001,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 5_000L,
            pendingIntent
        )
    }

    companion object {
        const val ACTION_START = "io.shayne.fogvisitor.action.START_TRACKING"
        const val ACTION_STOP = "io.shayne.fogvisitor.action.STOP_TRACKING"

        private const val CHANNEL_ID = "fog_visitor_tracking"
        private const val NOTIFICATION_ID = 1001
    }

    //#region debug-point apk-ui-storage-regression-service-reporter
    private fun reportDebugEvent(name: String, payload: Map<String, String>) {
        Thread {
            runCatching {
                val body = buildString {
                    append("{")
                    append("\"session_id\":\"apk-ui-storage-regression\",")
                    append("\"event\":\"").append(jsonEscape(name)).append("\",")
                    append("\"payload\":{")
                    append(payload.entries.joinToString(",") { entry ->
                        "\"${jsonEscape(entry.key)}\":\"${jsonEscape(entry.value)}\""
                    })
                    append("}}")
                }
                val conn = (URL("http://127.0.0.1:7777/event").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 1000
                    readTimeout = 1000
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.inputStream.close()
                conn.disconnect()
            }
        }.start()
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }
    //#endregion
}
