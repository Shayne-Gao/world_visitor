package io.shayne.fogvisitor.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_segments")
data class TrackSegmentEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val source: String,
    @ColumnInfo(name = "brush_radius_km")
    val brushRadiusKm: Double,
    @ColumnInfo(name = "encoded_path")
    val encodedPath: String,
    @ColumnInfo(name = "bbox_json")
    val bboxJson: String?,
    @ColumnInfo(defaultValue = "0")
    val revision: Long = 0
)

@Entity(tableName = "archive_sync")
data class ArchiveSyncEntity(
    @PrimaryKey val id: Int = 1,
    val epoch: String,
    val revision: Long,
    @ColumnInfo(name = "reset_revision") val resetRevision: Long
) {
    fun requiresReset(clientEpoch: String, afterRevision: Long): Boolean =
        clientEpoch != epoch || afterRevision < resetRevision ||
            afterRevision > revision || afterRevision < 0
}

@Entity(tableName = "draft_points", primaryKeys = ["sequence"])
data class DraftPointEntity(
    val sequence: Int,
    val lng: Double,
    val lat: Double
)
