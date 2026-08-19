package com.example.dashtool.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DashSessionEntity::class,
        OfferEntity::class,
        RouteSnapshotEntity::class,
        OrderEventEntity::class
    ],

    version = 1,

    /*
     * We will enable schema export after the first
     * working database build, when migrations become
     * relevant.
     */
    exportSchema = false
)
abstract class DashToolDatabase :
    RoomDatabase() {

    abstract fun dashToolDao():
            DashToolDao

    companion object {

        private const val DATABASE_NAME =
            "dash_tool_database"

        @Volatile
        private var instance:
                DashToolDatabase? = null

        fun getInstance(
            context: Context
        ): DashToolDatabase {

            return instance
                ?: synchronized(this) {

                    instance
                        ?: Room.databaseBuilder(
                            context.applicationContext,
                            DashToolDatabase::class.java,
                            DATABASE_NAME
                        )
                            .build()
                            .also { database ->
                                instance = database
                            }
                }
        }
    }
}