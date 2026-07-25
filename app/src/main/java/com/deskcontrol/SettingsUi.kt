package com.deskcontrol

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textview.MaterialTextView
import kotlin.math.min
import kotlin.math.round

internal class SettingsPageLayout(context: Context) {
    private val contextRef = context
    val root: ScrollView
    val content: LinearLayout

    init {
        root = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val frame = FrameLayout(context)
        root.addView(
            frame,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }
        val widthDp = min(context.resources.configuration.screenWidthDp, 680)
        frame.addView(
            content,
            FrameLayout.LayoutParams(
                if (context.resources.configuration.screenWidthDp > 680) dp(widthDp) else {
                    ViewGroup.LayoutParams.MATCH_PARENT
                },
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
        )
    }

    fun addGroup(@StringRes titleRes: Int, vararg rows: View): LinearLayout {
        val title = MaterialTextView(content.context).apply {
            setText(titleRes)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        content.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (content.childCount == 0) dp(8) else dp(24)
                marginStart = dp(8)
                marginEnd = dp(8)
                bottomMargin = dp(8)
            }
        )

        val card = MaterialCardView(content.context).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainer))
        }
        val holder = SettingsGroupLayout(content.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(
            holder,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        rows.forEachIndexed { index, row ->
            if (index > 0) holder.addView(divider(content.context))
            holder.addView(row)
        }
        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return holder
    }

    private fun divider(context: Context) = View(context).apply {
        tag = SettingsGroupLayout.DIVIDER_TAG
        setBackgroundColor(attrColor(com.google.android.material.R.attr.colorOutlineVariant))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            marginStart = dp(64)
            marginEnd = dp(16)
        }
    }

    private fun dp(value: Int): Int = contextRef.dp(value)

    private fun attrColor(attr: Int): Int = contextRef.attrColor(attr)
}

private class SettingsGroupLayout(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var hasVisibleRow = false
        var pendingDivider: View? = null
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.tag == DIVIDER_TAG) {
                child.visibility = View.GONE
                pendingDivider = child
            } else if (child.visibility != View.GONE) {
                pendingDivider?.visibility = if (hasVisibleRow) View.VISIBLE else View.GONE
                pendingDivider = null
                hasVisibleRow = true
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        const val DIVIDER_TAG = "settings_group_divider"
    }
}

internal data class SwitchSetting(val row: View, val switch: SwitchMaterial)

internal data class SliderSetting(
    val row: View,
    val slider: Slider,
    val value: TextView
)

internal data class ColorSetting(
    val row: View,
    val black: MaterialCardView,
    val white: MaterialCardView,
    val blackTarget: View,
    val whiteTarget: View
)

internal fun Context.navigationRow(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    summary: CharSequence,
    onClick: () -> Unit
): View {
    val row = horizontalRow(minHeight = 80).apply {
        isClickable = true
        isFocusable = true
        foreground = selectableItemBackground()
        setOnClickListener { onClick() }
    }
    val iconContainer = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
        }
    }
    val icon = ImageView(this).apply {
        setImageResource(iconRes)
        ImageViewCompat.setImageTintList(
            this,
            ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        )
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    iconContainer.addView(
        icon,
        FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
    )
    row.addView(
        iconContainer,
        LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginEnd = dp(16)
        }
    )
    row.addView(
        labelColumn(titleRes, summary),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    row.addView(
        ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            )
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },
        LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            marginStart = dp(12)
        }
    )
    row.contentDescription = "${getString(titleRes)}, $summary"
    return row
}

internal fun Context.actionRow(
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    showChevron: Boolean = true,
    onClick: () -> Unit
): View {
    val row = horizontalRow(minHeight = 76).apply {
        isClickable = true
        isFocusable = true
        foreground = selectableItemBackground()
        setOnClickListener { onClick() }
    }
    row.addView(
        labelColumn(titleRes, getString(summaryRes)),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    if (showChevron) {
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                ImageViewCompat.setImageTintList(
                    this,
                    ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                )
            },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) }
        )
    }
    return row
}

internal fun Context.switchSetting(
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int? = null
): SwitchSetting {
    val row = horizontalRow(minHeight = if (summaryRes == null) 64 else 76)
    val summary = summaryRes?.let(::getString).orEmpty()
    row.addView(
        labelColumn(titleRes, summary),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    val switch = SwitchMaterial(this).apply {
        isClickable = false
        isFocusable = false
    }
    row.addView(
        switch,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(12) }
    )
    row.isClickable = true
    row.isFocusable = true
    row.foreground = selectableItemBackground()
    row.setOnClickListener {
        if (switch.isEnabled) switch.isChecked = !switch.isChecked
    }
    return SwitchSetting(row, switch)
}

internal fun Context.sliderSetting(
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int? = null
): SliderSetting {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(8))
        minimumHeight = dp(96)
    }
    val titleLine = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    titleLine.addView(
        text(titleRes, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    val value = MaterialTextView(this).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        gravity = Gravity.END
    }
    titleLine.addView(
        value,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(16) }
    )
    row.addView(titleLine)
    if (summaryRes != null) {
        row.addView(
            text(
                summaryRes,
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                com.google.android.material.R.attr.colorOnSurfaceVariant
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        )
    }
    val slider = Slider(this).apply {
        isTickVisible = false
    }
    row.addView(
        slider,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) }
    )
    return SliderSetting(row, slider, value)
}

