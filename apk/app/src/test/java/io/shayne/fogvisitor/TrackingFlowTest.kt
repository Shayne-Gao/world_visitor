package io.shayne.fogvisitor

import android.Manifest
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.Tasks
import io.shayne.fogvisitor.storage.FogVisitorDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.*
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.ServerSocket
import java.net.InetAddress
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class TrackingFlowTest {
    private lateinit var store: NativeTrackStore
    private lateinit var controller: ServiceController<TrackingForegroundService>
    private lateinit var service: TrackingForegroundService
    private lateinit var locations: MockedStatic<LocationServices>
    private lateinit var client: FusedLocationProviderClient
    private lateinit var callback: LocationCallback
    private lateinit var callbackLooper: Looper
    private val startAt = System.currentTimeMillis()

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        store = NativeTrackStore.getInstance(app)
        client = mock(FusedLocationProviderClient::class.java)
        `when`(client.lastLocation).thenReturn(Tasks.forResult(null))
        `when`(client.getCurrentLocation(anyInt(), any(CancellationToken::class.java))).thenReturn(Tasks.forResult(null))
        `when`(client.removeLocationUpdates(any(LocationCallback::class.java))).thenReturn(Tasks.forResult(null))
        doAnswer { invocation ->
            callback = invocation.getArgument(1)
            callbackLooper = invocation.getArgument(2)
            Tasks.forResult<Void>(null)
        }.`when`(client).requestLocationUpdates(any(LocationRequest::class.java), any(LocationCallback::class.java), any(Looper::class.java))
        locations = mockStatic(LocationServices::class.java)
        locations.`when`<FusedLocationProviderClient> { LocationServices.getFusedLocationProviderClient(any(android.content.Context::class.java)) }.thenReturn(client)
        controller = Robolectric.buildService(TrackingForegroundService::class.java).create()
        service = controller.get()
        service.onStartCommand(Intent().setAction(TrackingForegroundService.ACTION_START), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        drainWriter()
    }

    @After fun tearDown() {
        if (::controller.isInitialized) {
            service.onStartCommand(Intent().setAction(TrackingForegroundService.ACTION_STOP), 0, 99)
            shadowOf(Looper.getMainLooper()).idle()
            drainWriter()
            shadowOf(Looper.getMainLooper()).idle()
            controller.destroy()
        }
        if (::locations.isInitialized) locations.close()
        if (::store.isInitialized) {
            val executor = NativeTrackStore::class.java.getDeclaredField("storeExecutor").apply { isAccessible = true }.get(store) as java.util.concurrent.ExecutorService
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        val dbField = FogVisitorDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (dbField.get(null) as? FogVisitorDatabase)?.close()
        dbField.set(null, null)
        NativeTrackStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun point(meters: Double, seconds: Long, accuracyMeters: Float = 5f) = Location("replay").apply {
        latitude = 1.34
        longitude = 103.81 + meters / 111_290.0
        accuracy = accuracyMeters
        time = startAt + seconds * 1000
        elapsedRealtimeNanos = (seconds + 1) * 1_000_000_000
    }

    private fun drainWriter(action: () -> Unit = {}) {
        val done = CountDownLatch(1)
        Handler(callbackLooper).post { try { action() } finally { done.countDown() } }
        assertTrue("location writer must not deadlock", done.await(10, TimeUnit.SECONDS))
    }

    private fun deliver(vararg samples: Location, target: LocationCallback = callback) {
        drainWriter { target.onLocationResult(LocationResult.create(samples.toList())) }
    }

    private fun evidence(name: String, hypothesis: String) {
        val endpoint = System.getenv("FOG_DEBUG_URL") ?: return
        val payload = JSONObject().put("sessionId", "tracking-zoom")
            .put("runId", System.getenv("DEBUG_RUN_ID") ?: "pre-fix")
            .put("hypothesisId", hypothesis).put("msg", "[DEBUG] $name")
            .put("data", JSONObject(store.getStatusJson()).put("savedTracks", store.trackCount()))
        payload.getJSONObject("data").remove("debugEvents")
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 2000
            readTimeout = 2000
            outputStream.use { it.write(payload.toString().toByteArray()) }
            inputStream.close()
            disconnect()
        }
    }

    @Test fun recordsBatchesWithoutMainLooperProgress() {
        // The UI/main looper stays paused while the actual location writer and Room run.
        deliver(point(0.0, 0), point(20.0, 10), point(40.0, 20), point(60.0, 30))
        evidence("batch-with-paused-ui", "A")
        assertTrue(store.trackCount() > 0)
        val coords = PolylineCodec.decode(store.readArchiveTracks().last().encodedPath)
        assertEquals(4, coords.size)
        assertEquals(point(60.0, 30).longitude, coords.last()[0], 0.00001)
    }

    @Test fun pageBridgeRecordingSurvivesReopening() {
        fun command(action: String) {
            val done = CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            Handler(Looper.getMainLooper()).post {
                try {
                    service.onStartCommand(Intent().setAction(action), 0, 100)
                } catch (error: Throwable) { failure.set(error) }
                finally { done.countDown() }
            }
            check(done.await(10, TimeUnit.SECONDS)) { "Main looper did not process tracking action" }
            failure.get()?.let { throw it }
        }
        val bridge = JsBridge(RuntimeEnvironment.getApplication(),
            { command(TrackingForegroundService.ACTION_START) },
            { command(TrackingForegroundService.ACTION_STOP) },
            { command(TrackingForegroundService.ACTION_STOP) }, {}, {}, {})
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val serverThread = Thread {
          while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: java.net.SocketException) { break }
            socket.use {
              val input = socket.getInputStream().buffered()
              fun line(): String {
                  val text = StringBuilder()
                  while (true) {
                      val b = input.read()
                      if (b == -1 || b == 10) return text.toString().trimEnd('\r')
                      text.append(b.toChar())
                  }
              }
              val request = line().split(" ")
              var length = 0
              while (true) {
                  val header = line()
                  if (header.isEmpty()) break
                  if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
              }
              val body = input.readNBytes(length).toString(Charsets.UTF_8)
              var status = 200
              val response = try {
                val args = org.json.JSONArray(body.ifEmpty { "[]" })
                val name = request[1].removePrefix("/")
                val result = when (name) {
                    "openActivity" -> {
                        command(TrackingForegroundService.ACTION_START)
                        bridge.getNativeArchiveSummary()
                    }
                    "deliver" -> {
                        val base = args.getDouble(0)
                        val at = args.getLong(1)
                        deliver(point(base, at), point(base + 20, at + 10), point(base + 40, at + 20))
                        bridge.getNativeArchiveSummary()
                    }
                    else -> {
                        val method = JsBridge::class.java.methods.single { it.name == name && it.parameterCount == args.length() }
                        val values = method.parameterTypes.mapIndexed { i, type ->
                            when (type) {
                                java.lang.Long.TYPE -> args.getLong(i)
                                java.lang.Double.TYPE -> args.getDouble(i)
                                java.lang.Boolean.TYPE -> args.getBoolean(i)
                                else -> args.getString(i)
                            }
                        }.toTypedArray()
                        method.invoke(bridge, *values)?.toString() ?: ""
                    }
                }
                result
              } catch (error: Throwable) {
                status = 500
                error.printStackTrace()
                (error.cause ?: error).toString()
              }
              val bytes = response.toByteArray()
              socket.getOutputStream().use { output ->
                  output.write("HTTP/1.1 $status OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                  output.write(bytes)
              }
            }
          }
        }
        serverThread.isDaemon = true
        serverThread.start()
        try {
            val apkRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "tests/p0-app-flow.cjs").exists() }
            val process = ProcessBuilder("node", File(apkRoot, "tests/p0-app-flow.cjs").absolutePath)
                .directory(apkRoot.parentFile)
                .inheritIO()
                .apply { environment()["P0_BRIDGE_URL"] = "http://127.0.0.1:${server.localPort}" }
                .start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
            while (process.isAlive && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                process.waitFor(50, TimeUnit.MILLISECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly()
                fail("P0 page flow exceeded 180 seconds")
            }
            assertEquals("P0 page, native bridge, Room and cold-page restart must pass", 0, process.exitValue())
        } finally {
            server.close()
            serverThread.join(5000)
        }
    }

    @Test fun accumulatesNineMeterWalkingSteps() {
        for (i in 0..12) deliver(point(i * 9.0, i * 10L))
        evidence("nine-meter-walking", "B")
        assertTrue("108 meters of accepted walking must become a saved track", store.trackCount() > 0)
        val coords = PolylineCodec.decode(store.readArchiveTracks().last().encodedPath)
        assertEquals(point(0.0, 0).longitude, coords.first()[0], 0.00001)
        assertEquals(point(108.0, 120).longitude, coords.last()[0], 0.00001)
        assertEquals(5.0, JSONObject(store.getStatusJson()).getDouble("lastAccuracy"), 0.0)
    }

    @Test fun boundsStationaryJitterWithoutDiscardingTheAnchor() {
        for (i in 0..50) deliver(point(if (i % 2 == 0) 0.0 else 9.0, i * 10L))
        assertEquals(0, store.trackCount())
        assertTrue(store.readDraftPoints().size <= 24)
        assertEquals(point(0.0, 0).longitude, store.readDraftPoints().first()[0], 0.000001)
        deliver(point(27.0, 510))
        assertEquals(1, store.trackCount())
    }

    @Test fun recreatesServiceAndContinuesPersistedDraft() {
        deliver(point(0.0, 0), point(9.0, 10))
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(store.shouldTrack())
        controller = Robolectric.buildService(TrackingForegroundService::class.java).create()
        service = controller.get()
        service.onStartCommand(null, 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        drainWriter()
        deliver(point(18.0, 20))
        evidence("service-recreated-draft", "A")
        assertEquals(1, store.trackCount())
        assertEquals(3, PolylineCodec.decode(store.readArchiveTracks().last().encodedPath).size)
    }

    @Test fun continuesAfterActivityBackgroundAndScreenOff() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            deliver(point(0.0, 0), point(20.0, 10))
            activity.pause().stop()
            val power = RuntimeEnvironment.getApplication().getSystemService(android.os.PowerManager::class.java)
            shadowOf(power).setIsInteractive(false)
            shadowOf(power).setIsDeviceIdleMode(true)
            deliver(point(40.0, 20), point(60.0, 30))
            evidence("activity-background-screen-off", "A")
            assertEquals(4, PolylineCodec.decode(store.readArchiveTracks().last().encodedPath).size)
            assertTrue(store.shouldTrack())
        } finally {
            activity.destroy()
        }
    }

    @Test fun watchdogRetriesFailedSubscription() {
        var attempts = 0
        doAnswer { invocation ->
            attempts++
            if (attempts == 1) Tasks.forException<Void>(IllegalStateException("simulated subscription failure"))
            else {
                callback = invocation.getArgument(1)
                callbackLooper = invocation.getArgument(2)
                Tasks.forResult<Void>(null)
            }
        }.`when`(client).requestLocationUpdates(any(LocationRequest::class.java), any(LocationCallback::class.java), any(Looper::class.java))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
        assertEquals(1, attempts)
        assertFalse(JSONObject(store.getStatusJson()).getBoolean("isTracking"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(61))
        assertEquals(2, attempts)
        deliver(point(0.0, 0), point(20.0, 10))
        evidence("subscription-failure-recovered", "A")
        assertEquals(1, store.trackCount())
    }

    @Test fun sortsBatchAndRejectsDuplicatePoorAccuracyAndSpeedJump() {
        deliver(point(40.0, 20), point(0.0, 0), point(20.0, 10))
        deliver(point(40.0, 20), point(60.0, 30, 100f), point(5000.0, 21))
        evidence("batch-filtering", "B")
        assertEquals(3, PolylineCodec.decode(store.readArchiveTracks().last().encodedPath).size)
    }

    @Test fun splitsLongGapsWithoutDrawingAConnectingLine() {
        deliver(point(0.0, 0), point(20.0, 10))
        deliver(point(3000.0, 200), point(3020.0, 210))
        evidence("gap-split", "B")
        assertEquals(2, store.trackCount())
        store.readArchiveTracks().forEach {
            assertEquals(2, PolylineCodec.decode(it.encodedPath).size)
        }
    }

    @Test fun ignoresLateCallbacksAfterStopAndRestartsOnlyOnStart() {
        deliver(point(0.0, 0), point(20.0, 10))
        val old = callback
        service.onStartCommand(Intent().setAction(TrackingForegroundService.ACTION_STOP), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        drainWriter()
        shadowOf(Looper.getMainLooper()).idle()
        val revision = JSONObject(store.getArchiveSummaryJson()).getLong("revision")
        deliver(point(40.0, 20), target = old)
        evidence("stopped-late-callback", "C")
        assertFalse(store.shouldTrack())
        assertEquals(revision, JSONObject(store.getArchiveSummaryJson()).getLong("revision"))
        service.onStartCommand(Intent().setAction(TrackingForegroundService.ACTION_START), 0, 3)
        shadowOf(Looper.getMainLooper()).idle()
        deliver(point(60.0, 30), target = old)
        assertEquals(revision, JSONObject(store.getArchiveSummaryJson()).getLong("revision"))
        deliver(point(40.0, 20), point(60.0, 30))
        assertTrue(store.shouldTrack())
        assertTrue(JSONObject(store.getArchiveSummaryJson()).getLong("revision") > revision)
    }
}
