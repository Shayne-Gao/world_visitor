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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTrack(track: TrackSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertTracks(tracks: List<TrackSegmentEntity>)

    @Query("DELETE FROM track_segments")
    fun clearTracks()

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
        if (tracks.isNotEmpty()) upsertTracks(tracks)
    }
}
