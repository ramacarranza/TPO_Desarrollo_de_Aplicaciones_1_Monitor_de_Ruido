package com.example.soundfriendlyworkplace.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SectorEntity::class,
        AlertaEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sectorDao(): SectorDao

    abstract fun alertaDao(): AlertaDao
}



