package com.alkaidlab.sunshine.service

import android.util.AtomicFile
import com.alkaidlab.sunshine.data.*
import com.google.gson.Gson
import java.io.File

/** One lock and atomic writes for REST and native Moonlight catalog reads. */
object AppCatalog {
    private val gson = Gson()
    @Synchronized fun read(file: File): AppsResponse {
        val source = gson.fromJson(AtomicFile(file).openRead().bufferedReader().use { it.readText() }, AppsResponse::class.java)
        val normalized = normalize(source)
        if (source != normalized) write(file, normalized)
        return normalized
    }
    internal fun normalize(source: AppsResponse): AppsResponse {
        val used = mutableSetOf<Int>()
        var next = maxOf(2, source.nextId, (source.apps.mapNotNull { it.id?.toIntOrNull() }.maxOrNull() ?: 1) + 1)
        val apps = source.apps.map { app ->
            val old = app.id?.toIntOrNull()?.takeIf { it > 0 && it !in used }
            val id = old ?: if (app.name.equals("Desktop", true) && 1 !in used) 1 else next++
            used += id
            app.copy(id = id.toString())
        }
        return source.copy(apps = apps, nextId = next)
    }
    @Synchronized fun save(file: File, item: AppItem) {
        val source = read(file)
        val existing = source.apps.firstOrNull { it.id == item.id && item.id != null }
            ?: source.apps.firstOrNull { it.name == item.name }
        val saved = item.copy(id = existing?.id ?: source.nextId.toString())
        val updated = if (existing == null) source.apps + saved else source.apps.map { if (it.id == existing.id) saved else it }
        write(file, source.copy(apps = updated, nextId = source.nextId + if (existing == null) 1 else 0))
    }
    @Synchronized fun delete(file: File, idOrName: String) {
        val source = read(file)
        write(file, source.copy(apps = source.apps.filterNot { it.id == idOrName || it.name == idOrName }))
    }
    private fun write(file: File, source: AppsResponse) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(gson.toJson(source).toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
