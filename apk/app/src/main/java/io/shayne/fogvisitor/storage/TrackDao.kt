package io.shayne.fogvisitor.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TrackDao {

    @Query("SELECT * FROM track_segments ORDER BY timestamp ASC")
    fun getAllTracks(): List<TrackSegmentEntity>

    @Query("SELECT * FROM track_segments WHERE timestamp > :afterTimestamp ORDER BY timestamp ASC")
    fun getTracksAfter(afterTimestamp: Long): List<TrackSegmentEntity>

    @Query("SELECT * FROM track_segments WHERE revision > :afterRevision ORDER BY revision ASC")
    fun getTracksAfterRevision(afterRevision: Long): List<TrackSegmentEntity>

    @Query("SELECT * FROM archive_sync WHERE id = 1")
    fun getSyncState(): ArchiveSyncEntity

    @Query("UPDATE archive_sync SET revision = revision + 1 WHERE id = 1")
    fun advanceRevision()

    @Query("UPDATE archive_sync SET reset_revision = revision WHERE id = 1")
    fun markResetRevision()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrack(track: TrackSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTracks(tracks: List<TrackSegmentEntity>)

    @Query("DELETE FROM track_segments")
    fun deleteAllTracks()

    @Transaction
    fun upsertTrack(track: TrackSegmentEntity) {
        advanceRevision()
        insertTrack(track.copy(revision = getSyncState().revision))
    }

    @Transaction
    fun clearTracks() {
        advanceRevision()
        markResetRevision()
        deleteAllTracks()
    }

    @Query("SELECT COUNT(*) FROM track_segments")
    fun getTrackCount(): Int

    @Query("SELECT MAX(timestamp) FROM track_segments")
    fun getLatestTimestamp(): Long?

    @Query("SELECT * FROM track_segments ORDER BY timestamp DESC LIMIT 1")
    fun getLatestTrack(): TrackSegmentEntity?

    @Query("SELECT * FROM draft_points ORDER BY sequence ASC")
    fun getDraftPoints(): List<DraftPointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDraftPoints(points: List<DraftPointEntity>)

    @Query("DELETE FROM draft_points")
    fun clearDraftPoints()

    @Transaction
    fun replaceDraftPoints(points: List<DraftPointEntity>) {
        clearDraftPoints()
        if (points.isNotEmpty()) upsertDraftPoints(points)
    }

    @Transaction
    fun replaceAllTracks(tracks: List<TrackSegmentEntity>) {
        clearTracks()
        val revision = getSyncState().revision
        if (tracks.isNotEmpty()) insertTracks(tracks.map { it.copy(revision = revision) })
    }
}
