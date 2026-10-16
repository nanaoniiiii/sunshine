package com.alkaidlab.sunshine.service

/** Capabilities, not a model-name allowlist. A compatible desktop is never reported as native DeX. */
object SamsungDexPolicy {
    enum class Route { EXISTING, LOCAL_PC, STANDALONE, COMPATIBLE }
    fun route(samsung: Boolean, active: Boolean, external: Boolean, pc: Boolean, standalone: Boolean): Route = when {
        !samsung -> Route.COMPATIBLE
        active || external -> Route.EXISTING
        pc -> Route.LOCAL_PC
        standalone -> Route.STANDALONE
        else -> Route.COMPATIBLE
    }
}
