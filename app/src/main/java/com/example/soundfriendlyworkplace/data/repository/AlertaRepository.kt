
package com.example.soundfriendlyworkplace.data.repository

import com.example.soundfriendlyworkplace.data.local.AlertaDao
import com.example.soundfriendlyworkplace.data.local.AlertaEntity
import com.example.soundfriendlyworkplace.data.model.AlertaDto
import com.example.soundfriendlyworkplace.data.model.ReconocerAlertaRequest
import com.example.soundfriendlyworkplace.data.remote.ApiService
import com.example.soundfriendlyworkplace.data.remote.RetrofitClient
import kotlinx.coroutines.CancellationException

data class ResultadoAlertas(
    val alertas: List<AlertaDto>,
    val desdeCache: Boolean
)

data class ResultadoAlerta(
    val alerta: AlertaDto,
    val desdeCache: Boolean
)

class AlertaRepository(
    private val alertaDao: AlertaDao,
    private val api: ApiService = RetrofitClient.api
) {

    suspend fun obtenerAlertas(): ResultadoAlertas {
        return try {
            // Consultamos el backend.
            val alertas = api.obtenerAlertas()

            // Guardamos la lista completa en Room.
            alertaDao.reemplazarAlertas(
                alertas.map { it.aEntity() }
            )

            ResultadoAlertas(
                alertas = alertaDao.obtenerAlertas().map { it.aDto() },
                desdeCache = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Si falla la consulta, usamos los datos guardados.
            val alertasGuardadas = alertaDao.obtenerAlertas()

            if (alertasGuardadas.isEmpty()) {
                throw e
            }

            ResultadoAlertas(
                alertas = alertasGuardadas.map { it.aDto() },
                desdeCache = true
            )
        }
    }

    suspend fun obtenerAlertaPorId(id: Int): ResultadoAlerta {
        return try {
            // Consultamos el detalle en el backend.
            val alerta = api.obtenerAlertaPorId(id)

            // Guardamos la alerta recibida.
            alertaDao.guardarAlertas(listOf(alerta.aEntity()))

            val alertaGuardada = alertaDao.obtenerAlertaPorId(id)
                ?: throw IllegalStateException("No se encontró la alerta guardada.")

            ResultadoAlerta(
                alerta = alertaGuardada.aDto(),
                desdeCache = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val alertaGuardada = alertaDao.obtenerAlertaPorId(id)
                ?: throw e

            ResultadoAlerta(
                alerta = alertaGuardada.aDto(),
                desdeCache = true
            )
        }
    }

    suspend fun reconocerAlerta(
        alertaId: Int,
        reconocidaPor: String
    ): AlertaDto {

        val alertaActualizada = api.reconocerAlerta(
            alertaId = alertaId,
            solicitud = ReconocerAlertaRequest(
                reconocidaPor = reconocidaPor
            )
        )

        alertaDao.guardarAlertas(
            listOf(alertaActualizada.aEntity())
        )

        return alertaActualizada
    }
}

private fun AlertaDto.aEntity(): AlertaEntity {
    return AlertaEntity(
        id = id,
        sectorId = sectorId,
        sector = sector,
        deviceId = deviceId,
        tipo = tipo,
        valor = valor,
        mensaje = mensaje,
        estado = estado,
        creadaEn = creadaEn,
        reconocidaPor = reconocidaPor,
        reconocidaEn = reconocidaEn,
        resueltaPor = resueltaPor,
        resueltaEn = resueltaEn,
        actualizadoEn = actualizadoEn
    )
}

private fun AlertaEntity.aDto(): AlertaDto {
    return AlertaDto(
        id = id,
        sectorId = sectorId,
        sector = sector,
        deviceId = deviceId,
        tipo = tipo,
        valor = valor,
        mensaje = mensaje,
        estado = estado,
        creadaEn = creadaEn,
        reconocidaPor = reconocidaPor,
        reconocidaEn = reconocidaEn,
        resueltaPor = resueltaPor,
        resueltaEn = resueltaEn,
        actualizadoEn = actualizadoEn,
        intervenciones = emptyList()
    )
}





