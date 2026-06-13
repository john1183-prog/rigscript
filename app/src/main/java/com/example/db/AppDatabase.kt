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

        private fun buildDatabase(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "rigscript.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Seed built-in poses on first creation
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.poseDao()?.let { dao ->
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
    }
}
