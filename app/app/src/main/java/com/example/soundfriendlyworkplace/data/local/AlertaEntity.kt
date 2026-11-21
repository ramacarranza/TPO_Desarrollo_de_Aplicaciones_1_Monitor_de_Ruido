package com.example.soundfriendlyworkplace.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alertas")
data class AlertaEntity(
    @PrimaryKey
    val id: Int,
    val sectorId: Int,
    val sector: String,
    val deviceId: String,
    val tipo: String,
    val valor: Double?,
    val mensaje: String,
    val estado: String,
    val creadaEn: String,
    val reconocidaPor: String?,
    val reconocidaEn: String?,
    val resueltaPor: String?,
    val resueltaEn: String?,
    val actualizadoEn: String
)


