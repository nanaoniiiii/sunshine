package com.alkaidlab.sunshine.controller

/** Changes only our service entry; other accessibility services can change while we run. */
internal class ControllerNavigationSettings(
    private val component: String,
    private val matchesComponent: (String) -> Boolean,
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    companion object {
        const val SERVICES = "enabled_accessibility_services"
        const val ENABLED = "accessibility_enabled"
        // Keep ownership across a Shizuku UserService process restart. An entry
        // already enabled by the user is never acquired or removed by us.
        const val OWNED = "sunshine_controller_navigation_owned"
    }

    fun setEnabled(enabled: Boolean) {
        val existing = read(SERVICES).orEmpty().split(':').filter { it.isNotBlank() }.toMutableList()
        if (enabled) {
            if (existing.none(matchesComponent)) {
                existing.add(component)
                // Record before changing the list so even a process death or
                // failed verification leaves a recoverable ownership record.
                write(OWNED, "1")
                write(SERVICES, existing.joinToString(":"))
            }
            // OEM settings or a prior interrupted startup may leave our entry
            // present while the global switch is off. Reconcile both settings.
            if (read(ENABLED) != "1") write(ENABLED, "1")
        } else if (read(OWNED) == "1") {
            existing.removeAll(matchesComponent)
            write(SERVICES, existing.joinToString(":"))
            if (existing.isEmpty() && read(ENABLED) != "0") write(ENABLED, "0")
            // Clear ownership last, so a failed write can be retried on stop.
            write(OWNED, "0")
        }
    }
}
