package com.alkaidlab.sunshine.ui.viewmodel

import com.alkaidlab.sunshine.data.ConfigResponse

/** Called on the UI thread. Revisions preserve edits made while a request is in flight. */
class SettingsAutosave {
    data class Snapshot(val confirmed: ConfigResponse?, val draft: SettingsDraft?)
    data class Batch(val values: Map<String, String>, val revisions: Map<String, Long>)
    private var confirmed: ConfigResponse? = null
    private var draft: SettingsDraft? = null
    private var revision = 0L
    private val edited = mutableMapOf<String, Long>()
    private val ready = mutableSetOf<String>()

    fun edit(key: String, value: String): Snapshot {
        draft?.let {
            draft = it.withValue(key, value)
            edited[key] = ++revision
            if (key in numericKeys) ready.remove(key) else ready.add(key)
        }
        return snapshot()
    }

    fun finishEdit(key: String) {
        if (key in edited) ready.add(key)
    }

    fun refreshed(current: ConfigResponse): Snapshot {
        confirmed = current
        rebase()
        return snapshot()
    }

    /** Only call when no previous batch is in flight. Invalid inputs stay editable. */
    fun nextBatch(): Batch? {
        val current = confirmed ?: return null
        val input = draft ?: return null
        val saved = SettingsDraft.from(current)
        edited.keys.removeAll { input[it] == saved[it] }
        val errors = input.errors()
        val values = input.values.filter { (key, _) -> key in edited && key in ready && key !in errors }.toMutableMap()
        val min = (values["min_bitrate"] ?: saved["min_bitrate"]).toIntOrNull()
        val max = (values["max_bitrate"] ?: saved["max_bitrate"]).toIntOrNull()
        if (min != null && max != null && min > max) {
            values.remove("min_bitrate")
            values.remove("max_bitrate")
        }
        return if (values.isEmpty()) null else Batch(values, edited.filterKeys { it in values })
    }

    fun acknowledged(batch: Batch, current: ConfigResponse): Snapshot {
        batch.revisions.forEach { (key, sentRevision) ->
            if (edited[key] == sentRevision) {
                edited.remove(key)
                ready.remove(key)
            }
        }
        confirmed = current
        rebase()
        return snapshot()
    }

    fun hasUnsavedEdits(): Boolean {
        val current = confirmed ?: return false
        val input = draft ?: return false
        val saved = SettingsDraft.from(current)
        return edited.keys.any { input[it] != saved[it] }
    }

    private fun rebase() {
        val current = confirmed ?: return
        draft = SettingsDraft(SettingsDraft.from(current).values + draft?.values.orEmpty().filterKeys { it in edited })
    }

    private fun snapshot() = Snapshot(confirmed, draft)

    companion object {
        val numericKeys = setOf("max_stream_fps", "audio_buffer_ms", "min_bitrate", "max_bitrate", "port", "web_port")
    }
}
