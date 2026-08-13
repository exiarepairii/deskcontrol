package com.deskcontrol

import android.content.Intent
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.deskcontrol.databinding.ActivityDiagnosticsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private var pendingDiagnosticsText = ""
    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        val saved = runCatching {
            contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                writer -> writer.write(pendingDiagnosticsText)
            } ?: error("Document provider returned no output stream")
        }
        if (saved.isSuccess) {
            DiagnosticsLog.add("Diagnostics: saved uriAuthority=${uri.authority ?: "unknown"}")
            Toast.makeText(this, R.string.diagnostics_save_logs_done, Toast.LENGTH_SHORT).show()
        } else {
            DiagnosticsLog.add(
                "Diagnostics: save failed exception=${saved.exceptionOrNull()?.javaClass?.simpleName}"
            )
            Toast.makeText(this, R.string.diagnostics_save_logs_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)
        binding.diagnosticsToolbar.title = getString(R.string.diagnostics_title)
        binding.diagnosticsToolbar.setNavigationOnClickListener { finish() }
        binding.diagnosticsToolbar.inflateMenu(R.menu.diagnostics_menu)
        binding.diagnosticsToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy_logs -> {
                    val clipboard =
                        getSystemService(android.content.ClipboardManager::class.java)
                    val text = buildDiagnosticsText()
                    binding.diagnosticsText.text = text
                    val clip = android.content.ClipData.newPlainText(
                        getString(R.string.diagnostics_logs_label),
                        text
                    )
                    clipboard?.setPrimaryClip(clip)
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.diagnostics_copy_logs_done),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                R.id.action_save_logs -> {
                    launchSaveLogsPicker()
                    true
                }
                else -> false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.diagnosticsText.text = buildDiagnosticsText()
    }

    private fun buildDiagnosticsText(): String {
        val displayInfo = DisplaySessionManager.getExternalDisplayInfo()
        val displayState = DisplaySessionManager.getSelectedDisplayState()
        val displayText = when {
            displayInfo != null -> getString(
                R.string.diagnostics_external_display_info,
                displayInfo.displayId,
                displayInfo.width,
                displayInfo.height,
                displayInfo.densityDpi,
                displayInfo.rotation
            )

            displayState == DisplaySessionManager.ExternalDisplayState.SUSPENDED -> getString(
                R.string.diagnostics_external_display_suspended,
                DisplaySessionManager.getSelectedDisplayId() ?: -1
            )

            else -> getString(R.string.diagnostics_external_display_not_connected)
        }
        val accessibility = getString(
            R.string.diagnostics_accessibility_state,
            ControlAccessibilityService.isConfigured(this),
            ControlAccessibilityService.isConnected(),
            ControlAccessibilityService.isReady()
        )
        val launchFailure = SessionStore.lastLaunchFailure ?: getString(R.string.diagnostics_none)
        val injectionResult = SessionStore.lastInjectionResult ?: getString(R.string.diagnostics_none)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val environment = DiagnosticsEnvironment.snapshot(this)
        val displaySnapshot = DisplayDiagnostics.currentSnapshot(this)
        val lastSeenDisplay = DisplaySessionManager.getLastSeenExternalDiagnostics()
        val logs = DiagnosticsLog.snapshot()

        return listOf(
            getString(
                R.string.diagnostics_app_info,
                packageInfo.versionName ?: "unknown",
                packageInfo.longVersionCode
            ),
            getString(
                R.string.diagnostics_device_info,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.VERSION.SDK_INT
            ),
            displayText,
            accessibility,
            getString(R.string.diagnostics_last_launch_failure, launchFailure),
            getString(R.string.diagnostics_last_injection_result, injectionResult),
            "",
            getString(R.string.diagnostics_environment_snapshot_label),
            environment.joinToString("\n"),
            "",
            getString(R.string.diagnostics_display_snapshot_label),
            displaySnapshot.joinToString("\n"),
            "",
            getString(R.string.diagnostics_last_seen_display_snapshot_label),
            if (lastSeenDisplay.isEmpty()) {
                getString(R.string.diagnostics_none)
            } else {
                lastSeenDisplay.joinToString("\n")
            },
            "",
            getString(R.string.diagnostics_logs_label),
            if (logs.isEmpty()) getString(R.string.diagnostics_logs_empty) else logs.joinToString("\n")
        ).joinToString("\n")
    }

    private fun launchSaveLogsPicker() {
        pendingDiagnosticsText = buildDiagnosticsText()
        binding.diagnosticsText.text = pendingDiagnosticsText
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "deskcontrol-diagnostics-$timestamp.txt")
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
                )
            )
        }
        runCatching { createLogDocument.launch(intent) }
            .onFailure {
                DiagnosticsLog.add(
                    "Diagnostics: file picker failed exception=${it.javaClass.simpleName}"
                )
                Toast.makeText(
                    this,
                    R.string.diagnostics_save_logs_picker_unavailable,
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}
