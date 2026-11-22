package com.example.soundfriendlyworkplace.data.model

// Intervención registrada sobre una alerta (GET /api/v1/alertas/{id} y POST /api/v1/intervenciones).
data class IntervencionDto(
    val id: String,
    val alertaId: Int,
    val sectorId: Int,
    val usuario: String?,       // nombre de quien la registró
    val descripcion: String,
    val realizadaEn: String,
    val creadaEn: String
)

// Cuerpo de POST /api/v1/intervenciones.
// El id (UUID) lo genera la app: si se reenvía la misma intervención, el backend no la duplica.
data class NuevaIntervencionRequest(
    val id: String,
    val alertaId: Int,
    val descripcion: String,
    val responsable: String?
)
