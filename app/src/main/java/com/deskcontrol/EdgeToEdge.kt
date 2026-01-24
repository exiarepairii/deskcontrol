package com.deskcontrol

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
