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
    private var externalDisplayConnected = false
    private var availableDisplays: List<DisplaySessionManager.ExternalDisplayInfo> = emptyList()
    private var selectedDisplayId: Int? = null
    private var lastSelectedDisplayId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeToEdgePadding(binding.root)

        binding.btnPickApp.setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        binding.btnTouchpad.setOnClickListener {
            startActivity(Intent(this, TouchpadActivity::class.java))
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

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        externalDisplayConnected = info != null
        binding.statusDisplayValue.text = if (externalDisplayConnected) {
            getString(R.string.external_display_connected)
        } else {
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
    }

    private fun updateAccessibilityState() {
        val accessibilityEnabled = ControlAccessibilityService.isEnabled(this)
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
