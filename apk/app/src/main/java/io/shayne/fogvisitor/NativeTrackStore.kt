package io.shayne.fogvisitor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.shayne.fogvisitor.storage.DraftPointEntity
import io.shayne.fogvisitor.storage.FogVisitorDatabase
import io.shayne.fogvisitor.storage.TrackDao
import io.shayne.fogvisitor.storage.TrackSegmentEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

class NativeTrackStore(private val context: Context) {

    private val legacyArchiveFile = File(context.filesDir, "fog_apk_archive.json")
    private val legacyArchiveBackupFile = File(context.filesDir, "fog_apk_archive.backup.json")
    private val legacyDraftFile = File(context.filesDir, "fog_apk_draft.json")
    private val legacyDraftBackupFile = File(context.filesDir, "fog_apk_draft.backup.json")
    private val exploredCellsFile = File(context.filesDir, "fog_apk_explored_cells_v1.txt")
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val dao: TrackDao = FogVisitorDatabase.getInstance(context).trackDao()
    private val storeThreadRef = AtomicReference<Thread?>(null)
    private val storeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fog-visitor-store").also { storeThreadRef.set(it) }
    }
    private val exploredCells = linkedSetOf<String>()
    private var exploredCellsLoaded = false

    init {
        migrateLegacyJsonIfNeeded()
    }

    fun appendDraftPoint(lng: Double, lat: Double, accuracy: Double? = null) {
        runStore {
            val points = readDraftPoints().toMutableList()
            points.add(listOf(lng, lat))
            writeDraftPoints(points)
            updateStatus(
                isTracking = true,
                shouldTrack = true,
                draftPointCount = points.size,
                lastPointAt = System.currentTimeMillis(),
                lastLng = lng,
                lastLat = lat,
                lastAccuracy = accuracy
            )
        }
    }

    fun checkpointDraftToTrackIfNeeded(
        source: String = "android_background_track_segment",
        brushRadiusKm: Double = 0.02,
        minPointCount: Int = 12
    ): TrackRecord? {
        return runStore {
            val points = readDraftPoints()
            if (points.size < minPointCount) return@runStore null
            if (shouldDiscardCheckpoint(points)) {
                val seed = points.lastOrNull()?.let { listOf(it) } ?: emptyList()
                val lastPoint = seed.lastOrNull()
                writeDraftPoints(seed)
                updateStatus(
                    isTracking = true,
                    shouldTrack = true,
                    draftPointCount = seed.size,
                    lastPointAt = System.currentTimeMillis(),
                    lastLng = lastPoint?.getOrNull(0),
                    lastLat = lastPoint?.getOrNull(1)
                )
                return@runStore null
            }

            val track = TrackRecord(
                id = "trk_${System.currentTimeMillis()}_${(100..999).random()}",
                timestamp = System.currentTimeMillis(),
                source = source,
                brushRadiusKm = brushRadiusKm,
                encodedPath = PolylineCodec.encode(points),
                bbox = PolylineCodec.calculateBbox(points)
            )
            val persistResult = persistTrackCandidate(track)

            val seed = points.lastOrNull()?.let { listOf(it) } ?: emptyList()
            val lastPoint = points.lastOrNull()
            writeDraftPoints(seed)
            updateStatus(
                isTracking = true,
                shouldTrack = true,
                draftPointCount = seed.size,
                lastPointAt = System.currentTimeMillis(),
                lastLng = lastPoint?.getOrNull(0),
                lastLat = lastPoint?.getOrNull(1)
            )
            persistResult.track
        }
    }

    fun finalizeDraftToTrack(
        source: String = "android_background_track",
        brushRadiusKm: Double = 0.02
    ): TrackRecord? {
        return runStore {
            val points = readDraftPoints()
            if (points.isEmpty()) return@runStore null

            val track = TrackRecord(
                id = "trk_${System.currentTimeMillis()}_${(100..999).random()}",
                timestamp = System.currentTimeMillis(),
                source = source,
                brushRadiusKm = brushRadiusKm,
                encodedPath = PolylineCodec.encode(points),
                bbox = PolylineCodec.calculateBbox(points)
            )
            val persistResult = persistTrackCandidate(track)
            val lastPoint = points.lastOrNull()
            clearDraft()
            updateStatus(
                isTracking = false,
                shouldTrack = false,
                draftPointCount = 0,
                lastPointAt = System.currentTimeMillis(),
                lastLng = lastPoint?.getOrNull(0),
                lastLat = lastPoint?.getOrNull(1)
            )
            persistResult.track
        }
    }

    fun readArchiveTracks(): List<TrackRecord> {
        return runStore { dao.getAllTracks().map { it.toModel() } }
    }

    fun readDraftPoints(): List<List<Double>> {
        return runStore { dao.getDraftPoints().map { listOf(it.lng, it.lat) } }
    }

    fun clearDraft() {
        runStore {
            dao.clearDraftPoints()
        }
    }

    fun exportArchiveJson(): String {
        return runStore {
            val tracks = readArchiveTracks()
            buildArchiveJson(tracks).toString()
        }
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

    fun buildExportFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "fog_apk_export_$timestamp.json"
    }

    fun exportArchiveToUri(uri: Uri): String {
        val json = exportArchiveJson()
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray())
        } ?: throw IllegalStateException("无法写入导出文件")
        return uri.toString()
    }

    fun importArchiveJson(rawJson: String, merge: Boolean): String {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_UNCOMPRESSED_BYTES) {
            throw IllegalArgumentException("导入文件过大，请先拆分后再导入。")
        }
        val parsed = JSONObject(rawJson)
        return importParsedArchive(parsed, merge)
    }

    fun appendTrackJson(rawJson: String): String {
        return runStore {
            val track = trackFromJson(JSONObject(rawJson))
            val persistResult = persistTrackCandidate(track)
            val shouldUpdateLiveLocation = isLiveLocationSource(track.source)
            val lastPoint = if (shouldUpdateLiveLocation) extractLastPoint(track) else null
            updateStatus(
                isTracking = prefs.getBoolean(KEY_IS_TRACKING, false),
                shouldTrack = prefs.getBoolean(KEY_SHOULD_TRACK, false),
                draftPointCount = readDraftPoints().size,
                lastPointAt = if (shouldUpdateLiveLocation) track.timestamp else prefs.getLong(KEY_LAST_POINT_AT, 0L),
                lastLng = lastPoint?.getOrNull(0) ?: readOptionalDouble(KEY_LAST_LNG_BITS),
                lastLat = lastPoint?.getOrNull(1) ?: readOptionalDouble(KEY_LAST_LAT_BITS),
                lastAccuracy = if (shouldUpdateLiveLocation) null else readOptionalDouble(KEY_LAST_ACCURACY_BITS)
            )
            JSONObject().apply {
                put("ok", true)
                put("trackId", track.id)
                put("persisted", persistResult.persisted)
                put("reason", persistResult.reason)
                put("trackCount", persistResult.trackCount)
                put("latestTimestamp", track.timestamp)
            }.toString()
        }
    }

    fun replaceTracksJson(rawJson: String): String {
        return runStore {
            val parsed = JSONArray(rawJson)
            val tracks = (0 until parsed.length()).map { index ->
                trackFromJson(parsed.getJSONObject(index))
            }.sortedBy { it.timestamp }
            dao.replaceAllTracks(tracks.map { it.toEntity() })
            dao.clearDraftPoints()
            replaceExploredCells(tracks)
            val lastTrack = tracks.lastOrNull()
            val lastPoint = lastTrack?.let(::extractLastPoint)
            updateStatus(
                isTracking = false,
                shouldTrack = false,
                draftPointCount = 0,
                lastPointAt = lastTrack?.timestamp ?: 0L,
                lastLng = lastPoint?.getOrNull(0),
                lastLat = lastPoint?.getOrNull(1),
                lastAccuracy = null
            )
            JSONObject().apply {
                put("ok", true)
                put("trackCount", tracks.size)
                put("latestTimestamp", lastTrack?.timestamp ?: 0L)
            }.toString()
        }
    }

    fun importArchiveUri(uri: Uri, merge: Boolean): String {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取导入文件")
        if (rawBytes.size > MAX_IMPORT_BYTES) {
            throw IllegalArgumentException("导入文件过大，请先压缩或拆分后再导入。")
        }
        val fileName = resolveDisplayName(uri) ?: ""
        val jsonStr = if (
            fileName.endsWith(".fogbak", ignoreCase = true) ||
            fileName.endsWith(".gz", ignoreCase = true) ||
            isGzip(rawBytes)
        ) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { stream ->
                readLimitedUtf8(stream, MAX_IMPORT_UNCOMPRESSED_BYTES)
            }
        } else {
            if (rawBytes.size > MAX_IMPORT_UNCOMPRESSED_BYTES) {
                throw IllegalArgumentException("导入文件过大，请先拆分后再导入。")
            }
            rawBytes.toString(Charsets.UTF_8)
        }
        val parsed = JSONObject(jsonStr)
        return importParsedArchive(parsed, merge)
    }

    private fun importParsedArchive(parsed: JSONObject, merge: Boolean): String {
        return runStore {
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
            if (importedTracks.size > MAX_IMPORT_TRACK_COUNT) {
                throw IllegalArgumentException("导入轨迹数量过多，请拆分后再导入。")
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

            dao.replaceAllTracks(finalTracks.map { it.toEntity() })
            if (!merge) {
                dao.clearDraftPoints()
            }
            replaceExploredCells(finalTracks)
            val lastTrack = finalTracks.lastOrNull()
            val lastPoint = lastTrack?.let(::extractLastPoint)
            updateStatus(
                isTracking = false,
                shouldTrack = false,
                draftPointCount = if (merge) readDraftPoints().size else 0,
                lastPointAt = lastTrack?.timestamp ?: 0L,
                lastLng = lastPoint?.getOrNull(0),
                lastLat = lastPoint?.getOrNull(1),
                lastAccuracy = null
            )
            JSONObject().apply {
                put("ok", true)
                put("mode", if (merge) "merge" else "replace")
                put("trackCount", finalTracks.size)
                put("latestTimestamp", lastTrack?.timestamp ?: 0L)
            }.toString()
        }
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

    private fun readLimitedUtf8(inputStream: GZIPInputStream, maxBytes: Int): String {
        val buffer = ByteArray(8 * 1024)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            if (output.size() > maxBytes) {
                throw IllegalArgumentException("导入文件解压后过大，请先拆分后再导入。")
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun getStatusJson(): String {
        return runStore {
            val json = JSONObject().apply {
                put("isTracking", prefs.getBoolean(KEY_IS_TRACKING, false))
                put("shouldTrack", prefs.getBoolean(KEY_SHOULD_TRACK, false))
                put("draftPointCount", prefs.getInt(KEY_DRAFT_COUNT, 0))
                put("lastPointAt", prefs.getLong(KEY_LAST_POINT_AT, 0L))
                put("trackCount", dao.getTrackCount())
                put("archivePath", "room://fog_visitor_truth.db/track_segments")
                put("draftPath", "room://fog_visitor_truth.db/draft_points")
                put("hasRecoverableDraft", hasRecoverableDraft())
            }
            putOptionalDouble(json, "lastLng", KEY_LAST_LNG_BITS)
            putOptionalDouble(json, "lastLat", KEY_LAST_LAT_BITS)
            putOptionalDouble(json, "lastAccuracy", KEY_LAST_ACCURACY_BITS)
            json.toString()
        }
    }

    fun getArchiveSummaryJson(): String {
        return runStore {
            val latestTimestamp = dao.getLatestTimestamp() ?: 0L
            JSONObject().apply {
                put("trackCount", dao.getTrackCount())
                put("latestTimestamp", latestTimestamp)
                put("archiveExists", dao.getTrackCount() > 0)
                put("draftExists", readDraftPoints().isNotEmpty())
                put("hasRecoverableDraft", hasRecoverableDraft())
                put("shouldTrack", shouldTrack())
            }.toString()
        }
    }

    fun getArchiveTracksJson(): String {
        return runStore {
            val tracks = readArchiveTracks()
            JSONArray().apply {
                tracks.sortedByDescending { it.timestamp }.forEach { track ->
                    put(trackToJson(track))
                }
            }.toString()
        }
    }

    fun markTrackingRunning(isRunning: Boolean, shouldTrack: Boolean = prefs.getBoolean(KEY_SHOULD_TRACK, false)) {
        runStore {
            updateStatus(
                isTracking = isRunning,
                shouldTrack = shouldTrack,
                draftPointCount = readDraftPoints().size
            )
        }
    }

    fun markTrackingRequested(shouldTrack: Boolean) {
        runStore {
            updateStatus(
                isTracking = if (shouldTrack) prefs.getBoolean(KEY_IS_TRACKING, false) else false,
                shouldTrack = shouldTrack,
                draftPointCount = readDraftPoints().size
            )
        }
    }

    fun hasRecoverableDraft(): Boolean = runStore { readDraftPoints().isNotEmpty() }

    fun shouldTrack(): Boolean = runStore { prefs.getBoolean(KEY_SHOULD_TRACK, false) }

    fun recoverDraftAsTrack(): TrackRecord? = runStore { finalizeDraftToTrack(source = "android_recovered_track") }

    fun clearArchive() {
        runStore {
            dao.clearTracks()
            dao.clearDraftPoints()
            replaceExploredCells(emptyList())
            updateStatus(
                isTracking = false,
                shouldTrack = false,
                draftPointCount = 0,
                lastPointAt = 0L,
                lastLng = null,
                lastLat = null,
                lastAccuracy = null
            )
        }
    }

    fun latestTrackTimestamp(): Long = runStore { readArchiveTracks().maxOfOrNull { it.timestamp } ?: 0L }

    fun hasArchive(): Boolean = runStore { dao.getTrackCount() > 0 }

    fun getRecoveryStatusJson(): String {
        return runStore {
            JSONObject().apply {
                put("shouldTrack", shouldTrack())
                put("hasRecoverableDraft", hasRecoverableDraft())
                put("draftPointCount", readDraftPoints().size)
                put("latestTrackTimestamp", latestTrackTimestamp())
            }.toString()
        }
    }

    private fun writeArchiveTracks(tracks: List<TrackRecord>) {
        dao.replaceAllTracks(tracks.map { it.toEntity() })
        replaceExploredCells(tracks)
    }

    private fun writeDraftPoints(points: List<List<Double>>) {
        dao.replaceDraftPoints(points.mapIndexed { index, point ->
            DraftPointEntity(
                sequence = index,
                lng = point[0],
                lat = point[1]
            )
        })
    }

    private fun updateStatus(
        isTracking: Boolean,
        shouldTrack: Boolean,
        draftPointCount: Int,
        lastPointAt: Long? = null,
        lastLng: Double? = null,
        lastLat: Double? = null,
        lastAccuracy: Double? = null
    ) {
        prefs.edit().apply {
            putBoolean(KEY_IS_TRACKING, isTracking)
            putBoolean(KEY_SHOULD_TRACK, shouldTrack)
            putInt(KEY_DRAFT_COUNT, draftPointCount)
            if (lastPointAt != null) putLong(KEY_LAST_POINT_AT, lastPointAt)
            if (lastLng != null) putLong(KEY_LAST_LNG_BITS, lastLng.toBits())
            if (lastLat != null) putLong(KEY_LAST_LAT_BITS, lastLat.toBits())
            if (lastAccuracy != null) putLong(KEY_LAST_ACCURACY_BITS, lastAccuracy.toBits())
            if (lastLng == null) remove(KEY_LAST_LNG_BITS)
            if (lastLat == null) remove(KEY_LAST_LAT_BITS)
            if (lastAccuracy == null) remove(KEY_LAST_ACCURACY_BITS)
        }.apply()
    }

    private fun putOptionalDouble(json: JSONObject, key: String, prefKey: String) {
        val value = readOptionalDouble(prefKey) ?: return
        json.put(key, value)
    }

    private fun readOptionalDouble(prefKey: String): Double? {
        if (!prefs.contains(prefKey)) return null
        val value = Double.fromBits(prefs.getLong(prefKey, 0L))
        return if (value.isNaN()) null else value
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
        require(encodedPath.length <= MAX_ENCODED_PATH_LENGTH) { "轨迹路径过长，无法导入。" }

        val brushRadiusKm = json.optDouble("brushRadiusKm", 0.02)
        require(brushRadiusKm.isFinite() && brushRadiusKm in 0.001..5.0) {
            "轨迹半径超出允许范围。"
        }

        return TrackRecord(
            id = json.optString("id", fallbackId).ifBlank { fallbackId },
            timestamp = timestamp,
            source = json.optString("source", "android_background_track"),
            brushRadiusKm = brushRadiusKm,
            encodedPath = encodedPath,
            bbox = bbox
        )
    }

    private fun TrackRecord.toEntity(): TrackSegmentEntity {
        return TrackSegmentEntity(
            id = id,
            timestamp = timestamp,
            source = source,
            brushRadiusKm = brushRadiusKm,
            encodedPath = encodedPath,
            bboxJson = bbox?.let { JSONArray(it).toString() }
        )
    }

    private fun TrackSegmentEntity.toModel(): TrackRecord {
        val bbox = bboxJson?.let { raw ->
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getDouble(it) }
        }
        return TrackRecord(
            id = id,
            timestamp = timestamp,
            source = source,
            brushRadiusKm = brushRadiusKm,
            encodedPath = encodedPath,
            bbox = bbox
        )
    }

    private fun extractLastPoint(track: TrackRecord): List<Double>? {
        return runCatching {
            PolylineCodec.decode(track.encodedPath).lastOrNull()
        }.getOrNull()
    }

    private data class PersistTrackResult(
        val persisted: Boolean,
        val reason: String,
        val trackCount: Int,
        val track: TrackRecord?
    )

    private fun persistTrackCandidate(track: TrackRecord): PersistTrackResult {
        val candidateCells = collectTrackCells(track)
        val hasNewExploredCells = candidateCells.any { it !in getExploredCells() }
        if (!hasNewExploredCells) {
            return PersistTrackResult(
                persisted = false,
                reason = "already_explored",
                trackCount = dao.getTrackCount(),
                track = null
            )
        }
        val latestTrack = dao.getLatestTrack()?.toModel()
        val trackToPersist = if (latestTrack != null && shouldMergeIntoLatest(latestTrack, track)) {
            mergeTracks(latestTrack, track)
        } else {
            track
        }
        dao.upsertTrack(trackToPersist.toEntity())
        appendExploredCells(candidateCells)
        return PersistTrackResult(
            persisted = true,
            reason = if (trackToPersist.id == track.id) "persisted" else "merged_into_latest",
            trackCount = dao.getTrackCount(),
            track = trackToPersist
        )
    }

    private fun getExploredCells(): MutableSet<String> {
        if (!exploredCellsLoaded) {
            exploredCells.clear()
            if (exploredCellsFile.exists()) {
                exploredCellsFile.forEachLine { line ->
                    val cell = line.trim()
                    if (cell.isNotEmpty()) exploredCells.add(cell)
                }
            }
            if (exploredCells.isEmpty() && dao.getTrackCount() > 0) {
                replaceExploredCells(dao.getAllTracks().map { it.toModel() })
            }
            exploredCellsLoaded = true
        }
        return exploredCells
    }

    private fun appendExploredCells(cells: Set<String>) {
        if (cells.isEmpty()) return
        val known = getExploredCells()
        val newCells = cells.filter { known.add(it) }
        if (newCells.isEmpty()) return
        if (!exploredCellsFile.exists()) {
            exploredCellsFile.parentFile?.mkdirs()
            exploredCellsFile.createNewFile()
        }
        exploredCellsFile.appendText(newCells.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun replaceExploredCells(tracks: List<TrackRecord>) {
        val rebuilt = linkedSetOf<String>()
        tracks.forEach { rebuilt.addAll(collectTrackCells(it)) }
        exploredCells.clear()
        exploredCells.addAll(rebuilt)
        exploredCellsLoaded = true
        if (rebuilt.isEmpty()) {
            if (exploredCellsFile.exists()) exploredCellsFile.delete()
            return
        }
        exploredCellsFile.parentFile?.mkdirs()
        exploredCellsFile.writeText(rebuilt.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun collectTrackCells(track: TrackRecord): Set<String> {
        val coords = runCatching { PolylineCodec.decode(track.encodedPath) }.getOrElse { emptyList() }
        if (coords.isEmpty()) return emptySet()
        val sampled = mutableListOf<List<Double>>()
        sampled.add(coords.first())
        for (index in 1 until coords.size) {
            val previous = coords[index - 1]
            val current = coords[index]
            sampled.addAll(sampleSegment(previous, current))
        }
        val radiusMeters = (track.brushRadiusKm * 1000.0).coerceAtLeast(CELL_SIZE_METERS)
        val cellRadius = kotlin.math.ceil(radiusMeters / CELL_SIZE_METERS).toInt()
        val cells = linkedSetOf<String>()
        sampled.forEach { point ->
            val base = toCell(point[1], point[0])
            for (dx in -cellRadius..cellRadius) {
                for (dy in -cellRadius..cellRadius) {
                    cells.add("${base.first + dx}:${base.second + dy}")
                }
            }
        }
        return cells
    }

    private fun sampleSegment(start: List<Double>, end: List<Double>): List<List<Double>> {
        val distance = distanceMeters(start[1], start[0], end[1], end[0])
        if (distance <= CELL_SIZE_METERS) return listOf(end)
        val steps = kotlin.math.ceil(distance / CELL_SIZE_METERS).toInt()
        return (1..steps).map { step ->
            val t = step.toDouble() / steps.toDouble()
            listOf(
                start[0] + (end[0] - start[0]) * t,
                start[1] + (end[1] - start[1]) * t
            )
        }
    }

    private fun shouldMergeIntoLatest(previous: TrackRecord, current: TrackRecord): Boolean {
        if (!isMergeableAutoSource(previous.source) || !isMergeableAutoSource(current.source)) return false
        if (current.timestamp - previous.timestamp > MAX_MERGE_GAP_MS) return false
        if (previous.brushRadiusKm != current.brushRadiusKm) return false
        val previousLast = extractLastPoint(previous) ?: return false
        val currentFirst = runCatching { PolylineCodec.decode(current.encodedPath).firstOrNull() }.getOrNull() ?: return false
        val distance = distanceMeters(
            previousLast.getOrElse(1) { 0.0 },
            previousLast.getOrElse(0) { 0.0 },
            currentFirst.getOrElse(1) { 0.0 },
            currentFirst.getOrElse(0) { 0.0 }
        )
        return distance <= MAX_MERGE_DISTANCE_METERS
    }

    private fun mergeTracks(previous: TrackRecord, current: TrackRecord): TrackRecord {
        val previousPoints = runCatching { PolylineCodec.decode(previous.encodedPath) }.getOrElse { emptyList() }
        val currentPoints = runCatching { PolylineCodec.decode(current.encodedPath) }.getOrElse { emptyList() }
        if (previousPoints.isEmpty()) return current
        if (currentPoints.isEmpty()) return previous

        val mergedPoints = previousPoints.toMutableList()
        val currentStartIndex = if (samePoint(previousPoints.last(), currentPoints.first())) 1 else 0
        for (index in currentStartIndex until currentPoints.size) {
            mergedPoints.add(currentPoints[index])
        }

        return previous.copy(
            timestamp = current.timestamp,
            encodedPath = PolylineCodec.encode(mergedPoints),
            bbox = PolylineCodec.calculateBbox(mergedPoints)
        )
    }

    private fun samePoint(a: List<Double>, b: List<Double>): Boolean {
        if (a.size < 2 || b.size < 2) return false
        return kotlin.math.abs(a[0] - b[0]) < 1e-7 && kotlin.math.abs(a[1] - b[1]) < 1e-7
    }

    private fun toCell(lat: Double, lng: Double): Pair<Int, Int> {
        val latMeters = lat * 111_320.0
        val lngMeters = lng * 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
        return Pair(
            kotlin.math.floor(lngMeters / CELL_SIZE_METERS).toInt(),
            kotlin.math.floor(latMeters / CELL_SIZE_METERS).toInt()
        )
    }

    private fun isStationaryCluster(points: List<List<Double>>): Boolean {
        if (points.size < 2) return true
        val first = points.first()
        val last = points.last()
        val displacementMeters = distanceMeters(
            first.getOrElse(1) { 0.0 },
            first.getOrElse(0) { 0.0 },
            last.getOrElse(1) { 0.0 },
            last.getOrElse(0) { 0.0 }
        )
        val bbox = PolylineCodec.calculateBbox(points)
        val spreadMeters = if (bbox != null && bbox.size >= 4) {
            distanceMeters(bbox[1], bbox[0], bbox[3], bbox[2])
        } else {
            0.0
        }
        return displacementMeters < MIN_MOVEMENT_FOR_TRACK_METERS && spreadMeters < MIN_MOVEMENT_FOR_TRACK_METERS
    }

    private fun shouldDiscardCheckpoint(points: List<List<Double>>): Boolean {
        if (isStationaryCluster(points)) return true
        val pathLengthMeters = totalPathLengthMeters(points)
        val first = points.first()
        val last = points.last()
        val displacementMeters = distanceMeters(
            first.getOrElse(1) { 0.0 },
            first.getOrElse(0) { 0.0 },
            last.getOrElse(1) { 0.0 },
            last.getOrElse(0) { 0.0 }
        )
        return pathLengthMeters < MIN_PATH_LENGTH_FOR_TRACK_METERS &&
            displacementMeters < MIN_MOVEMENT_FOR_TRACK_METERS
    }

    private fun totalPathLengthMeters(points: List<List<Double>>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            total += distanceMeters(
                previous.getOrElse(1) { 0.0 },
                previous.getOrElse(0) { 0.0 },
                current.getOrElse(1) { 0.0 },
                current.getOrElse(0) { 0.0 }
            )
        }
        return total
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLng / 2) * kotlin.math.sin(dLng / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadiusMeters * c
    }

    private fun isLiveLocationSource(source: String): Boolean {
        return source in setOf(
            "manual_locate",
            "android_background_track",
            "android_background_track_segment",
            "auto_track",
            "auto_track_recovered",
            "android_recovered_track"
        )
    }

    private fun isMergeableAutoSource(source: String): Boolean {
        return source in setOf(
            "android_background_track",
            "android_background_track_segment",
            "auto_track",
            "auto_track_recovered",
            "android_recovered_track"
        )
    }

    private fun buildArchiveJson(tracks: List<TrackRecord>): JSONObject {
        return JSONObject().apply {
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
    }

    private fun migrateLegacyJsonIfNeeded() {
        runStore {
            if (prefs.getBoolean(KEY_ROOM_MIGRATED, false)) return@runStore
            if (dao.getTrackCount() > 0 || dao.getDraftPoints().isNotEmpty()) {
                prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
                return@runStore
            }

            val legacyTracks = readTracksFromFile(legacyArchiveFile)
                ?: readTracksFromFile(legacyArchiveBackupFile)
                ?: emptyList()
            val legacyDraft = readDraftPointsFromFile(legacyDraftFile)
                ?: readDraftPointsFromFile(legacyDraftBackupFile)
                ?: emptyList()

            if (legacyTracks.isNotEmpty()) {
                dao.replaceAllTracks(legacyTracks.map { it.toEntity() })
            }
            if (legacyDraft.isNotEmpty()) {
                writeDraftPoints(legacyDraft)
            }
            prefs.edit().putBoolean(KEY_ROOM_MIGRATED, true).apply()
        }
    }

    private fun <T> runStore(block: () -> T): T {
        if (Thread.currentThread() === storeThreadRef.get()) {
            return block()
        }
        return storeExecutor.submit<T> { block() }.get()
    }

    private fun readTracksFromFile(file: File): List<TrackRecord>? {
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val tracks = root.getJSONObject("sourceOfTruth").getJSONArray("tracks")
            (0 until tracks.length()).map { index -> trackFromJson(tracks.getJSONObject(index)) }
        }.getOrNull()
    }

    private fun readDraftPointsFromFile(file: File): List<List<Double>>? {
        if (!file.exists()) return null
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { idx ->
                val point = array.getJSONArray(idx)
                listOf(point.getDouble(0), point.getDouble(1))
            }
        }.getOrNull()
    }

    companion object {
        private const val PREF_NAME = "fog_visitor_native_tracking"
        private const val KEY_IS_TRACKING = "is_tracking"
        private const val KEY_SHOULD_TRACK = "should_track"
        private const val KEY_DRAFT_COUNT = "draft_count"
        private const val KEY_LAST_POINT_AT = "last_point_at"
        private const val KEY_LAST_LNG_BITS = "last_lng_bits"
        private const val KEY_LAST_LAT_BITS = "last_lat_bits"
        private const val KEY_LAST_ACCURACY_BITS = "last_accuracy_bits"
        private const val KEY_CREATED_AT = "archive_created_at"
        private const val KEY_ROOM_MIGRATED = "room_migrated"
        private const val MIN_MOVEMENT_FOR_TRACK_METERS = 20.0
        private const val MIN_PATH_LENGTH_FOR_TRACK_METERS = 45.0
        private const val CELL_SIZE_METERS = 20.0
        private const val MAX_MERGE_GAP_MS = 3 * 60 * 1000L
        private const val MAX_MERGE_DISTANCE_METERS = 35.0
        private const val MAX_IMPORT_BYTES = 25 * 1024 * 1024
        private const val MAX_IMPORT_UNCOMPRESSED_BYTES = 50 * 1024 * 1024
        private const val MAX_IMPORT_TRACK_COUNT = 20_000
        private const val MAX_ENCODED_PATH_LENGTH = 200_000
    }
}
