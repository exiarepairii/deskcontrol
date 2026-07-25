package com.deskcontrol

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar

internal class SettingsActivity : AppCompatActivity(), SettingsNavigator {

    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(findViewById(R.id.settingsRoot))

        toolbar = findViewById(R.id.settingsToolbar)
        toolbar.setNavigationOnClickListener { navigateBack() }
        supportFragmentManager.addOnBackStackChangedListener(::updateToolbar)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = navigateBack()
            }
        )

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsOverviewFragment())
                .commit()
        }
        updateToolbar()
    }

    override fun openSettingsPage(page: SettingsPage) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, page.createFragment())
            .addToBackStack(page.name)
            .commit()
    }

    private fun navigateBack() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
    }

    private fun updateToolbar() {
        val current = supportFragmentManager.findFragmentById(R.id.settingsContainer)
        toolbar.title = if (current is SettingsPageOwner) {
            getString(current.settingsPage.titleRes)
        } else {
            getString(R.string.settings_title)
        }
    }

    override fun onPause() {
        super.onPause()
        ControlAccessibilityService.requestSwitchBarForceVisible(false)
        ControlAccessibilityService.requestCursorForceVisible(false)
    }
}

internal interface SettingsNavigator {
    fun openSettingsPage(page: SettingsPage)
}

internal interface SettingsPageOwner {
    val settingsPage: SettingsPage
}

internal enum class SettingsPage(val titleRes: Int) {
    APPEARANCE(R.string.settings_appearance_title),
    DISPLAY(R.string.settings_display_section),
    DOCK(R.string.settings_switch_bar_section),
    TOUCHPAD(R.string.settings_touchpad_section),
    RAY_MOUSE(R.string.settings_ray_mouse_section),
    CURSOR(R.string.settings_cursor_section),
    DEVELOPER(R.string.settings_developer_section);

    fun createFragment() = when (this) {
        APPEARANCE -> AppearanceSettingsFragment()
        DISPLAY -> DisplaySettingsFragment()
        DOCK -> DockSettingsFragment()
        TOUCHPAD -> TouchpadSettingsFragment()
        RAY_MOUSE -> RayMouseSettingsFragment()
        CURSOR -> CursorSettingsFragment()
        DEVELOPER -> DeveloperSettingsFragment()
    }
}
