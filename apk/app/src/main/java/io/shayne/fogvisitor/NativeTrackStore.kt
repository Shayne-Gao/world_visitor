package io.shayne.fogvisitor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream
import java.util.Date
import java.util.Locale

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
            shouldTrack = true,
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
        updateStatus(
            isTracking = false,
            shouldTrack = false,
            draftPointCount = 0,
            lastPointAt = System.currentTimeMillis()
        )
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

    fun exportArchiveToDownloads(): String {
        val json = exportArchiveJson()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "fog_apk_export_$timestamp.json"

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建导出文件")

        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray())
        } ?: throw IllegalStateException("无法写入导出文件")

        return uri.toString()
    }

    fun importArchiveJson(rawJson: String, merge: Boolean): String {
        val parsed = JSONObject(rawJson)
        return importParsedArchive(parsed, merge)
    }

    fun importArchiveUri(uri: Uri, merge: Boolean): String {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取导入文件")
        val fileName = resolveDisplayName(uri) ?: ""
        val jsonStr = if (
            fileName.endsWith(".fogbak", ignoreCase = true) ||
            fileName.endsWith(".gz", ignoreCase = true) ||
            isGzip(rawBytes)
        ) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).bufferedReader().use { it.readText() }
        } else {
            rawBytes.toString(Charsets.UTF_8)
        }
        val parsed = JSONObject(jsonStr)
        return importParsedArchive(parsed, merge)
    }

    private fun importParsedArchive(parsed: JSONObject, merge: Boolean): String {
        val importedTracks = when {
            parsed.optString("version") == "2.0.0" || parsed.optString("version") == "1.0.0" -> {
                val tracks = parsed.getJSONObject("sourceOfTruth").getJSONArray("tracks")
                (0 until tracks.length()).map { index -> trackFromJson(tracks.getJSONObject(index)) }
            }
            parsed.has("fog") -> {
                throw IllegalArgumentException("当前 APK 原型阶段暂不支持仅 fog 渲染缓存格式，请先使用带 sourceOfTruth.tracks 的新格式存档。")
            }
            else -> throw IllegalArgumentException("无法识别的存档结构")
        }

        val finalTracks = if (merge) {
            val map = linkedMapOf<String, TrackRecord>()
            readArchiveTracks().forEach { track ->
                map[track.id] = track
            }
            importedTracks.forEach { track ->
                map.putIfAbsent(track.id, track)
            }
            map.values.sortedBy { it.timestamp }
        } else {
            importedTracks.sortedBy { it.timestamp }
        }

        writeArchiveTracks(finalTracks)
        return JSONObject().apply {
            put("mode", if (merge) "merge" else "replace")
            put("trackCount", finalTracks.size)
        }.toString()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    private fun isGzip(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        return bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    }

    fun getStatusJson(): String {
        val json = JSONObject().apply {
            put("isTracking", prefs.getBoolean(KEY_IS_TRACKING, false))
            put("shouldTrack", prefs.getBoolean(KEY_SHOULD_TRACK, false))
            put("draftPointCount", prefs.getInt(KEY_DRAFT_COUNT, 0))
            put("lastPointAt", prefs.getLong(KEY_LAST_POINT_AT, 0L))
            put("trackCount", readArchiveTracks().size)
            put("archivePath", archiveFile.absolutePath)
            put("draftPath", draftFile.absolutePath)
            put("hasRecoverableDraft", hasRecoverableDraft())
        }
        return json.toString()
    }

    fun getArchiveSummaryJson(): String {
        val tracks = readArchiveTracks()
        val latestTimestamp = tracks.maxOfOrNull { it.timestamp } ?: 0L
        return JSONObject().apply {
            put("trackCount", tracks.size)
            put("latestTimestamp", latestTimestamp)
            put("archiveExists", archiveFile.exists())
            put("draftExists", draftFile.exists())
            put("hasRecoverableDraft", hasRecoverableDraft())
            put("shouldTrack", shouldTrack())
        }.toString()
    }

    fun getArchiveTracksJson(): String {
        val tracks = readArchiveTracks()
        return JSONArray().apply {
            tracks.sortedByDescending { it.timestamp }.forEach { track ->
                put(trackToJson(track))
            }
        }.toString()
    }

    fun markTrackingRunning(isRunning: Boolean, shouldTrack: Boolean = prefs.getBoolean(KEY_SHOULD_TRACK, false)) {
        updateStatus(
            isTracking = isRunning,
            shouldTrack = shouldTrack,
            draftPointCount = readDraftPoints().size
        )
    }

    fun markTrackingRequested(shouldTrack: Boolean) {
        updateStatus(
            isTracking = if (shouldTrack) prefs.getBoolean(KEY_IS_TRACKING, false) else false,
            shouldTrack = shouldTrack,
            draftPointCount = readDraftPoints().size
        )
    }

    fun hasRecoverableDraft(): Boolean = readDraftPoints().isNotEmpty()

    fun shouldTrack(): Boolean = prefs.getBoolean(KEY_SHOULD_TRACK, false)

    fun recoverDraftAsTrack(): TrackRecord? = finalizeDraftToTrack(source = "android_recovered_track")

    fun latestTrackTimestamp(): Long = readArchiveTracks().maxOfOrNull { it.timestamp } ?: 0L

    fun hasArchive(): Boolean = archiveFile.exists()

    fun getRecoveryStatusJson(): String {
        return JSONObject().apply {
            put("shouldTrack", shouldTrack())
            put("hasRecoverableDraft", hasRecoverableDraft())
            put("draftPointCount", readDraftPoints().size)
            put("latestTrackTimestamp", latestTrackTimestamp())
        }.toString()
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

    private fun updateStatus(
        isTracking: Boolean,
        shouldTrack: Boolean,
        draftPointCount: Int,
        lastPointAt: Long? = null
    ) {
        prefs.edit().apply {
            putBoolean(KEY_IS_TRACKING, isTracking)
            putBoolean(KEY_SHOULD_TRACK, shouldTrack)
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

        val encodedPath = json.optString("encodedPath", "")
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
        val fallbackId = "trk_${timestamp}_${encodedPath.length}"

        return TrackRecord(
            id = json.optString("id", fallbackId).ifBlank { fallbackId },
            timestamp = timestamp,
            source = json.optString("source", "android_background_track"),
            brushRadiusKm = json.optDouble("brushRadiusKm", 0.02),
            encodedPath = encodedPath,
            bbox = bbox
        )
    }

    companion object {
        private const val PREF_NAME = "fog_visitor_native_tracking"
        private const val KEY_IS_TRACKING = "is_tracking"
        private const val KEY_SHOULD_TRACK = "should_track"
        private const val KEY_DRAFT_COUNT = "draft_count"
        private const val KEY_LAST_POINT_AT = "last_point_at"
        private const val KEY_CREATED_AT = "archive_created_at"
    }
}
