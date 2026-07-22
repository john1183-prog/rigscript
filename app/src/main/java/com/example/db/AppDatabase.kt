package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.AppJson
import com.example.engine.StickFigureRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@Database(
    entities = [ProjectEntity::class, PoseEntity::class, AppearancePresetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun poseDao(): PoseDao
    abstract fun appearancePresetDao(): AppearancePresetDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Adds the appearance_presets table. Deliberately a real migration,
         * not `.fallbackToDestructiveMigration()` — this app has real
         * projects and a real pose library on the developer's phone now;
         * destructive fallback would silently wipe both the next time the
         * app opens post-update, just to add one new, unrelated table.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS appearance_presets (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        appearanceJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            lateinit var instance: AppDatabase
            instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "rigscript.db")
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed built-in poses on first creation.
                        // Uses the locally-captured `instance` rather than the
                        // companion's INSTANCE field: INSTANCE is only assigned
                        // in getInstance() *after* this function returns, so
                        // reading it from the coroutine would see null and
                        // silently skip seeding on a cold start.
                        CoroutineScope(Dispatchers.IO).launch {
                            instance.poseDao().let { dao ->
                                StickFigureRig.BUILT_IN_POSES.forEach { pose ->
                                    dao.insertIfAbsent(
                                        PoseEntity(
                                            id        = pose.id,
                                            name      = pose.name,
                                            category  = pose.category,
                                            isBuiltIn = true,
                                            poseJson  = AppJson.storage.encodeToString(pose)
                                        )
                                    )
                                }
                            }
                        }
                    }
                })
                .build()
            return instance
        }
    }
}
