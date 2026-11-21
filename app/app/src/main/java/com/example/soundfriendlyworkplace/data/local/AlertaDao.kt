package com.example.soundfriendlyworkplace.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AlertaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarAlertas(alertas: List<AlertaEntity>)

    @Query("DELETE FROM alertas")
    suspend fun eliminarAlertas()

    @Transaction
    suspend fun reemplazarAlertas(alertas: List<AlertaEntity>) {
        eliminarAlertas()
        guardarAlertas(alertas)
    }

    @Query("SELECT * FROM alertas")
    suspend fun obtenerAlertas(): List<AlertaEntity>

    @Query("SELECT * FROM alertas WHERE id = :id LIMIT 1")
    suspend fun obtenerAlertaPorId(id: Int): AlertaEntity?
}


