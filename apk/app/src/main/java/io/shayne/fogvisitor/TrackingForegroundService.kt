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
import android.os.HandlerThread
import android.os.SystemClock
import android.os.PowerManager
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
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.util.concurrent.Executor

class TrackingForegroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var trackStore: NativeTrackStore
    private val trackingConfigPrefs by lazy { getSharedPreferences(TrackingConfig.PREFS_NAME, Context.MODE_PRIVATE) }
    private var locationCallback: LocationCallback? = null
    @Volatile private var isExplicitStop = false
    private val locationStateLock = Any()
    private var lastAcceptedLocation: Location? = null
    private var lastAcceptedAt: Long = 0L
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var locationWatchdog: Runnable? = null
    @Volatile private var lastLocationCallbackAt: Long = 0L
    private val locationThread = HandlerThread("fog-location-writer")
    private lateinit var locationHandler: Handler
    private lateinit var locationExecutor: Executor
    @Volatile private var session = 0
    @Volatile private var acceptingLocations = false
    private var probeCancellation: CancellationTokenSource? = null
    private var lastSubscriptionAttemptAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        trackStore = NativeTrackStore.getInstance(this)
        locationThread.start()
        locationHandler = Handler(locationThread.looper)
        locationExecutor = Executor { locationHandler.post(it) }
        locationHandler.post { restoreLastAcceptedLocation() }
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
            ACTION_RESTART -> if (trackStore.shouldTrack()) startInForeground() else stopSelf()
            ACTION_STOP -> stopSelfSafely()
            null -> if (trackStore.shouldTrack()) startInForeground() else stopSelf()
        }
        return if (isExplicitStop || !trackStore.shouldTrack()) START_NOT_STICKY else START_STICKY
    }

    private fun startInForeground() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        synchronized(locationStateLock) {
            isExplicitStop = false
            acceptingLocations = true
            trackStore.markTrackingRequested(true)
        }
        cancelSelfRestart()
        //#region debug-point apk-ui-storage-regression-service-foreground
        reportDebugEvent("service_start_in_foreground", emptyMap())
        //#endregion
        startLocationUpdates()
    }

    private fun stopSelfSafely() {
        val stoppedSession = synchronized(locationStateLock) {
            isExplicitStop = true
            acceptingLocations = false
            trackStore.markTrackingRequested(false)
            ++session
        }
        reportDebugEvent("service_explicit_stop", emptyMap())
        cancelSelfRestart()
        stopLocationUpdates {
            locationHandler.post {
                synchronized(locationStateLock) {
                  if (session == stoppedSession && isExplicitStop) {
                    trackStore.finalizeDraftToTrack()
                    watchdogHandler.post {
                        if (session == stoppedSession && isExplicitStop) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else if (acceptingLocations) {
                            // A new START can arrive while the old draft is being finalized.
                            trackStore.markTrackingRequested(true)
                        }
                    }
                  }
                }
            }
        }
    }

    override fun onDestroy() {
        synchronized(locationStateLock) {
            acceptingLocations = false
            session++
        }
        reportDebugEvent("service_on_destroy", mapOf("explicit" to isExplicitStop.toString()))
        stopLocationUpdates()
        if (!isExplicitStop && trackStore.shouldTrack() && hasLocationPermission()) {
            scheduleSelfRestart()
        }
        locationThread.quitSafely()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isExplicitStop && trackStore.shouldTrack()) {
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
            acceptingLocations = false
            trackStore.markTrackingRunning(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (requiresBackgroundLocationPermission() && !hasBackgroundLocationPermission()) {
            reportDebugEvent(
                "service_background_location_permission_missing",
                mapOf("sdk" to Build.VERSION.SDK_INT.toString())
            )
        }
        if (locationCallback != null) {
            return
        }
        val subscriptionSession = ++session
        lastSubscriptionAttemptAt = SystemClock.elapsedRealtime()

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!acceptingLocations || subscriptionSession != session) return
                lastLocationCallbackAt = SystemClock.elapsedRealtime()
                reportDebugEvent("service_location_batch", mapOf("count" to result.locations.size.toString()))
                result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach { location ->
                    if (acceptingLocations && subscriptionSession == session) persistLocation(location, subscriptionSession)
                }
            }
        }

        try { fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback as LocationCallback,
            locationThread.looper
        ).addOnSuccessListener {
            if (acceptingLocations && session == subscriptionSession) trackStore.markTrackingRunning(true)
        }.addOnFailureListener { error ->
            if (session == subscriptionSession) {
                locationCallback = null
                trackStore.markTrackingRunning(false)
                reportDebugEvent("service_subscription_failed", mapOf("error" to error.toString()))
            }
        } } catch (error: SecurityException) {
            locationCallback = null
            trackStore.markTrackingRunning(false)
            reportDebugEvent("service_subscription_failed", mapOf("error" to error.toString()))
        }
        startLocationWatchdog()
        requestCurrentLocationProbe("start_location_updates")

        try { fusedLocationClient.lastLocation.addOnSuccessListener(locationExecutor) { location ->
            val maxAgeMs = 2 * 60 * 1000L
            if (acceptingLocations && session == subscriptionSession && location != null &&
                System.currentTimeMillis() - location.time in 0..maxAgeMs) {
                persistLocation(location, subscriptionSession)
            } else {
                reportDebugEvent(
                    "service_last_location_ignored",
                    mapOf(
                        "reason" to if (location == null) "null" else "stale",
                        "ageMs" to if (location == null) "null" else (System.currentTimeMillis() - location.time).toString()
                    )
                )
            }
        } } catch (error: SecurityException) {
            reportDebugEvent("service_last_location_permission_revoked", mapOf("error" to error.toString()))
        }
    }

    private fun requestCurrentLocationProbe(reason: String) {
        if (!hasLocationPermission() || !acceptingLocations || probeCancellation != null) return
        val probeSession = session
        val cancellation = CancellationTokenSource()
        probeCancellation = cancellation
        watchdogHandler.postDelayed({ cancellation.cancel() }, 15_000L)
        reportDebugEvent("service_current_location_probe_start", mapOf("reason" to reason))
        try { fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener(locationExecutor) { location ->
                if (!acceptingLocations || probeSession != session) return@addOnSuccessListener
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
                persistLocation(location, probeSession)
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
            .addOnCompleteListener {
                if (probeCancellation === cancellation) probeCancellation = null
            }
        } catch (error: SecurityException) {
            cancellation.cancel()
            probeCancellation = null
            trackStore.markTrackingRunning(false)
            reportDebugEvent("service_probe_permission_revoked", mapOf("error" to error.toString()))
        }
    }

    private fun stopLocationUpdates(onStopped: (() -> Unit)? = null) {
        stopLocationWatchdog()
        probeCancellation?.cancel()
        probeCancellation = null
        val callback = locationCallback
        locationCallback = null
        val stoppingSession = session
        if (callback == null) {
            trackStore.markTrackingRunning(false, shouldTrack = !isExplicitStop && trackStore.shouldTrack())
            onStopped?.invoke()
            return
        }
        fusedLocationClient.removeLocationUpdates(callback).addOnCompleteListener {
            if (session == stoppingSession) {
                trackStore.markTrackingRunning(false, shouldTrack = !isExplicitStop && trackStore.shouldTrack())
            }
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

    private fun requiresBackgroundLocationPermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (!requiresBackgroundLocationPermission()) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
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
            action = ACTION_RESTART
        }
        val pendingIntent = PendingIntent.getForegroundService(
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

    private fun cancelSelfRestart() {
        val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        // Also remove alarms created by older APKs that used ACTION_START/getService.
        val oldIntent = Intent(this, TrackingForegroundService::class.java).apply { action = ACTION_START }
        val newIntent = Intent(this, TrackingForegroundService::class.java).apply { action = ACTION_RESTART }
        listOf(
            PendingIntent.getService(this, 2001, oldIntent, flags),
            PendingIntent.getForegroundService(this, 2001, newIntent, flags)
        ).filterNotNull().forEach { pending ->
            manager.cancel(pending)
            pending.cancel()
        }
    }

    private fun persistLocation(location: Location, expectedSession: Int) {
        synchronized(locationStateLock) {
            if (!acceptingLocations || expectedSession != session) return
            try {
                persistAcceptedLocation(location)
            } catch (error: Exception) {
                reportDebugEvent("service_location_write_failed", mapOf("error" to error.toString()))
            }
        }
    }

    private fun persistAcceptedLocation(location: Location) {
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
        maybeCheckpointDraftBeforeSegmentBreak(location)
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
            accuracy = location.accuracy.toDouble(),
            sampledAt = location.time
        )
        val track = trackStore.checkpointDraftToTrackIfNeeded(minPointCount = 2)
        lastAcceptedLocation = location
        lastAcceptedAt = location.time
        //#region debug-point auto-tracking-broken-service-point-persisted
        reportDebugEvent(
            "service_location_persisted",
            mapOf(
                "draftPointCount" to trackStore.readDraftPoints().size.toString(),
                "trackCheckpointed" to (track != null).toString(),
                "trackCount" to trackStore.trackCount().toString()
            )
        )
        //#endregion
    }

    private fun shouldPersistLocation(location: Location): Boolean {
        if (!location.hasAccuracy() || !LocationSamplePolicy.isNewSample(location.time, lastAcceptedAt)) return false
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
            location.time - lastAcceptedAt
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
        val speedMetersPerSecond = distance / maxOf(1f, elapsedMs.toFloat() / 1000f)
        val maxAcceptedSpeed = getMaxAcceptedSpeedMetersPerSecond()
        if (LocationSamplePolicy.isTooFast(distance, elapsedMs, maxAcceptedSpeed)) {
            reportDebugEvent(
                "service_location_skipped_speed",
                mapOf(
                    "distanceMeters" to distance.toString(),
                    "elapsedMs" to elapsedMs.toString(),
                    "speedMps" to speedMetersPerSecond.toString(),
                    "thresholdMps" to maxAcceptedSpeed.toString()
                )
            )
            return false
        }
        return true
    }

    private fun getMaxAcceptedSpeedMetersPerSecond(): Float {
        val configured = trackingConfigPrefs.getFloat(
            TrackingConfig.KEY_MAX_SPEED_METERS_PER_SECOND,
            TrackingConfig.DEFAULT_MAX_SPEED_METERS_PER_SECOND
        )
        return configured.coerceIn(
            TrackingConfig.MIN_MAX_SPEED_METERS_PER_SECOND,
            TrackingConfig.MAX_MAX_SPEED_METERS_PER_SECOND
        )
    }

    private fun maybeCheckpointDraftBeforeSegmentBreak(location: Location) {
        val previous = lastAcceptedLocation ?: return
        if (lastAcceptedAt <= 0L) return
        val elapsedMs = location.time - lastAcceptedAt
        val distanceMeters = location.distanceTo(previous)
        if (!LocationSamplePolicy.breaksSegment(distanceMeters, elapsedMs)) return
        val existingDraftCount = trackStore.readDraftPoints().size
        if (existingDraftCount <= 0) return
        val checkpointed = trackStore.checkpointDraftForSegmentBreak(minPointCount = 2)
        reportDebugEvent(
            "service_track_segment_break",
            mapOf(
                "elapsedMs" to elapsedMs.toString(),
                "distanceMeters" to distanceMeters.toString(),
                "draftPointCount" to existingDraftCount.toString(),
                "checkpointed" to (checkpointed != null).toString()
            )
        )
    }

    private fun startLocationWatchdog() {
        stopLocationWatchdog()
        locationWatchdog = object : Runnable {
            override fun run() {
                if (!acceptingLocations) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastLocationCallbackAt >= LOCATION_CALLBACK_STALL_MS) {
                    val power = getSystemService(PowerManager::class.java)
                    reportDebugEvent(
                        "service_location_watchdog_triggered",
                        mapOf(
                            "idleMs" to (now - lastLocationCallbackAt).toString(),
                            "interactive" to power.isInteractive.toString(),
                            "deviceIdle" to power.isDeviceIdleMode.toString(),
                            "batteryExempt" to power.isIgnoringBatteryOptimizations(packageName).toString(),
                            "backgroundPermission" to hasBackgroundLocationPermission().toString()
                        )
                    )
                    if (now - lastSubscriptionAttemptAt >= LOCATION_RESUBSCRIBE_MS) {
                        session++
                        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
                        locationCallback = null
                        probeCancellation?.cancel()
                        probeCancellation = null
                        startLocationUpdates()
                        return
                    }
                    requestCurrentLocationProbe("watchdog")
                }
                watchdogHandler.postDelayed(this, WATCHDOG_TICK_MS)
            }
        }
        lastLocationCallbackAt = SystemClock.elapsedRealtime()
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
        const val ACTION_RESTART = "io.shayne.fogvisitor.action.RESTART_TRACKING"

        private const val CHANNEL_ID = "fog_visitor_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_ACCEPTED_ACCURACY_METERS = 50f
        private const val MIN_POINT_DISTANCE_METERS = 8f
        private const val MIN_SLOW_MOVEMENT_DISTANCE_METERS = 8f
        private const val MAX_EFFECTIVE_DISTANCE_BY_ACCURACY_METERS = 18f
        private const val ACCURACY_DISTANCE_FACTOR = 0.6f
        private const val MAX_POINT_IDLE_MS = 20_000L
        private const val LOCATION_RESUBSCRIBE_MS = 60_000L
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
