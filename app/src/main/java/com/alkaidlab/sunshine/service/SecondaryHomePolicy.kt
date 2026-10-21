package com.alkaidlab.sunshine.service

/** Only HOME/empty tasks on the owned secondary display may be replaced. */
internal object SecondaryHomePolicy {
    fun restore(displayId: Int, top: String?, sunshine: String, homes: Set<String>, homeTask: Boolean): Boolean =
        displayId > 0 && top != sunshine && (top == null || homeTask || top in homes)
}
