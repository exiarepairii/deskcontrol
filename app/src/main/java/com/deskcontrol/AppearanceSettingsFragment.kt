package com.deskcontrol

import android.view.View
import androidx.appcompat.app.AppCompatDelegate

internal class AppearanceSettingsFragment :
    SettingsPageFragment(SettingsPage.APPEARANCE) {

    override fun buildPage() {
        val context = requireContext()
        val themeSystemId = View.generateViewId()
        val themeDarkId = View.generateViewId()
        val themeLightId = View.generateViewId()
        val (themeRow, themeGroup) = context.segmentedSetting(
            R.string.settings_theme,
            listOf(
                themeSystemId to R.string.theme_system,
                themeDarkId to R.string.theme_dark,
                themeLightId to R.string.theme_light
            )
        )
        themeGroup.check(
            when (SettingsStore.nightMode) {
                AppCompatDelegate.MODE_NIGHT_YES -> themeDarkId
                AppCompatDelegate.MODE_NIGHT_NO -> themeLightId
                else -> themeSystemId
            }
        )
        themeGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                themeDarkId -> AppCompatDelegate.MODE_NIGHT_YES
                themeLightId -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode != SettingsStore.nightMode) {
                SettingsStore.setNightMode(context, mode)
                requireActivity().recreate()
            }
        }

        val languageSystemId = View.generateViewId()
        val languageEnglishId = View.generateViewId()
        val languageChineseId = View.generateViewId()
        val (languageRow, languageGroup) = context.segmentedSetting(
            R.string.settings_language,
            listOf(
                languageSystemId to R.string.language_system,
                languageEnglishId to R.string.language_english,
                languageChineseId to R.string.language_chinese
            )
        )
        languageGroup.check(
            when {
                SettingsStore.isLanguageEnglish() -> languageEnglishId
                SettingsStore.isLanguageChinese() -> languageChineseId
                else -> languageSystemId
            }
        )
        languageGroup.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val oldLanguage = SettingsStore.appLanguageTag
            when (checkedId) {
                languageEnglishId -> SettingsStore.setAppLanguage(context, "en")
                languageChineseId -> SettingsStore.setAppLanguage(context, "zh-CN")
                else -> SettingsStore.setAppLanguage(context, "system")
            }
            if (oldLanguage != SettingsStore.appLanguageTag) {
                requireActivity().recreate()
            }
        }

        pageLayout.addGroup(
            R.string.settings_appearance_group,
            themeRow,
            languageRow
        )
    }
}
