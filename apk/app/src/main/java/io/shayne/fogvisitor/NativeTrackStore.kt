package io.shayne.fogvisitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NativeTrackStore(private val context: Context) {

    private val archiveFile = File(context.filesDir, "fog_apk_archive.json")
    private val draftFile = File(context.filesDir, "fog_apk_draft.json")
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun appendDraftPoint(lng: Double, lat: Double) {
        val points = readDraftPoints().toMutableList()
        points.add(listOf(lng, lat))
        writeDraftPoints(points)
        updateStatus(
            isTracking = true,
            draftPointCount = points.size,
            lastPointAt = System.currentTimeMillis()
        )
    }

    fun finalizeDraftToTrack(
        source: String = "android_background_track",
        brushRadiusKm: Double = 0.02
    ): TrackRecord? {
        val points = readDraftPoints()
        if (points.isEmpty()) return null

        val track = TrackRecord(
            id = "trk_${System.currentTimeMillis()}_${(100..999).random()}",
            timestamp = System.currentTimeMillis(),
            source = source,
            brushRadiusKm = brushRadiusKm,
            encodedPath = PolylineCodec.encode(points),
            bbox = PolylineCodec.calculateBbox(points)
        )

        val archive = readArchiveTracks().toMutableList()
        archive.add(track)
        writeArchiveTracks(archive)
        clearDraft()
        updateStatus(isTracking = false, draftPointCount = 0, lastPointAt = System.currentTimeMillis())
        return track
    }

    fun readArchiveTracks(): List<TrackRecord> {
        if (!archiveFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(archiveFile.readText())
            val tracks = root.getJSONObject("sourceOfTruth").getJSONArray("tracks")
            (0 until tracks.length()).map { index -> trackFromJson(tracks.getJSONObject(index)) }
        }.getOrElse { emptyList() }
    }

    fun readDraftPoints(): List<List<Double>> {
        if (!draftFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(draftFile.readText())
            (0 until array.length()).map { idx ->
                val point = array.getJSONArray(idx)
                listOf(point.getDouble(0), point.getDouble(1))
            }
        }.getOrElse { emptyList() }
    }

    fun clearDraft() {
        if (draftFile.exists()) draftFile.delete()
    }

    fun exportArchiveJson(): String {
        if (archiveFile.exists()) return archiveFile.readText()
        writeArchiveTracks(emptyList())
        return archiveFile.readText()
    }

    fun getStatusJson(): String {
        val json = JSONObject().apply {
            put("isTracking", prefs.getBoolean(KEY_IS_TRACKING, false))
            put("draftPointCount", prefs.getInt(KEY_DRAFT_COUNT, 0))
            put("lastPointAt", prefs.getLong(KEY_LAST_POINT_AT, 0L))
            put("trackCount", readArchiveTracks().size)
            put("archivePath", archiveFile.absolutePath)
            put("draftPath", draftFile.absolutePath)
        }
        return json.toString()
    }

    fun markTrackingRunning(isRunning: Boolean) {
        updateStatus(isTracking = isRunning, draftPointCount = readDraftPoints().size)
    }

    private fun writeArchiveTracks(tracks: List<TrackRecord>) {
        val root = JSONObject().apply {
            put("version", "2.0.0")
            put("metadata", JSONObject().apply {
                put("totalAreaKm2", 0.0)
                put("createdAt", prefs.getLong(KEY_CREATED_AT, System.currentTimeMillis()).also {
                    prefs.edit().putLong(KEY_CREATED_AT, it).apply()
                })
            })
            put("sourceOfTruth", JSONObject().apply {
                put("tracks", JSONArray().apply {
                    tracks.forEach { put(trackToJson(it)) }
                })
            })
        }
        archiveFile.writeText(root.toString())
    }

    private fun writeDraftPoints(points: List<List<Double>>) {
        val array = JSONArray().apply {
            points.forEach { point ->
                put(JSONArray().apply {
                    put(point[0])
                    put(point[1])
                })
            }
        }
        draftFile.writeText(array.toString())
    }

    private fun updateStatus(isTracking: Boolean, draftPointCount: Int, lastPointAt: Long? = null) {
        prefs.edit().apply {
            putBoolean(KEY_IS_TRACKING, isTracking)
            putInt(KEY_DRAFT_COUNT, draftPointCount)
            if (lastPointAt != null) putLong(KEY_LAST_POINT_AT, lastPointAt)
        }.apply()
    }

    private fun trackToJson(track: TrackRecord): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("timestamp", track.timestamp)
        put("source", track.source)
        put("brushRadiusKm", track.brushRadiusKm)
        put("encodedPath", track.encodedPath)
        put("bbox", track.bbox?.let { bbox ->
            JSONArray().apply { bbox.forEach { put(it) } }
        })
    }

    private fun trackFromJson(json: JSONObject): TrackRecord {
        val bboxArray = json.optJSONArray("bbox")
        val bbox = if (bboxArray != null) {
            (0 until bboxArray.length()).map { index -> bboxArray.getDouble(index) }
        } else {
            null
        }

        return TrackRecord(
            id = json.getString("id"),
            timestamp = json.getLong("timestamp"),
            source = json.optString("source", "android_background_track"),
            brushRadiusKm = json.optDouble("brushRadiusKm", 0.02),
            encodedPath = json.getString("encodedPath"),
            bbox = bbox
        )
    }

    companion object {
        private const val PREF_NAME = "fog_visitor_native_tracking"
        private const val KEY_IS_TRACKING = "is_tracking"
        private const val KEY_DRAFT_COUNT = "draft_count"
        private const val KEY_LAST_POINT_AT = "last_point_at"
        private const val KEY_CREATED_AT = "archive_created_at"
    }
}
