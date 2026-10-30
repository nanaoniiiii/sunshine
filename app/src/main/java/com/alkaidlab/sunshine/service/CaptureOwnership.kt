package com.alkaidlab.sunshine.service

/** Incomplete privileged observations must not authorize automatic take-over. */
sealed class CaptureOwnership {
    object None : CaptureOwnership()
    object Unknown : CaptureOwnership()
    data class Active(val packageName: String) : CaptureOwnership()
    companion object {
        fun parse(dump: String?): CaptureOwnership {
            if (dump == null) return Unknown
            val value = dump.substringAfter("Media Projection:", "").trim()
            if (value == "null") return None
            val match = Regex("^\\(([A-Za-z0-9_.]+), uid=\\d+\\): TYPE_[A-Z_]+(?:\\s|$)").find(value)
            return match?.groupValues?.get(1)?.let { Active(it) } ?: Unknown
        }
    }
}
