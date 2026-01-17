package com.deskcontrol

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_preferences, rootKey)

        val darkMode = findPreference<SwitchPreferenceCompat>("pref_dark_mode")
        darkMode?.isChecked =
            SettingsStore.nightMode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        darkMode?.summary = if (darkMode?.isChecked == true) {
            getString(R.string.settings_on)
        } else {
            getString(R.string.settings_off)
        }
        darkMode?.setOnPreferenceChangeListener { preference, newValue ->
            val enabled = newValue as Boolean
            val mode = if (enabled) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            } else {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            }
            SettingsStore.setNightMode(requireContext(), mode)
            preference.summary = if (enabled) {
                getString(R.string.settings_on)
            } else {
                getString(R.string.settings_off)
            }
            true
        }

        bindListPreference(
            key = "pref_cursor_color",
            currentValue = if (SettingsStore.cursorColor == 0xFF000000.toInt()) "black" else "white"
        ) { value ->
            val color = if (value == "black") 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            SettingsStore.setCursorColor(requireContext(), color)
        }

        bindListPreference(
            key = "pref_cursor_size",
            currentValue = SettingsStore.cursorScale.toString()
        ) { value ->
            SettingsStore.setCursorScale(requireContext(), value.toFloat())
        }

        bindListPreference(
            key = "pref_cursor_opacity",
            currentValue = SettingsStore.cursorAlpha.toString()
        ) { value ->
            SettingsStore.setCursorAlpha(requireContext(), value.toFloat())
        }

        bindListPreference(
            key = "pref_cursor_speed",
            currentValue = TouchpadTuning.baseGain.toString()
        ) { value ->
            SettingsStore.setPointerSpeed(requireContext(), value.toFloat())
        }

        bindListPreference(
            key = "pref_cursor_hide",
            currentValue = SettingsStore.cursorHideDelayMs.toString()
        ) { value ->
            SettingsStore.setCursorHideDelay(requireContext(), value.toLong())
        }
    }

    private fun bindListPreference(
        key: String,
        currentValue: String,
        onChange: (String) -> Unit
    ) {
        val pref = findPreference<ListPreference>(key) ?: return
        pref.value = currentValue
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue as String
            onChange(value)
            true
        }
    }
}
