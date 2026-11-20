
package com.example.soundfriendlyworkplace.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseProvider {

    @Volatile
    private var instancia: AppDatabase? = null

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `alertas` (
                    `id` INTEGER NOT NULL,
                    `sectorId` INTEGER NOT NULL,
                    `sector` TEXT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `tipo` TEXT NOT NULL,
                    `valor` REAL,
                    `mensaje` TEXT NOT NULL,
                    `estado` TEXT NOT NULL,
                    `creadaEn` TEXT NOT NULL,
                    `reconocidaPor` TEXT,
                    `reconocidaEn` TEXT,
                    `resueltaPor` TEXT,
                    `resueltaEn` TEXT,
                    `actualizadoEn` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    fun obtenerBaseDeDatos(context: Context): AppDatabase {
        return instancia ?: synchronized(this) {
            instancia ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sound_friendly_workplace.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instancia = it }
        }
    }
}



