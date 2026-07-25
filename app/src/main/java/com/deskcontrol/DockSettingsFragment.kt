package com.deskcontrol

import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible

internal class DockSettingsFragment :
    SettingsPageFragment(SettingsPage.DOCK) {

    private var labelMap: Map<String, String> = emptyMap()
    private var iconMap: Map<String, Drawable> = emptyMap()
    private var slotIcons: List<ImageView> = emptyList()

    private val pickApp =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val packageName =
                data.getStringExtra(AppPickerActivity.EXTRA_PICK_PACKAGE)
                    ?: return@registerForActivityResult
            val slotIndex = data.getIntExtra(AppPickerActivity.EXTRA_PICK_SLOT, -1)
            if (slotIndex !in 0..2) return@registerForActivityResult
            SwitchBarStore.setFavoriteSlot(requireContext(), slotIndex, packageName)
            refreshSlots()
            ControlAccessibilityService.requestSwitchBarRefresh()
        }

    override fun buildPage() {
        val context = requireContext()
        val apps = LaunchableAppCatalog.load(context)
        labelMap = apps.associate { it.packageName to it.label }
        iconMap = apps.associate { it.packageName to it.icon }

        val enabled = context.switchSetting(
            R.string.settings_switch_bar_enabled,
            R.string.settings_switch_bar_hint
        )
        val scale = context.sliderSetting(R.string.settings_switch_bar_scale)
        bindSlider(
            scale,
            range = SettingsSliderRanges.DOCK_SCALE,
            currentValue = SettingsStore.switchBarScale,
            formatValue = {
                getString(R.string.settings_switch_bar_scale_value, (it * 100).toInt())
            }
        ) {
            SettingsStore.setSwitchBarScale(context, it)
            showPreview()
        }
        scale.slider.addOnSliderTouchListener(previewTouchListener(::showPreview))

        val (slotsRow, icons) = context.appSlotsRow()
        slotIcons = icons
        slotIcons.forEachIndexed { index, imageView ->
            (imageView.parent as? android.view.View)?.setOnClickListener {
                showPreview()
                pickApp.launch(
                    Intent(context, AppPickerActivity::class.java).apply {
                        putExtra(AppPickerActivity.EXTRA_PICK_MODE, true)
                        putExtra(
                            AppPickerActivity.EXTRA_PICK_TITLE,
                            getString(R.string.settings_switch_bar_pick_title, index + 1)
                        )
                        putExtra(AppPickerActivity.EXTRA_PICK_SLOT, index)
                    }
                )
            }
        }
        refreshSlots()

        val updateVisibility: (Boolean) -> Unit = { visible ->
            scale.row.isVisible = visible
            slotsRow.isVisible = visible
        }
        enabled.switch.isChecked = SettingsStore.switchBarEnabled
        updateVisibility(enabled.switch.isChecked)
        enabled.switch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.setSwitchBarEnabled(context, checked)
            updateVisibility(checked)
            if (checked) showPreview()
        }

        pageLayout.addGroup(
            R.string.settings_dock_behavior_group,
            enabled.row,
            scale.row,
            slotsRow
        )
    }

    private fun refreshSlots() {
        if (slotIcons.isEmpty()) return
        val slots = SwitchBarStore.getFavoriteSlots(requireContext())
        slotIcons.forEachIndexed { index, imageView ->
            val packageName = slots.getOrNull(index)
            if (packageName.isNullOrBlank()) {
                imageView.setImageResource(R.drawable.ic_add)
                imageView.contentDescription =
                    getString(R.string.settings_switch_bar_app_add_slot, index + 1)
            } else {
                val icon = iconMap[packageName]
                if (icon != null) imageView.setImageDrawable(icon)
                else imageView.setImageResource(R.drawable.ic_add)
                imageView.contentDescription = labelMap[packageName] ?: packageName
            }
        }
    }

    private fun showPreview() {
        ControlAccessibilityService.requestSwitchBarForceVisible(true)
    }

    override fun onPause() {
        super.onPause()
        ControlAccessibilityService.requestSwitchBarForceVisible(false)
    }
}
