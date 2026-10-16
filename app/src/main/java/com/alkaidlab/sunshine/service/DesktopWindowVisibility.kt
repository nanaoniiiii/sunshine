package com.alkaidlab.sunshine.service

/** A surface can exist while AppOps keeps it hidden. Validate the owned window itself. */
object DesktopWindowVisibility {
    private val header = Regex("(?m)^\\s*Window #\\d+ Window\\{[^\\r\\n]*")
    fun visible(dump: String, displayId: Int): Boolean {
        val windows = header.findAll(dump).toList()
        return windows.withIndex().any { (index, match) ->
            if (!match.value.contains("Sunshine 桌面控制")) return@any false
            val body = dump.substring(match.range.first, windows.getOrNull(index + 1)?.range?.first ?: dump.length)
            Regex("\\bmDisplayId=(\\d+)\\b").find(body)?.groupValues?.get(1)?.toIntOrNull() == displayId &&
                Regex("\\bmOwnerUid=(0|2000)\\b").containsMatchIn(body) &&
                Regex("\\bmHasSurface=true\\b").containsMatchIn(body) &&
                // dumpsys omits normal policy fields unless verbose output is requested.
                !Regex("\\bmAppOpVisibility=false\\b").containsMatchIn(body) &&
                Regex("\\bmDrawState=HAS_DRAWN\\b").containsMatchIn(body) &&
                Regex("\\bisOnScreen=true\\b").containsMatchIn(body)
        }
    }
}
