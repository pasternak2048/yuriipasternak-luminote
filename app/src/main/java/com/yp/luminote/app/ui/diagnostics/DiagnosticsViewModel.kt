package com.yp.luminote.app.ui.diagnostics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yp.luminote.app.diagnostics.DiagnosticsRepository
import com.yp.luminote.app.diagnostics.DiagnosticsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(
    private val repository: DiagnosticsRepository
) : ViewModel() {
    private val _status = MutableStateFlow(repository.status())
    val status: StateFlow<DiagnosticsStatus> = _status.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _message = MutableStateFlow<DiagnosticsMessage?>(null)
    val message: StateFlow<DiagnosticsMessage?> = _message.asStateFlow()

    fun export(uri: Uri) {
        viewModelScope.launch {
            _exporting.value = true
            _message.value = null
            runCatching { repository.export(uri) }
                .onSuccess { _message.value = DiagnosticsMessage.EXPORTED }
                .onFailure { _message.value = DiagnosticsMessage.EXPORT_FAILED }
            _exporting.value = false
            refresh()
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clearAndAwait()
            _message.value = DiagnosticsMessage.CLEARED
            refresh()
        }
    }

    fun refresh() {
        _status.value = repository.status()
    }

    fun consumeMessage() {
        _message.value = null
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DiagnosticsViewModel::class.java)) {
                return DiagnosticsViewModel(DiagnosticsRepository.get(applicationContext)) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

enum class DiagnosticsMessage { EXPORTED, EXPORT_FAILED, CLEARED }
