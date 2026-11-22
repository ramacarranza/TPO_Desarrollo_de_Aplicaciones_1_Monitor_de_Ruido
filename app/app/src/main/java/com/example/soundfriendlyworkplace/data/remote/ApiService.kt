package com.example.soundfriendlyworkplace.data.remote

import com.example.soundfriendlyworkplace.data.model.AlertaDto
import com.example.soundfriendlyworkplace.data.model.IntervencionDto
import com.example.soundfriendlyworkplace.data.model.MedicionDto
import com.example.soundfriendlyworkplace.data.model.NuevaIntervencionRequest
import com.example.soundfriendlyworkplace.data.model.ReconocerAlertaRequest
import com.example.soundfriendlyworkplace.data.model.SectorDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.POST
import retrofit2.http.Body

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

    @POST("api/v1/alertas/{id}/reconocer")
    suspend fun reconocerAlerta(
        @Path("id") alertaId: Int,
        @Body solicitud: ReconocerAlertaRequest
    ): AlertaDto

    // Responde 201 la primera vez y 200 si se reenvía la misma intervención (mismo id).
    @POST("api/v1/intervenciones")
    suspend fun registrarIntervencion(
        @Body solicitud: NuevaIntervencionRequest
    ): IntervencionDto
}




