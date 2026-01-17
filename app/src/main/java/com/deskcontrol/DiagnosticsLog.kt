package com.deskcontrol

import android.content.res.Resources
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private var resources: Resources? = null

    fun init(resources: Resources) {
        this.resources = resources
    }

    @Synchronized
    fun add(message: String) {
        val timestamp = createFormatter().format(Date())
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
        }
        lines.addLast(formatLine(timestamp, message))
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    private fun createFormatter(): SimpleDateFormat {
        val pattern = requireResources().getString(R.string.diagnostics_log_time_format)
        return SimpleDateFormat(pattern, Locale.getDefault())
    }

    private fun formatLine(timestamp: String, message: String): String {
        return requireResources().getString(
            R.string.diagnostics_log_line_format,
            timestamp,
            message
        )
    }

    private fun requireResources(): Resources {
        return checkNotNull(resources) { "DiagnosticsLog not initialized" }
    }
}