internal fun Context.segmentedSetting(
    @StringRes titleRes: Int,
    options: List<Pair<Int, Int>>
): Pair<View, MaterialButtonToggleGroup> {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }
    row.addView(
        text(titleRes, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
    )
    val group = MaterialButtonToggleGroup(this).apply {
        isSingleSelection = true
        isSelectionRequired = true
        orientation = LinearLayout.HORIZONTAL
    }
    options.forEach { (id, labelRes) ->
        group.addView(
            MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                this.id = id
                setText(labelRes)
                isAllCaps = false
                minWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
    }
    row.addView(
        group,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    )
    return row to group
}

internal fun Context.appSlotsRow(): Pair<View, List<ImageView>> {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }
    row.addView(
        text(
            R.string.settings_switch_bar_apps,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
        )
    )
    val slots = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val slotBackground = androidx.core.content.ContextCompat.getColor(this, R.color.surfaceGlass)
    val slotStroke = attrColor(com.google.android.material.R.attr.colorOutlineVariant)
    val icons = mutableListOf<ImageView>()
    repeat(3) { index ->
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = slotStroke
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setCardBackgroundColor(slotBackground)
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        card.addView(
            icon,
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
        )
        slots.addView(
            card,
            LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                if (index > 0) marginStart = dp(8)
            }
        )
        icons += icon
    }
    row.addView(
        slots,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    )
    return row to icons
}

internal fun Context.cursorColorSetting(): ColorSetting {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }
    row.addView(
        text(
            R.string.settings_cursor_color,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
        )
    )
    val choices = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    fun choice(
        color: Int,
        @StringRes labelRes: Int
    ): Pair<FrameLayout, MaterialCardView> {
        val target = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            contentDescription = getString(labelRes)
            foreground = selectableItemBackground()
        }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = attrColor(com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(color)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        target.addView(
            card,
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        )
        return target to card
    }

    val (blackTarget, black) = choice(Color.BLACK, R.string.cursor_color_black)
    val (whiteTarget, white) = choice(Color.WHITE, R.string.cursor_color_white)
    choices.addView(
        blackTarget,
        LinearLayout.LayoutParams(dp(48), dp(48))
    )
    choices.addView(
        whiteTarget,
        LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) }
    )
    row.addView(
        choices,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    )
    return ColorSetting(row, black, white, blackTarget, whiteTarget)
}

internal fun bindSlider(
    setting: SliderSetting,
    range: SettingsSliderRange,
    currentValue: Float,
    formatValue: (Float) -> CharSequence,
    onChanged: (Float) -> Unit
) {
    bindSlider(
        setting = setting,
        valueFrom = range.start,
        valueTo = range.end,
        stepSize = range.step,
        currentValue = currentValue,
        formatValue = formatValue,
        onChanged = onChanged
    )
}

internal fun bindSlider(
    setting: SliderSetting,
    valueFrom: Float,
    valueTo: Float,
    stepSize: Float,
    currentValue: Float,
    formatValue: (Float) -> CharSequence,
    onChanged: (Float) -> Unit
) {
    val slider = setting.slider
    slider.valueFrom = valueFrom
    slider.valueTo = valueTo
    slider.stepSize = stepSize
    slider.value = snapSettingValue(currentValue, valueFrom, valueTo, stepSize)
    setting.value.text = formatValue(slider.value)
    slider.addOnChangeListener { _, rawValue, fromUser ->
        val value = snapSettingValue(rawValue, valueFrom, valueTo, stepSize)
        setting.value.text = formatValue(value)
        if (fromUser) {
            if (value != rawValue) slider.value = value
            onChanged(value)
        }
    }
}

internal fun previewTouchListener(onStart: () -> Unit) =
    object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) = onStart()
        override fun onStopTrackingTouch(slider: Slider) = Unit
    }

internal fun snapSettingValue(
    value: Float,
    start: Float,
    end: Float,
    step: Float
): Float {
    val steps = round((value.coerceIn(start, end) - start) / step).toInt()
    return (round((start + steps * step) * 1000f) / 1000f).coerceIn(start, end)
}

private fun Context.horizontalRow(minHeight: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(minHeight)
    setPadding(dp(16), dp(10), dp(16), dp(10))
}

private fun Context.labelColumn(
    @StringRes titleRes: Int,
    summary: CharSequence
) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    addView(
        text(titleRes, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
    )
    if (summary.isNotEmpty()) {
        addView(
            MaterialTextView(this@labelColumn).apply {
                text = summary
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 3
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        )
    }
}

private fun Context.text(
    @StringRes textRes: Int,
    appearance: Int,
    colorAttr: Int = com.google.android.material.R.attr.colorOnSurface
) = MaterialTextView(this).apply {
    setText(textRes)
    setTextAppearance(appearance)
    setTextColor(attrColor(colorAttr))
}

internal fun Context.attrColor(attr: Int): Int =
    MaterialColors.getColor(this, attr, Color.TRANSPARENT)

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun Context.selectableItemBackground() =
    android.util.TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        androidx.appcompat.content.res.AppCompatResources.getDrawable(this, value.resourceId)
    }
