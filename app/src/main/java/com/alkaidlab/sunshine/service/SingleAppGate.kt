package com.alkaidlab.sunshine.service

/** Notifications do not change the resumed Activity; floating/PiP stacks are skipped. */
object ForegroundAppParser {
    private val stackHeader = Regex("^\\s*(Stack|RootTask) #")
    private val activityPackage = Regex(" u\\d+ ([A-Za-z0-9_.]+)/")
    private val transientPackages = setOf("com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller")
    fun fullscreenPackage(text: String): String? {
        var primaryDisplay = false
        var fullscreen = false
        for (line in text.lineSequence()) {
            if (line.startsWith("Display #")) primaryDisplay = line.startsWith("Display #0")
            if (!primaryDisplay) continue
            if (stackHeader.containsMatchIn(line)) {
                fullscreen = line.contains("mode=fullscreen") && !line.contains("type=recents")
            }
            if (fullscreen && line.contains("mResumedActivity:")) {
                val pkg = activityPackage.find(line)?.groupValues?.get(1)
                if (pkg != null && pkg !in transientPackages) return pkg
            }
        }
        return null
    }
}

/** Each connection owns its own gate. Unknown/overlay observations never mean app exit. */
class SingleAppGate(private val packageName: String, private val startedAt: Long) {
    private var entered = false
    private var awaySince: Long? = null
    fun shouldEnd(fullscreenPackage: String?, now: Long): Boolean {
        if (fullscreenPackage == null) { awaySince = null; return false }
        if (fullscreenPackage == packageName) { entered = true; awaySince = null; return false }
        if (!entered) return now - startedAt >= 10000
        val since = awaySince ?: now.also { awaySince = it }
        return now - since >= 500
    }
}
