package com.deskcontrol

import android.view.View
import androidx.core.view.isVisible

/** Keeps the inline tuning panel geometry identical across control surfaces. */
class ControlSurfaceTuningPanelController(
    private val root: View,
    private val panel: View
) {
    var isExpanded: Boolean = false
        private set

    fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        if (expanded) {
            val density = root.resources.displayMetrics.density
            val maxHeightPx = (MAX_HEIGHT_DP * density).toInt()
            val availableHeightPx = (root.height * HEIGHT_FRACTION).toInt()
            panel.layoutParams = panel.layoutParams.apply {
                height = minOf(maxHeightPx, availableHeightPx).coerceAtLeast(
                    (MIN_HEIGHT_DP * density).toInt()
                )
            }
        }
        panel.isVisible = expanded
    }

    fun toggle() {
        setExpanded(!isExpanded)
    }

    companion object {
        private const val MAX_HEIGHT_DP = 280f
        private const val MIN_HEIGHT_DP = 120f
        private const val HEIGHT_FRACTION = 0.45f
    }
}
