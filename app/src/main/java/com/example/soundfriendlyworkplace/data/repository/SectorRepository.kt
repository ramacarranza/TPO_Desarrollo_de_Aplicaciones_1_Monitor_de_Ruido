package com.example.soundfriendlyworkplace.data.repository

import com.example.soundfriendlyworkplace.data.local.SectorDao
import com.example.soundfriendlyworkplace.data.local.SectorEntity
import com.example.soundfriendlyworkplace.data.model.SectorDto
import com.example.soundfriendlyworkplace.data.model.SensorDto
import com.example.soundfriendlyworkplace.data.remote.ApiService
import com.example.soundfriendlyworkplace.data.remote.RetrofitClient
import kotlinx.coroutines.CancellationException

data class ResultadoSectores(
    val sectores: List<SectorDto>,
    val desdeCache: Boolean
)

data class ResultadoSector(
    val sector: SectorDto,
    val desdeCache: Boolean
)

class SectorRepository(
    private val sectorDao: SectorDao,
    private val api: ApiService = RetrofitClient.api
) {


    suspend fun obtenerSectores(): ResultadoSectores {
        return try {
            // Obtenemos los sectores del backend.
            val sectores = api.obtenerSectores()

            // Actualizamos Room en una sola transacción.
            sectorDao.reemplazarSectores(
                sectores.map { it.aEntity() }
            )

            // Leemos los datos confirmados en Room.
            val sectoresGuardados = sectorDao.obtenerSectores()

            ResultadoSectores(
                sectores = sectoresGuardados.map { it.aDto() },
                desdeCache = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Si falla la actualización, usamos los datos anteriores.
            val sectoresGuardados = sectorDao.obtenerSectores()

            if (sectoresGuardados.isEmpty()) {
                throw e
            }

            ResultadoSectores(
                sectores = sectoresGuardados.map { it.aDto() },
                desdeCache = true
            )
        }
    }



    suspend fun obtenerSectorPorId(id: Int): ResultadoSector {
        return try {
            // Obtenemos el sector desde el backend.
            val sector = api.obtenerSectorPorId(id)

            // Guardamos o actualizamos el sector en Room.
            sectorDao.guardarSectores(
                listOf(sector.aEntity())
            )

            // Leemos el sector guardado en Room.
            val sectorGuardado = sectorDao.obtenerSectorPorId(id)
                ?: throw IllegalStateException("No se encontró el sector guardado.")

            ResultadoSector(
                sector = sectorGuardado.aDto(),
                desdeCache = false
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Si falla la actualización, intentamos usar la caché.
            val sectorGuardado = sectorDao.obtenerSectorPorId(id)
                ?: throw e

            ResultadoSector(
                sector = sectorGuardado.aDto(),
                desdeCache = true
            )
        }
    }
}

private fun SectorDto.aEntity(): SectorEntity {
    return SectorEntity(
        id = id,
        nombre = nombre,
        planta = planta,
        estado = estado,
        nivelDba = nivelDba,
        ultimaActualizacion = ultimaActualizacion,
        sensorId = sensor?.id,
        sensorEstado = sensor?.estado,
        alertasPendientes = alertasPendientes
    )
}

private fun SectorEntity.aDto(): SectorDto {
    return SectorDto(
        id = id,
        nombre = nombre,
        planta = planta,
        estado = estado,
        nivelDba = nivelDba,
        exposicionPct = null,
        ultimaActualizacion = ultimaActualizacion,
        sensor = if (sensorId != null && sensorEstado != null) {
            SensorDto(
                id = sensorId,
                estado = sensorEstado
            )
        } else {
            null
        },
        alertasPendientes = alertasPendientes
    )
}


