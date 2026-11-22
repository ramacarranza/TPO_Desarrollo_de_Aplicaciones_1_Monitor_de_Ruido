package com.example.soundfriendlyworkplace.data.model

data class AlertaDto(
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
    val actualizadoEn: String,
    // Solo viene en el detalle (GET /alertas/{id}); en la lista llega nula.
    val intervenciones: List<IntervencionDto>? = null
)

data class ReconocerAlertaRequest(
    val reconocidaPor: String
)


