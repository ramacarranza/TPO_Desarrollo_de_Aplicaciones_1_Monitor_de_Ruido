package com.example.soundfriendlyworkplace.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.soundfriendlyworkplace.data.model.AlertaDto
import com.example.soundfriendlyworkplace.data.repository.AlertaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface AlertasUiState {
    data object Cargando : AlertasUiState

    data class Exito(
        val alertas: List<AlertaDto>,
        val desdeCache: Boolean = false
    ) : AlertasUiState

    data class Error(
        val mensaje: String
    ) : AlertasUiState
}

sealed interface AlertaDetalleUiState {
    data object Cargando : AlertaDetalleUiState

    data class Exito(
        val alerta: AlertaDto,
        val desdeCache: Boolean = false
    ) : AlertaDetalleUiState

    data class Error(
        val mensaje: String
    ) : AlertaDetalleUiState
}

class AlertaViewModel(
    private val repository: AlertaRepository
) : ViewModel() {

    var alertasUiState by mutableStateOf<AlertasUiState>(
        AlertasUiState.Cargando
    )
        private set

    var alertaDetalleUiState by mutableStateOf<AlertaDetalleUiState>(
        AlertaDetalleUiState.Cargando
    )
        private set

    var reconociendoAlerta by mutableStateOf(false)
        private set

    var errorReconocimiento by mutableStateOf<String?>(null)
        private set

    var registrandoIntervencion by mutableStateOf(false)
        private set

    var errorIntervencion by mutableStateOf<String?>(null)
        private set

    // Se conserva el id mientras la intervención no se confirme: un reintento reusa el mismo id.
    private var idIntervencionPendiente: String? = null

    init {
        cargarAlertas()
    }

    fun reconocerAlerta(alertaId: Int, reconocidaPor: String) {
        if (reconociendoAlerta) return

        viewModelScope.launch {
            reconociendoAlerta = true
            errorReconocimiento = null

            try {
                val alertaActualizada = repository.reconocerAlerta(
                    alertaId = alertaId,
                    reconocidaPor = reconocidaPor
                )

                alertaDetalleUiState = AlertaDetalleUiState.Exito(
                    alerta = alertaActualizada,
                    desdeCache = false
                )

                cargarAlertas()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorReconocimiento =
                    "No se pudo reconocer la alerta. Verificá la conexión."
            } finally {
                reconociendoAlerta = false
            }
        }
    }

    fun registrarIntervencion(alertaId: Int, descripcion: String, responsable: String) {
        if (registrandoIntervencion) return

        val idIntervencion = idIntervencionPendiente
            ?: UUID.randomUUID().toString().also { idIntervencionPendiente = it }

        viewModelScope.launch {
            registrandoIntervencion = true
            errorIntervencion = null

            try {
                val alertaActualizada = repository.registrarIntervencion(
                    idIntervencion = idIntervencion,
                    alertaId = alertaId,
                    descripcion = descripcion,
                    responsable = responsable.ifBlank { null }
                )

                idIntervencionPendiente = null

                alertaDetalleUiState = AlertaDetalleUiState.Exito(
                    alerta = alertaActualizada,
                    desdeCache = false
                )

                cargarAlertas()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("SFW-API", "Error al registrar la intervención", e)
                errorIntervencion =
                    "No se pudo registrar la intervención. Verificá la conexión y volvé a intentar."
            } finally {
                registrandoIntervencion = false
            }
        }
    }

    fun cargarAlertas() {
        viewModelScope.launch {
            alertasUiState = AlertasUiState.Cargando

            alertasUiState = try {
                val resultado = repository.obtenerAlertas()

                AlertasUiState.Exito(
                    alertas = resultado.alertas,
                    desdeCache = resultado.desdeCache
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AlertasUiState.Error(
                    "No se pudieron cargar las alertas y no hay datos guardados."
                )
            }
        }
    }

    fun cargarAlertaPorId(id: Int) {
        viewModelScope.launch {
            alertaDetalleUiState = AlertaDetalleUiState.Cargando

            alertaDetalleUiState = try {
                val resultado = repository.obtenerAlertaPorId(id)

                AlertaDetalleUiState.Exito(
                    alerta = resultado.alerta,
                    desdeCache = resultado.desdeCache
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AlertaDetalleUiState.Error(
                    "No se pudo cargar la alerta y no hay datos guardados."
                )
            }
        }
    }
}




