package com.appblocker.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for the list of blocks. Shared by the settings UI and
 * the accessibility service (same process), persisted as a small JSON file.
 *
 * Call [init] before use; BlockerApp does this at process start.
 */
object BlockRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val _blocks = MutableStateFlow<List<Block>>(emptyList())
    private var file: File? = null

    val blocks: StateFlow<List<Block>> = _blocks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "blocks.json")
        file = f
        _blocks.value = runCatching { json.decodeFromString<List<Block>>(f.readText()) }
            .getOrDefault(emptyList())
    }

    /** Adds the block, or replaces the existing one with the same id. */
    @Synchronized
    fun upsert(block: Block) {
        val current = _blocks.value
        val updated = if (current.any { it.id == block.id }) {
            current.map { if (it.id == block.id) block else it }
        } else {
            current + block
        }
        save(updated)
    }

    @Synchronized
    fun delete(id: String) {
        save(_blocks.value.filterNot { it.id == id })
    }

    private fun save(list: List<Block>) {
        _blocks.value = list
        val f = file ?: return
        // Write to a temp file then rename, so a crash can't leave a half-written file.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(list))
        tmp.renameTo(f)
    }
}
