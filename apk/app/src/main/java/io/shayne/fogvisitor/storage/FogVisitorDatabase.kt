package io.shayne.fogvisitor.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackSegmentEntity::class, DraftPointEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FogVisitorDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: FogVisitorDatabase? = null

        fun getInstance(context: Context): FogVisitorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FogVisitorDatabase::class.java,
                    "fog_visitor_truth.db"
                ).build()
                    .also { INSTANCE = it }
            }
        }
    }
}
