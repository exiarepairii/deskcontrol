package com.deskcontrol

import android.content.Intent
import android.os.Bundle
import android.animation.ObjectAnimator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.deskcontrol.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity(), DisplaySessionManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private var displayStatusNudge: ObjectAnimator? = null
    private var externalDisplayConnected = false
    private var externalDisplayState = DisplaySessionManager.ExternalDisplayState.NONE
    private var availableDisplays: List<DisplaySessionManager.ExternalDisplayInfo> = emptyList()
    private var selectedDisplayId: Int? = null
    private var lastSelectedDisplayId: Int? = null
    private var displaySelectionToast: android.widget.Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DiagnosticsLog.add("Main: create displayId=${display?.displayId ?: -1}")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)

        binding.btnPickApp.setOnClickListener {
            if (externalDisplayConnected) {
                startActivity(Intent(this, AppPickerActivity::class.java))
            } else {
                nudgeDisconnectedDisplayStatus()
                val messageRes = if (
                    externalDisplayState == DisplaySessionManager.ExternalDisplayState.SUSPENDED
                ) {
                    R.string.choose_app_wake_display_first
                } else {
                    R.string.choose_app_connect_display_first
                }
                android.widget.Toast.makeText(
                    this,
                    messageRes,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.btnTouchpad.setOnClickListener {
            startActivity(lastControlSurfaceIntent())
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        DisplaySessionManager.addListener(this)
        updateAccessibilityState()
    }

    override fun onStop() {
        super.onStop()
        DisplaySessionManager.removeListener(this)
    }

    override fun onDestroy() {
        displayStatusNudge?.cancel()
        displaySelectionToast?.cancel()
        super.onDestroy()
    }

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        externalDisplayState = DisplaySessionManager.getSelectedDisplayState()
        externalDisplayConnected = externalDisplayState ==
            DisplaySessionManager.ExternalDisplayState.ACTIVE && info != null
        binding.statusDisplayValue.text = when (externalDisplayState) {
            DisplaySessionManager.ExternalDisplayState.ACTIVE ->
                getString(R.string.external_display_connected)
            DisplaySessionManager.ExternalDisplayState.SUSPENDED ->
                getString(R.string.external_display_suspended)
            DisplaySessionManager.ExternalDisplayState.NONE ->
                getString(R.string.external_display_not_connected)
        }
        updateSecondaryActions()
    }

    override fun onDisplaysUpdated(
        displays: List<DisplaySessionManager.ExternalDisplayInfo>,
        selectedDisplayId: Int?
    ) {
        availableDisplays = displays
        this.selectedDisplayId = selectedDisplayId
        updateDisplaySelector()
        DisplaySessionManager.consumeSelectionNotice()?.let(::showDisplaySelectionNotice)
    }

    private fun showDisplaySelectionNotice(
        notice: DisplaySessionManager.SelectionNotice
    ) {
        val messageRes = when (notice.type) {
            DisplaySessionManager.SelectionNoticeType.EXCLUDED_UNAVAILABLE ->
                R.string.display_excluded_unavailable
            DisplaySessionManager.SelectionNoticeType.DETECTED_UNAVAILABLE ->
                R.string.display_detected_unavailable
        }
        val duration = when (notice.type) {
            DisplaySessionManager.SelectionNoticeType.EXCLUDED_UNAVAILABLE ->
                android.widget.Toast.LENGTH_SHORT
            DisplaySessionManager.SelectionNoticeType.DETECTED_UNAVAILABLE ->
                android.widget.Toast.LENGTH_LONG
        }
        displaySelectionToast?.cancel()
        displaySelectionToast = android.widget.Toast.makeText(this, messageRes, duration).also {
            it.show()
        }
    }

    private fun updateAccessibilityState() {
        val accessibilityEnabled = ControlAccessibilityService.isConfigured(this)
        binding.statusAccessibilityValue.text = if (accessibilityEnabled) {
            getString(R.string.accessibility_enabled)
        } else {
            getString(R.string.accessibility_required)
        }
    }

    private fun updateSecondaryActions() {
        binding.btnTouchpad.isEnabled = true
        binding.btnTouchpad.alpha = 1f
    }

    private fun nudgeDisconnectedDisplayStatus() {
        displayStatusNudge?.cancel()
        binding.statusDisplaySection.translationX = 0f
        val offset = dpToPx(7).toFloat()
        displayStatusNudge = ObjectAnimator.ofFloat(
            binding.statusDisplaySection,
            android.view.View.TRANSLATION_X,
            0f,
            -offset,
            offset,
            -offset * 0.65f,
            offset * 0.65f,
            0f
        ).apply {
            duration = 420L
            interpolator = FastOutSlowInInterpolator()
            start()
        }
    }

    private fun updateDisplaySelector() {
        val showSelector = availableDisplays.isNotEmpty()
        binding.displaySelector.isVisible = showSelector
        if (!showSelector) return

        val items = availableDisplays.take(3)
        binding.displaySelectorRow.removeAllViews()
        val selectedPrimary = MaterialColors.getColor(
            binding.displaySelectorRow,
            com.google.android.material.R.attr.colorOnSurface,
            0
        )
        val unselectedPrimary = MaterialColors.getColor(
            binding.displaySelectorRow,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0
        )
        val secondaryColor = MaterialColors.getColor(
            binding.displaySelectorRow,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0
        )
        items.forEachIndexed { index, display ->
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { DisplaySessionManager.setSelectedDisplayId(display.displayId) }
            }
            val primary = MaterialTextView(this).apply {
                text = getString(R.string.display_selector_title, index + 1)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                gravity = android.view.Gravity.CENTER
            }
            val secondary = MaterialTextView(this).apply {
                text = getString(R.string.display_selector_resolution, display.width, display.height)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                setTextColor(secondaryColor)
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                gravity = android.view.Gravity.CENTER
            }
            val textParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(primary, textParams)
            container.addView(secondary, textParams)
            container.setPadding(0, dpToPx(6), 0, dpToPx(6))
            val params = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
            binding.displaySelectorRow.addView(container, params)
            primary.setTextColor(
                if (display.displayId == selectedDisplayId) selectedPrimary else unselectedPrimary
            )
        }

        binding.displaySelector.doOnLayout {
            if (items.isEmpty()) return@doOnLayout
            val contentWidth = it.width - it.paddingStart - it.paddingEnd
            val segmentWidth = contentWidth / items.size
            val highlightParams = binding.displaySelectorHighlight.layoutParams
            if (highlightParams.width != segmentWidth) {
                highlightParams.width = segmentWidth
                binding.displaySelectorHighlight.layoutParams = highlightParams
            }
            val selectedIndex = items.indexOfFirst { display ->
                display.displayId == selectedDisplayId
            }.coerceAtLeast(0)
            val targetX = segmentWidth * selectedIndex.toFloat()
            binding.displaySelectorHighlight.animate().cancel()
            if (lastSelectedDisplayId == null) {
                binding.displaySelectorHighlight.translationX = targetX
            } else {
                ObjectAnimator.ofFloat(
                    binding.displaySelectorHighlight,
                    "translationX",
                    binding.displaySelectorHighlight.translationX,
                    targetX
                ).apply {
                    duration = 160
                    interpolator = FastOutSlowInInterpolator()
                }.start()
            }
            lastSelectedDisplayId = selectedDisplayId
        }
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
