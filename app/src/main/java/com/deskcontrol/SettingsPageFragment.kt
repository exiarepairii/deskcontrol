package com.deskcontrol

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

internal abstract class SettingsPageFragment(
    final override val settingsPage: SettingsPage
) : Fragment(), SettingsPageOwner {

    protected lateinit var pageLayout: SettingsPageLayout

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        pageLayout = SettingsPageLayout(requireContext())
        buildPage()
        return pageLayout.root
    }

    protected abstract fun buildPage()
}
