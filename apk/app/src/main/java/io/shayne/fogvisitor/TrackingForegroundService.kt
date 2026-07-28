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
import android.location.Location
import android.os.Handler
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

class TrackingForegroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var trackStore: NativeTrackStore
    private var locationCallback: LocationCallback? = null
    private var isExplicitStop = false
    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedAt: Long = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var locationWatchdog: Runnable? = null
    private var lastLocationCallbackAt: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        trackStore = NativeTrackStore(this)
        restoreLastAcceptedLocation()
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
            null -> if (trackStore.shouldTrack()) startInForeground()
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
        stopLocationUpdates {
            trackStore.finalizeDraftToTrack()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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
        if (!hasLocationPermission()) {
            trackStore.markTrackingRunning(false, shouldTrack = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (locationCallback != null) {
            requestCurrentLocationProbe("existing_callback")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                lastLocationCallbackAt = System.currentTimeMillis()
                result.lastLocation?.let { location ->
                    persistLocation(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback as LocationCallback,
            Looper.getMainLooper()
        )
        startLocationWatchdog()
        requestCurrentLocationProbe("start_location_updates")

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val maxAgeMs = 2 * 60 * 1000L
            if (location != null && System.currentTimeMillis() - location.time <= maxAgeMs) {
                persistLocation(location)
            } else {
                reportDebugEvent(
                    "service_last_location_ignored",
                    mapOf(
                        "reason" to if (location == null) "null" else "stale",
                        "ageMs" to if (location == null) "null" else (System.currentTimeMillis() - location.time).toString()
                    )
                )
            }
        }
    }

    private fun requestCurrentLocationProbe(reason: String) {
        if (!hasLocationPermission()) return
        reportDebugEvent("service_current_location_probe_start", mapOf("reason" to reason))
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    reportDebugEvent(
                        "service_current_location_probe_empty",
                        mapOf("reason" to reason)
                    )
                    return@addOnSuccessListener
                }
                reportDebugEvent(
                    "service_current_location_probe_success",
                    mapOf(
                        "reason" to reason,
                        "lat" to location.latitude.toString(),
                        "lng" to location.longitude.toString(),
                        "acc" to location.accuracy.toString()
                    )
                )
                persistLocation(location)
            }
            .addOnFailureListener { error ->
                reportDebugEvent(
                    "service_current_location_probe_failed",
                    mapOf(
                        "reason" to reason,
                        "error" to (error.message ?: error.javaClass.simpleName)
                    )
                )
            }
    }

    private fun stopLocationUpdates(onStopped: (() -> Unit)? = null) {
        stopLocationWatchdog()
        val callback = locationCallback
        if (callback == null) {
            trackStore.markTrackingRunning(false, shouldTrack = !isExplicitStop && trackStore.shouldTrack())
            onStopped?.invoke()
            return
        }
        fusedLocationClient.removeLocationUpdates(callback).addOnCompleteListener {
            locationCallback = null
            trackStore.markTrackingRunning(false, shouldTrack = !isExplicitStop && trackStore.shouldTrack())
            onStopped?.invoke()
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
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
        val triggerAt = System.currentTimeMillis() + 5_000L
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }.getOrElse {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun persistLocation(location: Location) {
        reportDebugEvent(
            "service_location_received",
            mapOf(
                "lat" to location.latitude.toString(),
                "lng" to location.longitude.toString(),
                "acc" to location.accuracy.toString(),
                "time" to location.time.toString()
            )
        )
        if (!shouldPersistLocation(location)) return
        maybeResetDraftAfterLongGap()
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
        reportDebugEvent(
            "service_location_write_decision",
            mapOf("decision" to "append_draft_and_checkpoint")
        )
        trackStore.appendDraftPoint(
            lng = location.longitude,
            lat = location.latitude,
            accuracy = location.accuracy.toDouble()
        )
        val track = trackStore.checkpointDraftToTrackIfNeeded(minPointCount = 3)
        lastAcceptedLocation = location
        lastAcceptedAt = System.currentTimeMillis()
        //#region debug-point auto-tracking-broken-service-point-persisted
        reportDebugEvent(
            "service_location_persisted",
            mapOf(
                "draftPointCount" to trackStore.readDraftPoints().size.toString(),
                "trackCheckpointed" to (track != null).toString(),
                "trackCount" to trackStore.readArchiveTracks().size.toString()
            )
        )
        //#endregion
    }

    private fun shouldPersistLocation(location: Location): Boolean {
        if (location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            reportDebugEvent(
                "service_location_skipped_accuracy",
                mapOf("acc" to location.accuracy.toString())
            )
            return false
        }
        val previous = lastAcceptedLocation
        if (previous == null) {
            reportDebugEvent(
                "service_location_first_accept",
                mapOf("reason" to "no_previous_accepted_location")
            )
            return true
        }
        val distance = location.distanceTo(previous)
        val elapsedMs = if (lastAcceptedAt > 0L) {
            System.currentTimeMillis() - lastAcceptedAt
        } else {
            Long.MAX_VALUE
        }
        val effectiveMinDistance = maxOf(
            MIN_POINT_DISTANCE_METERS,
            minOf(
                MAX_EFFECTIVE_DISTANCE_BY_ACCURACY_METERS,
                maxOf(previous.accuracy, location.accuracy) * ACCURACY_DISTANCE_FACTOR
            )
        )
        if (distance < effectiveMinDistance) {
            val allowSlowMovementFallback =
                elapsedMs >= MAX_POINT_IDLE_MS && distance >= MIN_SLOW_MOVEMENT_DISTANCE_METERS
            if (allowSlowMovementFallback) {
                reportDebugEvent(
                    "service_location_accepted_slow_movement",
                    mapOf(
                        "distance" to distance.toString(),
                        "elapsedMs" to elapsedMs.toString(),
                        "threshold" to effectiveMinDistance.toString()
                    )
                )
                return true
            }
            reportDebugEvent(
                "service_location_skipped_stationary",
                mapOf(
                    "distance" to distance.toString(),
                    "threshold" to effectiveMinDistance.toString(),
                    "elapsedMs" to elapsedMs.toString()
                )
            )
            return false
        }
        return true
    }

    private fun maybeResetDraftAfterLongGap() {
        if (lastAcceptedAt <= 0L) return
        val elapsedMs = System.currentTimeMillis() - lastAcceptedAt
        if (elapsedMs <= MAX_TRACK_GAP_MS) return
        val existingDraftCount = trackStore.readDraftPoints().size
        if (existingDraftCount <= 0) return
        trackStore.clearDraft()
        reportDebugEvent(
            "service_track_gap_reset",
            mapOf(
                "elapsedMs" to elapsedMs.toString(),
                "clearedDraftCount" to existingDraftCount.toString()
            )
        )
    }

    private fun startLocationWatchdog() {
        stopLocationWatchdog()
        locationWatchdog = object : Runnable {
            override fun run() {
                if (locationCallback == null) return
                val now = System.currentTimeMillis()
                if (now - lastLocationCallbackAt >= LOCATION_CALLBACK_STALL_MS) {
                    reportDebugEvent(
                        "service_location_watchdog_triggered",
                        mapOf("idleMs" to (now - lastLocationCallbackAt).toString())
                    )
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                reportDebugEvent(
                                    "service_location_watchdog_fix_success",
                                    mapOf(
                                        "lat" to location.latitude.toString(),
                                        "lng" to location.longitude.toString(),
                                        "acc" to location.accuracy.toString()
                                    )
                                )
                                persistLocation(location)
                            }
                        }
                        .addOnFailureListener { error ->
                            reportDebugEvent(
                                "service_location_watchdog_fix_failed",
                                mapOf("error" to (error.message ?: error.javaClass.simpleName))
                            )
                        }
                }
                watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
            }
        }
        lastLocationCallbackAt = System.currentTimeMillis()
        watchdogHandler.postDelayed(locationWatchdog!!, WATCHDOG_TICK_MS)
    }

    private fun stopLocationWatchdog() {
        locationWatchdog?.let { watchdogHandler.removeCallbacks(it) }
        locationWatchdog = null
        lastLocationCallbackAt = 0L
    }

    private fun restoreLastAcceptedLocation() {
        runCatching {
            val status = JSONObject(trackStore.getStatusJson())
            val lat = status.optDouble("lastLat", Double.NaN)
            val lng = status.optDouble("lastLng", Double.NaN)
            if (!lat.isFinite() || !lng.isFinite()) return
            lastAcceptedLocation = Location("track_store_status").apply {
                latitude = lat
                longitude = lng
                val accuracy = status.optDouble("lastAccuracy", Double.NaN)
                if (accuracy.isFinite()) {
                    this.accuracy = accuracy.toFloat()
                }
            }
            lastAcceptedAt = status.optLong("lastPointAt", 0L)
        }
    }

    companion object {
        const val ACTION_START = "io.shayne.fogvisitor.action.START_TRACKING"
        const val ACTION_STOP = "io.shayne.fogvisitor.action.STOP_TRACKING"

        private const val CHANNEL_ID = "fog_visitor_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ACCEPTED_ACCURACY_METERS = 35f
        private const val MIN_POINT_DISTANCE_METERS = 8f
        private const val MIN_SLOW_MOVEMENT_DISTANCE_METERS = 8f
        private const val MAX_EFFECTIVE_DISTANCE_BY_ACCURACY_METERS = 18f
        private const val ACCURACY_DISTANCE_FACTOR = 0.6f
        private const val MAX_POINT_IDLE_MS = 20_000L
        private const val MAX_TRACK_GAP_MS = 120_000L
        private const val WATCHDOG_TICK_MS = 15_000L
        private const val LOCATION_CALLBACK_STALL_MS = 20_000L
    }

    //#region debug-point apk-ui-storage-regression-service-reporter
    private fun reportDebugEvent(name: String, payload: Map<String, String>) {
        Log.d("FogVisitor", "$name $payload")
        trackStore.appendTrackingDebugEvent(name, payload)
    }
    //#endregion
}
