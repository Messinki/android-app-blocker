package com.appblocker.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appblocker.data.Block
import com.appblocker.data.BlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the half-edited block so it survives rotation and folding/unfolding.
 * Nothing is written to the repository until [save] is called.
 */
class EditorViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {
    // Navigation puts the "blockId" route argument into the saved state handle.
    private val existing: Block? = savedStateHandle.get<String>("blockId")
        ?.let { id -> BlockRepository.blocks.value.firstOrNull { it.id == id } }

    val isNew: Boolean = existing == null

    var name by mutableStateOf(existing?.name ?: "")
    // Number fields are kept as text so the user can clear them while typing.
    var holdMinutes by mutableStateOf(((existing?.holdSeconds ?: Block.DEFAULT_HOLD_SECONDS) / 60).toString())
    var holdSecondsPart by mutableStateOf(((existing?.holdSeconds ?: Block.DEFAULT_HOLD_SECONDS) % 60).toString())
    var sessionMinutes by mutableStateOf((existing?.sessionMinutes ?: Block.DEFAULT_SESSION_MINUTES).toString())
    var packages by mutableStateOf(existing?.packages ?: emptySet())

    /** null while loading. */
    var apps by mutableStateOf<List<AppInfo>?>(null)
        private set
    private var loadStarted = false

    val holdTotalSeconds: Int
        get() = (holdMinutes.toIntOrNull() ?: 0) * 60 + (holdSecondsPart.toIntOrNull() ?: 0)

    val holdValid: Boolean get() = holdTotalSeconds in MIN_HOLD_SECONDS..MAX_HOLD_SECONDS
    val sessionValid: Boolean get() = sessionMinutes.toIntOrNull() in MIN_SESSION..MAX_SESSION
    val canSave: Boolean get() = holdValid && sessionValid

    fun loadApps(pm: PackageManager) {
        if (loadStarted) return
        loadStarted = true
        viewModelScope.launch {
            apps = withContext(Dispatchers.IO) { loadLaunchableApps(pm) }
        }
    }

    fun togglePackage(pkg: String) {
        packages = if (pkg in packages) packages - pkg else packages + pkg
    }

    fun save() {
        if (!canSave) return
        // Starting from the existing block keeps its id and enabled flag.
        val base = existing ?: Block()
        BlockRepository.upsert(
            base.copy(
                name = name.trim(),
                packages = packages,
                holdSeconds = holdTotalSeconds,
                sessionMinutes = sessionMinutes.toInt(),
            ),
        )
    }

    fun delete() {
        existing?.let { BlockRepository.delete(it.id) }
    }

    companion object {
        const val MIN_HOLD_SECONDS = 1
        const val MAX_HOLD_SECONDS = 60 * 60
        const val MIN_SESSION = 1
        const val MAX_SESSION = 600
    }
}
