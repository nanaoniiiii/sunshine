package com.alkaidlab.sunshine.ui.desktop

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo

data class DesktopPosition(val page: String = "apps", val query: String = "", val search: Boolean = false,
    val selected: Int = 0, val selectedKey: String? = null, val zone: String = "rail", val topIndex: Int = 0,
    val actionIndex: Int = 0, val taskSelected: Int = 0, val quickSelected: Int = 0,
    val railIndex: Int = 0, val railOffset: Int = 0)

/** One independent position per secondary display; HOME does not reset it. */
object DesktopSessionCache {
    private val positions = java.util.concurrent.ConcurrentHashMap<Int, DesktopPosition>()
    fun read(display: Int) = positions[display] ?: DesktopPosition()
    fun save(display: Int, position: DesktopPosition) { positions[display] = position }
    fun clear(display: Int) { positions.remove(display) }
}

object DesktopAppCatalogCache {
    @Volatile private var apps: List<ResolveInfo>? = null
    fun peek(): List<ResolveInfo> = apps.orEmpty()
    @Synchronized fun invalidate() { apps = null }
    @Synchronized fun load(context: Context, force: Boolean = false): List<ResolveInfo> {
        if (!force) apps?.let { return it }
        return context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.enabled && it.activityInfo.exported }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
            .sortedBy { it.loadLabel(context.packageManager).toString() }.also { apps = it }
    }
}
