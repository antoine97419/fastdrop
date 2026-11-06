package com.fastdrop

/** Detailed protocol traces are opt-in; failures remain visible to diagnostics. */
object FastDropDiagnostics {
    var enabled: Boolean = false

    fun debug(message: String) {
        if (enabled) println(message)
    }
}
