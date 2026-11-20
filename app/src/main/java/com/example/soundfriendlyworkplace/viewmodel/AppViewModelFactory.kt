package com.example.soundfriendlyworkplace.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.soundfriendlyworkplace.data.repository.AlertaRepository

class AlertaViewModelFactory(
    private val repository: AlertaRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AlertaViewModel::class.java)) {
            return AlertaViewModel(repository) as T
        }

        throw IllegalArgumentException("ViewModel desconocido")
    }
}


