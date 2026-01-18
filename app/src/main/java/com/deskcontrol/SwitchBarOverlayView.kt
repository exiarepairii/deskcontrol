package com.deskcontrol

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class SwitchBarOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    data class Item(
        val label: String,
        val packageName: String?,
        val icon: Drawable,
        val isAllApps: Boolean = false
    )

    private val itemsRow: ViewGroup
    private var onItemClick: ((Item) -> Unit)? = null
    var bottomInsetPx: Int = 0
        private set

    init {
        LayoutInflater.from(context).inflate(R.layout.switch_bar_overlay, this, true)
        itemsRow = findViewById(R.id.switch_bar_items)
        val initialBottomPadding = paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bottomInsetPx = systemInsets.bottom
            v.updatePadding(bottom = initialBottomPadding + systemInsets.bottom)
            insets
        }
    }

    fun setItems(items: List<Item>) {
        itemsRow.removeAllViews()
        items.forEach { item ->
            itemsRow.addView(buildItemView(item))
        }
    }

    fun setOnItemClickListener(listener: (Item) -> Unit) {
        onItemClick = listener
    }

    private fun buildItemView(item: Item): View {
        val density = resources.displayMetrics.density
        val sizePx = (72f * density).toInt()
        val iconSizePx = (48f * density).toInt()
        val paddingPx = (12f * density).toInt()
        val container = FrameLayout(context).apply {
            val margin = (4f * density).toInt()
            layoutParams = ViewGroup.MarginLayoutParams(sizePx, sizePx).apply {
                leftMargin = margin
                rightMargin = margin
            }
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            foreground = resolveSelectableItemBackground()
            isClickable = true
            isFocusable = false
            contentDescription = item.label
            setOnClickListener { onItemClick?.invoke(item) }
        }
        val imageView = ImageView(context).apply {
            layoutParams = LayoutParams(iconSizePx, iconSizePx).apply {
                gravity = android.view.Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(item.icon)
        }
        container.addView(imageView)
        return container
    }

    private fun resolveSelectableItemBackground(): Drawable? {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                typedValue,
                true
            )
        ) {
            androidx.appcompat.content.res.AppCompatResources.getDrawable(context, typedValue.resourceId)
        } else {
            null
        }
    }
}
