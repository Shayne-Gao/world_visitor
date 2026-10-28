package io.shayne.fogvisitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.shayne.fogvisitor.storage.FogVisitorDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class NativeArchiveP0Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun bridge() = JsBridge(context, {}, {}, {}, {}, {}, {})
    private fun track(id: String) = JSONObject()
        .put("id", id).put("timestamp", 1700000000000L)
        .put("source", "web_draw").put("brushRadiusKm", 0.02)
        .put("encodedPath", PolylineCodec.encode(listOf(listOf(103.81, 1.34), listOf(103.811, 1.341))))

    @Test fun workerSourceContainsBothPackagedAssetsInOrder() {
        val source = bridge().getGeometryWorkerSource()
        val expected = listOf("web/vendor/turf/turf.min.js", "web/geometry-worker.js")
            .map { path -> context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
        assertEquals(expected.joinToString("\n;\n"), source)
        assertTrue(source.contains("self.onmessage"))
        assertTrue(source.length > 600000)
        assertEquals(source, bridge().getGeometryWorkerSource())
    }

    @After fun closeDatabase() {
        val instance = NativeTrackStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.get(null)?.let { store ->
            val queue = NativeTrackStore::class.java.getDeclaredField("storeExecutor").apply { isAccessible = true }.get(store) as ExecutorService
            queue.shutdown()
            assertTrue(queue.awaitTermination(10, TimeUnit.SECONDS))
        }
        instance.set(null, null)
        val db = FogVisitorDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        (db.get(null) as? FogVisitorDatabase)?.close()
        db.set(null, null)
    }

    @Test fun manualMarkSurvivesDatabaseCloseAndReopen() {
        val first = bridge()
        assertTrue(JSONObject(first.appendNativeTrackJson(track("manual").toString())).getBoolean("persisted"))
        val expected = JSONObject(first.getNativeArchiveSummary())
        closeDatabase()
        val next = bridge()
        assertEquals("manual", JSONArray(next.getNativeArchiveTracks()).getJSONObject(0).getString("id"))
        val actual = JSONObject(next.getNativeArchiveSummary())
        assertEquals(expected.getString("epoch"), actual.getString("epoch"))
        assertEquals(expected.getLong("revision"), actual.getLong("revision"))
        assertFalse(JSONObject(next.getNativeTrackingStatus()).has("lastLng"))
    }

    @Test fun exportImportRoundTripPreservesManualAndRecordedCoordinates() {
        val store = NativeTrackStore.getInstance(context)
        bridge().appendNativeTrackJson(track("manual").toString())
        store.appendDraftPoint(104.0, 2.0, 5.0, 1700000000000L)
        store.appendDraftPoint(104.001, 2.001, 5.0, 1700000010000L)
        assertNotNull(store.checkpointDraftToTrackIfNeeded(minPointCount = 2))
        val archive = bridge().exportNativeArchiveJson()
        val expected = JSONObject(archive).getJSONObject("sourceOfTruth").getJSONArray("tracks").toString()
        store.clearArchive()
        bridge().importNativeArchiveJson(archive, false)
        closeDatabase()
        assertEquals(expected, JSONObject(bridge().exportNativeArchiveJson())
            .getJSONObject("sourceOfTruth").getJSONArray("tracks").toString())
    }

    @Test fun upgradesVersionOneDatabaseThenPersistsNewMarks() {
        val file = context.getDatabasePath("fog_visitor_truth.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE track_segments (id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, source TEXT NOT NULL, brush_radius_km REAL NOT NULL, encoded_path TEXT NOT NULL, bbox_json TEXT)")
            db.execSQL("CREATE TABLE draft_points (sequence INTEGER NOT NULL PRIMARY KEY, lng REAL NOT NULL, lat REAL NOT NULL)")
            db.execSQL("INSERT INTO track_segments VALUES ('old', 1700000000000, 'web_draw', 0.02, ?, NULL)",
                arrayOf(track("old").getString("encodedPath")))
            db.execSQL("INSERT INTO draft_points VALUES (0, 104.0, 2.0)")
            db.version = 1
        }
        val api = bridge()
        assertEquals(1, JSONObject(api.getNativeArchiveSummary()).getInt("trackCount"))
        assertEquals(1, JSONObject(api.getNativeTrackingStatus()).getJSONArray("draftPoints").length())
        api.appendNativeTrackJson(track("new").toString())
        closeDatabase()
        assertEquals(2, JSONObject(bridge().getNativeArchiveSummary()).getInt("trackCount"))
    }
}
