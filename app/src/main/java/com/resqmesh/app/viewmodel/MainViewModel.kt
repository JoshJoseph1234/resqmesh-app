package com.resqmesh.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resqmesh.app.ConnectivityState
import com.resqmesh.app.DeliveryStatus
import com.resqmesh.app.SosType
import com.resqmesh.app.data.ResQMeshDatabase
import com.resqmesh.app.data.SosMessageEntity
import com.resqmesh.app.data.SosRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // Initialize Database and Repository
    private val database = ResQMeshDatabase.getDatabase(application)
    private val repository = SosRepository(database.sosDao())

    // UI State: Messages (Automatically updates when DB changes)
    val sentMessages: StateFlow<List<SosMessageEntity>> = repository.allMessages
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // UI State: Connectivity
    private val _connectivity = MutableStateFlow(ConnectivityState.OFFLINE)
    val connectivity = _connectivity.asStateFlow()

    fun sendSos(type: SosType, messageText: String) {
        viewModelScope.launch {
            val newSos = SosMessageEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                message = messageText,
                timestamp = System.currentTimeMillis(),
                status = DeliveryStatus.PENDING
            )

            // Save to Room Database offline
            repository.saveMessage(newSos)

            // Simulate Mesh connection active
            _connectivity.value = ConnectivityState.MESH_ACTIVE
        }
    }
}

