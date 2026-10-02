package com.logicedge.opencodemobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.logicedge.opencodemobile.data.ServerProfile
import com.logicedge.opencodemobile.data.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServerViewModel(private val repository: ServerRepository) : ViewModel() {

    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    val defaultProfile: ServerProfile?
        get() = _profiles.value.firstOrNull { it.isDefault }

    val sortedProfiles: List<ServerProfile>
        get() = _profiles.value.sortedWith(
            compareByDescending<ServerProfile> { it.isDefault }.thenBy { it.name },
        )

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            try {
                _profiles.value = repository.load()
            } finally {
                _loading.value = false
            }
        }
    }

    fun refreshStatuses(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _profiles.value = repository.checkAll()
            onDone()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.deleteProfile(id)
            _profiles.value = repository.load()
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            repository.duplicateProfile(id)
            _profiles.value = repository.load()
        }
    }

    fun setDefault(id: String) {
        viewModelScope.launch {
            repository.setDefaultProfile(id)
            _profiles.value = repository.load()
        }
    }
}
