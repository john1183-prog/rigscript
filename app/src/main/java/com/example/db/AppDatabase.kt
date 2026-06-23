package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.engine.StickFigureRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Database(
    entities = [ProjectEntity::class, PoseEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun poseDao(): PoseDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            lateinit var instance: AppDatabase
            instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "rigscript.db")
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
                                            poseJson  = json.encodeToString(pose)
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
