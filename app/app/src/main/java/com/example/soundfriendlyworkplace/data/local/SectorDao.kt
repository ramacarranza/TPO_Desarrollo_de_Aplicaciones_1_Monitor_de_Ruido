
package com.example.soundfriendlyworkplace.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SectorDao {

    // Guarda o actualiza sectores individuales.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun guardarSectores(sectores: List<SectorEntity>)

    // Elimina los sectores almacenados.
    @Query("DELETE FROM sectores")
    suspend fun eliminarSectores()

    // Reemplaza el listado completo de forma segura.
    @Transaction
    suspend fun reemplazarSectores(sectores: List<SectorEntity>) {
        eliminarSectores()
        guardarSectores(sectores)
    }

    // Obtiene todos los sectores almacenados localmente.
    @Query("SELECT * FROM sectores ORDER BY nombre ASC")
    suspend fun obtenerSectores(): List<SectorEntity>

    // Obtiene un sector específico mediante su ID.
    @Query("SELECT * FROM sectores WHERE id = :id LIMIT 1")
    suspend fun obtenerSectorPorId(id: Int): SectorEntity?
}



