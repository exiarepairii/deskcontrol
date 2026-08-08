package com.deskcontrol

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import rikka.shizuku.Shizuku

/** Shared accessibility gate, including manual settings and optional Shizuku enablement. */
class AccessibilityGateController(
    private val activity: AppCompatActivity,
    private val gate: View,
    private val content: View,
    private val controlArea: View,
    private val tuningPanel: View,
    private val openSettingsButton: View,
    private val advancedEnableButton: View,
    private val onEnabledChanged: (Boolean) -> Unit
) {
    private var shizukuBinderReady = false
    private var shizukuEnableInFlight = false
    private var destroyed = false

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        shizukuBinderReady = true
        updateShizukuButton()
    }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        shizukuBinderReady = false
        updateShizukuButton()
    }
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST) {
                return@OnRequestPermissionResultListener
            }
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                enableAccessibilityWithShizuku()
            } else {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.touchpad_shizuku_permission_denied),
                    Toast.LENGTH_SHORT
                ).show()
                updateShizukuButton()
            }
        }

    init {
        openSettingsButton.setOnClickListener {
            AccessibilityDisclosure.showIfNeeded(activity) {
                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        advancedEnableButton.setOnClickListener {
            AccessibilityDisclosure.showIfNeeded(activity) {
                requestAccessibilityViaShizuku()
            }
        }
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        refreshShizukuBinderState()
        updateShizukuButton()
    }

    fun onStart() {
        refreshShizukuBinderState()
        refresh()
    }

    fun refresh() {
        val enabled = ControlAccessibilityService.isEnabled(activity)
        gate.isVisible = !enabled
        content.alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA
        controlArea.isEnabled = enabled
        tuningPanel.isEnabled = enabled
        onEnabledChanged(enabled)
        updateShizukuButton()
    }

    fun onDestroy() {
        destroyed = true
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun updateShizukuButton() {
        if (destroyed) return
        refreshShizukuBinderState()
        advancedEnableButton.alpha = if (isShizukuAvailable()) 1f else 0.5f
        advancedEnableButton.isEnabled = !shizukuEnableInFlight
    }

    private fun refreshShizukuBinderState() {
        shizukuBinderReady = shizukuBinderReady || isShizukuBinderAlive()
    }

    private fun requestAccessibilityViaShizuku() {
        if (!isShizukuAvailable()) {
            showShizukuIntroDialog()
            return
        }
        val permission = try {
            Shizuku.checkSelfPermission()
        } catch (e: Throwable) {
            DiagnosticsLog.add("Shizuku: not running (${e.javaClass.simpleName})")
            showShizukuIntroDialog()
            return
        }
        if (permission == PackageManager.PERMISSION_GRANTED) {
            enableAccessibilityWithShizuku()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.touchpad_shizuku_permission_rationale),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        } catch (e: Throwable) {
            DiagnosticsLog.add("Shizuku: requestPermission failed ${e.javaClass.simpleName}")
            showShizukuIntroDialog()
        }
    }

    private fun enableAccessibilityWithShizuku() {
        if (shizukuEnableInFlight) return
        shizukuEnableInFlight = true
        updateShizukuButton()
        Thread {
            val success = enableAccessibilityWithShizukuInternal()
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                shizukuEnableInFlight = false
                updateShizukuButton()
                Toast.makeText(
                    activity,
                    activity.getString(
                        if (success) {
                            R.string.touchpad_shizuku_enable_success
                        } else {
                            R.string.touchpad_shizuku_enable_failed
                        }
                    ),
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        }.start()
    }

    private fun enableAccessibilityWithShizukuInternal(): Boolean {
        val component = ComponentName(activity, ControlAccessibilityService::class.java)
            .flattenToString()
        val current = Settings.Secure.getString(
            activity.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val updated = mergeAccessibilityServices(current, component)
        val setServices = runShizukuCommand(
            arrayOf(
                "settings",
                "put",
                "secure",
                "enabled_accessibility_services",
                updated
            )
        )
        if (setServices.exitCode != 0) {
            DiagnosticsLog.add(
                "Shizuku: enable services failed " +
                    "code=${setServices.exitCode} err=${setServices.error}"
            )
            return false
        }
        val enable = runShizukuCommand(
            arrayOf("settings", "put", "secure", "accessibility_enabled", "1")
        )
        if (enable.exitCode != 0) {
            DiagnosticsLog.add(
                "Shizuku: enable accessibility flag failed " +
                    "code=${enable.exitCode} err=${enable.error}"
            )
            return false
        }
        SystemClock.sleep(SHIZUKU_ENABLE_SETTLE_MS)
        return ControlAccessibilityService.isEnabled(activity)
    }

    private fun mergeAccessibilityServices(current: String?, component: String): String {
        if (current.isNullOrBlank() || current == "null") return component
        val entries = current.split(":").filter(String::isNotBlank)
        if (entries.contains(component)) return entries.joinToString(":")
        return (entries + component).joinToString(":")
    }

    private fun runShizukuCommand(args: Array<String>): ShizukuCommandResult {
        return try {
            val process = newShizukuProcess(args)
                ?: return ShizukuCommandResult(-1, "", "newProcess unavailable")
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val error = process.errorStream.bufferedReader().use { it.readText() }.trim()
            ShizukuCommandResult(process.waitFor(), output, error)
        } catch (e: Exception) {
            ShizukuCommandResult(-1, "", e.message ?: "unknown")
        }
    }

    private fun isShizukuBinderAlive(): Boolean {
        return try {
            val method = Shizuku::class.java.declaredMethods.firstOrNull { candidate ->
                (candidate.name == "pingBinder" || candidate.name == "isBinderAlive") &&
                    candidate.parameterTypes.isEmpty()
            } ?: return false
            method.isAccessible = true
            (method.invoke(null) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun showShizukuIntroDialog() {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.touchpad_shizuku_intro_title)
            .setMessage(activity.getString(R.string.touchpad_shizuku_intro_message))
            .setPositiveButton(R.string.touchpad_shizuku_intro_ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun newShizukuProcess(args: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.declaredMethods.firstOrNull { candidate ->
                candidate.name == "newProcess" && candidate.parameterTypes.size == 3
            } ?: return null
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            method.invoke(null, args, null, null) as? Process
        } catch (_: Exception) {
            null
        }
    }

    private data class ShizukuCommandResult(
        val exitCode: Int,
        val output: String,
        val error: String
    )

    private companion object {
        const val DISABLED_CONTENT_ALPHA = 0.35f
        const val SHIZUKU_ENABLE_SETTLE_MS = 150L
        const val SHIZUKU_PERMISSION_REQUEST = 1201
    }
}
