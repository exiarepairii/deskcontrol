package com.deskcontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment

class SettingsOverviewFragment : Fragment() {

    private lateinit var pageLayout: SettingsPageLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        pageLayout = SettingsPageLayout(requireContext())
        return pageLayout.root
    }

    override fun onResume() {
        super.onResume()
        buildOverview()
    }

    private fun buildOverview() {
        val context = requireContext()
        pageLayout.content.removeAllViews()

        pageLayout.addGroup(
            R.string.settings_overview_personalization,
            context.navigationRow(
                R.drawable.ic_settings_appearance,
                R.string.settings_appearance_title,
                appearanceSummary()
            ) { open(SettingsPage.APPEARANCE) },
            context.navigationRow(
                R.drawable.ic_settings_display,
                R.string.settings_display_section,
                context.getString(
                    R.string.settings_overview_display_summary,
                    stateLabel(SettingsStore.keepScreenOn),
                    stateLabel(SettingsStore.touchpadAutoDimEnabled)
                )
            ) { open(SettingsPage.DISPLAY) }
        )

        val pinnedCount = SwitchBarStore.getFavoriteSlots(context).count { !it.isNullOrBlank() }
        pageLayout.addGroup(
            R.string.settings_overview_controls,
            context.navigationRow(
                R.drawable.ic_settings_dock,
                R.string.settings_switch_bar_section,
                resources.getQuantityString(
                    R.plurals.settings_overview_dock_summary,
                    pinnedCount,
                    stateLabel(SettingsStore.switchBarEnabled),
                    pinnedCount
                )
            ) { open(SettingsPage.DOCK) },
            context.navigationRow(
                R.drawable.ic_settings_touchpad,
                R.string.settings_touchpad_section,
                context.getString(R.string.settings_overview_touchpad_summary)
            ) { open(SettingsPage.TOUCHPAD) },
            context.navigationRow(
                R.drawable.ic_settings_ray_mouse,
                R.string.settings_ray_mouse_section,
                context.getString(R.string.settings_overview_ray_mouse_summary)
            ) { open(SettingsPage.RAY_MOUSE) },
            context.navigationRow(
                R.drawable.ic_settings_cursor,
                R.string.settings_cursor_section,
                cursorSummary()
            ) { open(SettingsPage.CURSOR) }
        )

        pageLayout.addGroup(
            R.string.settings_overview_other,
            context.navigationRow(
                R.drawable.ic_settings_developer,
                R.string.settings_developer_section,
                context.getString(R.string.settings_overview_developer_summary)
            ) { open(SettingsPage.DEVELOPER) }
        )
    }

    private fun appearanceSummary(): String {
        val theme = when (SettingsStore.nightMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> getString(R.string.theme_dark)
            AppCompatDelegate.MODE_NIGHT_NO -> getString(R.string.theme_light)
            else -> getString(R.string.theme_system)
        }
        val language = when {
            SettingsStore.isLanguageEnglish() -> getString(R.string.language_english)
            SettingsStore.isLanguageChinese() -> getString(R.string.language_chinese)
            else -> getString(R.string.language_system)
        }
        return getString(R.string.settings_overview_appearance_summary, theme, language)
    }

    private fun cursorSummary(): String {
        val color = if (SettingsStore.cursorColor == 0xFF000000.toInt()) {
            getString(R.string.cursor_color_black)
        } else {
            getString(R.string.cursor_color_white)
        }
        return getString(
            R.string.settings_overview_cursor_summary,
            color,
            SettingsStore.cursorScale
        )
    }

    private fun stateLabel(enabled: Boolean): String =
        getString(if (enabled) R.string.settings_on else R.string.settings_off)

    private fun open(page: SettingsPage) {
        (activity as? SettingsNavigator)?.openSettingsPage(page)
    }
}
