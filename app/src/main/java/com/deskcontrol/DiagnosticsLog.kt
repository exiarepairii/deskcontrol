package com.deskcontrol

import android.content.Context
import android.content.res.Resources
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object DiagnosticsLog {
    private const val MAX_LINES = 1_000
    private const val COMPACT_EVERY_N_WRITES = 100
    private const val LOG_FILE_NAME = "diagnostics.log"
    private val lines = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DeskControl-Diagnostics").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private var resources: Resources? = null
    private var logFile: File? = null
    private var writesSinceCompaction = 0

    @Synchronized
    fun init(context: Context) {
        resources = context.resources
        logFile = File(context.filesDir, LOG_FILE_NAME)
        lines.clear()
        runCatching {
            logFile
                ?.takeIf(File::exists)
                ?.readLines()
                ?.takeLast(MAX_LINES)
                ?.forEach(lines::addLast)
        }
        writesSinceCompaction = if (lines.size >= MAX_LINES) {
            COMPACT_EVERY_N_WRITES
        } else {
            0
        }
    }

    @Synchronized
    fun add(message: String) {
        val timestamp = createFormatter().format(Date())
        val removedOldest = lines.size >= MAX_LINES
        if (removedOldest) {
            lines.removeFirst()
        }
        val line = formatLine(timestamp, message.replace('\n', ' '))
        lines.addLast(line)
        val compactedLines = if (
            removedOldest && writesSinceCompaction >= COMPACT_EVERY_N_WRITES
        ) {
            writesSinceCompaction = 0
            lines.toList()
        } else {
            writesSinceCompaction += 1
            null
        }
        persistAsync(line, compactedLines)
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

    private fun persistAsync(line: String, compactedLines: List<String>?) {
        val file = logFile ?: return
        writer.execute {
            runCatching {
                if (compactedLines != null) {
                    rewriteAtomically(file, compactedLines)
                } else {
                    OutputStreamWriter(
                        FileOutputStream(file, true),
                        Charsets.UTF_8
                    ).buffered().use { output ->
                        output.appendLine(line)
                    }
                }
            }
        }
    }

    private fun rewriteAtomically(file: File, compactedLines: List<String>) {
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(
                compactedLines
                    .joinToString(separator = "\n", postfix = "\n")
                    .toByteArray(Charsets.UTF_8)
            )
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }
}
