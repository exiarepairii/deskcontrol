package com.deskcontrol

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding

fun applyEdgeToEdgePadding(view: View, includeTop: Boolean = true) {
    val initialLeft = view.paddingLeft
    val initialTop = view.paddingTop
    val initialRight = view.paddingRight
    val initialBottom = view.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val systemInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = initialLeft + systemInsets.left,
            top = initialTop + if (includeTop) systemInsets.top else 0,
            right = initialRight + systemInsets.right,
            bottom = initialBottom + systemInsets.bottom
        )
        insets
    }
}

@Suppress("DEPRECATION")
fun AppCompatActivity.configureOledControlSurfaceWindow(root: View, toolbar: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    applyEdgeToEdgePadding(root, includeTop = false)

    val initialToolbarTop = toolbar.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
        val systemInsets = insets.getInsetsIgnoringVisibility(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(top = initialToolbarTop + systemInsets.top)
        insets
    }

    WindowInsetsControllerCompat(window, root).apply {
        hide(WindowInsetsCompat.Type.statusBars())
        systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }

    val background = ContextCompat.getColor(this, R.color.touchpadBackground)
    root.setBackgroundColor(background)
    window.statusBarColor = background
    window.navigationBarColor = background
    window.isNavigationBarContrastEnforced = false
}
