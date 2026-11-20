package com.example.soundfriendlyworkplace.data.model

data class MedicionDto(
    val ts: String,
    val laeq: Double,
    val lmax: Double,
    val lmin: Double,
    val minutos: Int
)