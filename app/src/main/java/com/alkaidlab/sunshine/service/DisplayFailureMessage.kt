package com.alkaidlab.sunshine.service

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Preserve actionable startup context; unwrap only transport/reflection envelopes. */
internal object DisplayFailureMessage {
    fun from(error: Throwable): String {
        var current = error
        var depth = 0
        while (depth++ < 8 && (current is InvocationTargetException || current is ExecutionException || current is CompletionException)) {
            current = current.cause ?: break
        }
        return current.message?.takeIf { it.isNotBlank() }
            ?: current.cause?.message?.takeIf { it.isNotBlank() }
            ?: current.javaClass.simpleName
    }
}
