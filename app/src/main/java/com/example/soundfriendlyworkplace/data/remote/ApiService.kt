package com.example.soundfriendlyworkplace.data.remote

import com.example.soundfriendlyworkplace.data.model.AlertaDto
import com.example.soundfriendlyworkplace.data.model.MedicionDto
import com.example.soundfriendlyworkplace.data.model.SectorDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @GET("api/v1/sectores")
    suspend fun obtenerSectores(): List<SectorDto>

    @GET("api/v1/sectores/{id}")
    suspend fun obtenerSectorPorId(
        @Path("id") id: Int
    ): SectorDto

    @GET("api/v1/alertas")
    suspend fun obtenerAlertas(): List<AlertaDto>

    @GET("api/v1/alertas/{id}")
    suspend fun obtenerAlertaPorId(
        @Path("id") id: Int
    ): AlertaDto

    @GET("api/v1/sectores/{id}/mediciones")
    suspend fun obtenerMediciones(
        @Path("id") sectorId: Int,
        @Query("agrupar") agrupar: String = "15m"
    ): List<MedicionDto>
}




