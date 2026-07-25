package com.deskcontrol

import android.content.Intent

internal class DeveloperSettingsFragment :
    SettingsPageFragment(SettingsPage.DEVELOPER) {

    override fun buildPage() {
        val context = requireContext()
        val logs = context.actionRow(
            R.string.settings_logs_title,
            R.string.settings_logs_summary
        ) {
            startActivity(Intent(context, DiagnosticsActivity::class.java))
        }
        pageLayout.addGroup(
            R.string.settings_developer_tools_group,
            logs
        )
    }
}
