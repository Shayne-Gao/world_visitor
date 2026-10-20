package io.shayne.fogvisitor.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [TrackSegmentEntity::class, DraftPointEntity::class, ArchiveSyncEntity::class],
    version = 2,
    exportSchema = false
)
abstract class FogVisitorDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        private fun createSyncState(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS archive_sync (id INTEGER NOT NULL PRIMARY KEY, epoch TEXT NOT NULL, revision INTEGER NOT NULL, reset_revision INTEGER NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO archive_sync VALUES (1, ?, 1, 1)", arrayOf(UUID.randomUUID().toString()))
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_segments ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
                createSyncState(db)
            }
        }

        @Volatile
        private var INSTANCE: FogVisitorDatabase? = null

        fun getInstance(context: Context): FogVisitorDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FogVisitorDatabase::class.java,
                    "fog_visitor_truth.db"
                ).addMigrations(MIGRATION_1_2)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createSyncState(db)
                        }
                    }).build()
                    .also { INSTANCE = it }
            }
        }
    }
}
