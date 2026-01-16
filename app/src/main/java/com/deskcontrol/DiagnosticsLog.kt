package com.deskcontrol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        val timestamp = formatter.format(Date())
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
        }
        lines.addLast("[$timestamp] $message")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
