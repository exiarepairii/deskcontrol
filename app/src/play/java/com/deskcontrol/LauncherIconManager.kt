package com.deskcontrol

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object LauncherIconManager {
    enum class ApplyResult {
        APPLIED,
        LOCKED,
        FAILED
    }

    enum class Icon(val persistedValue: String, val aliasSuffix: String) {
        DEFAULT("default", ".launcher.DefaultLauncher"),
        WHITE("white", ".launcher.WhiteLauncher"),
        GOLD("gold", ".launcher.GoldLauncher")
    }

    private const val PREFS = "launcher_icon"
    private const val KEY_SELECTED = "selected"

    fun selected(context: Context): Icon {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, Icon.DEFAULT.persistedValue)
        return Icon.entries.firstOrNull { it.persistedValue == value } ?: Icon.DEFAULT
    }

    fun apply(context: Context, requested: Icon): ApplyResult {
        if (requested != Icon.DEFAULT && !PlayEntitlements.hasPremiumAccess(context)) {
            return ApplyResult.LOCKED
        }
        val pm = context.packageManager
        val components = Icon.entries.associateWith { icon ->
            ComponentName(
                context.packageName,
                context.packageName + icon.aliasSuffix
            )
        }
        val applied = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.setComponentEnabledSettings(
                    components.map { (icon, component) ->
                        PackageManager.ComponentEnabledSetting(
                            component,
                            enabledState(icon == requested),
                            PackageManager.DONT_KILL_APP
                        )
                    }
                )
            } else {
                // Keep at least one launcher enabled throughout the non-atomic legacy path.
                pm.setComponentEnabledSetting(
                    components.getValue(requested),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                components
                    .filterKeys { it != requested }
                    .forEach { (_, component) ->
                        pm.setComponentEnabledSetting(
                            component,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                    }
            }
        }.isSuccess
        if (!applied) return ApplyResult.FAILED

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, requested.persistedValue)
            .apply()
        return ApplyResult.APPLIED
    }

    private fun enabledState(enabled: Boolean): Int =
        if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
}
