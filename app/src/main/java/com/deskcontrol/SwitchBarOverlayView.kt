package com.deskcontrol

import android.content.Context
import android.graphics.Rect
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
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.google.android.material.color.MaterialColors

class SwitchBarOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    data class ContainerMetrics(
        val top: Int,
        val bottom: Int,
        val height: Int
    )

    data class Item(
        val label: String,
        val packageName: String?,
        val icon: Drawable?,
        val isAllApps: Boolean = false,
        val isDivider: Boolean = false
    )

    private val itemsRow: ViewGroup
    private val container: ViewGroup
    private var onItemClick: ((Item) -> Unit)? = null
    private var contentScale = 1f
    private val baseItemSizePx: Int
    private val baseIconSizePx: Int
    private val baseItemPaddingPx: Int
    private val baseItemMarginPx: Int
    private val baseRowHeightPx: Int
    private val baseContainerPaddingH: Int
    private val baseContainerPaddingV: Int
    private val baseRootPaddingH: Int
    private val baseRootPaddingV: Int
    var bottomInsetPx: Int = 0
        private set

    init {
        LayoutInflater.from(context).inflate(R.layout.switch_bar_overlay, this, true)
        container = findViewById(R.id.switch_bar_container)
        itemsRow = findViewById(R.id.switch_bar_items)
        val density = resources.displayMetrics.density
        baseItemSizePx = (72f * density).toInt()
        baseIconSizePx = (48f * density).toInt()
        baseItemPaddingPx = (12f * density).toInt()
        baseItemMarginPx = (4f * density).toInt()
        baseRowHeightPx = (72f * density).toInt()
        baseContainerPaddingH = (8f * density).toInt()
        baseContainerPaddingV = (4f * density).toInt()
        baseRootPaddingH = (12f * density).toInt()
        baseRootPaddingV = (8f * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            bottomInsetPx = systemInsets.bottom
            if (container.isLaidOut) {
                applyContentScale()
            } else {
                v.updatePadding(bottom = baseRootPaddingV + systemInsets.bottom)
            }
            insets
        }
    }

    fun setItems(items: List<Item>) {
        itemsRow.removeAllViews()
        items.forEach { item ->
            itemsRow.addView(buildItemView(item))
        }
        container.doOnLayout {
            applyContentScale()
        }
    }

    fun setOnItemClickListener(listener: (Item) -> Unit) {
        onItemClick = listener
    }

    fun setContentScale(scale: Float) {
        contentScale = scale
        if (!container.isLaidOut) {
            container.doOnLayout { applyContentScale() }
            return
        }
        applyContentScale()
    }

    private fun applyContentScale() {
        val scale = contentScale
        val rootPadH = (baseRootPaddingH * scale).toInt()
        val rootPadV = (baseRootPaddingV * scale).toInt()
        updatePadding(
            left = rootPadH,
            right = rootPadH,
            top = rootPadV,
            bottom = rootPadV + bottomInsetPx
        )
        val containerPadH = (baseContainerPaddingH * scale).toInt()
        val containerPadV = (baseContainerPaddingV * scale).toInt()
        container.setPadding(containerPadH, containerPadV, containerPadH, containerPadV)
        val rowParams = itemsRow.layoutParams
        rowParams.height = (baseRowHeightPx * scale).toInt().coerceAtLeast(1)
        itemsRow.layoutParams = rowParams
        for (i in 0 until itemsRow.childCount) {
            val itemView = itemsRow.getChildAt(i) as? FrameLayout ?: continue
            val itemParams = itemView.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val itemSize = (baseItemSizePx * scale).toInt().coerceAtLeast(1)
            val itemMargin = (baseItemMarginPx * scale).toInt()
            itemParams.width = itemSize
            itemParams.height = itemSize
            itemParams.leftMargin = itemMargin
            itemParams.rightMargin = itemMargin
            itemView.layoutParams = itemParams
            val itemPadding = (baseItemPaddingPx * scale).toInt()
            itemView.setPadding(itemPadding, itemPadding, itemPadding, itemPadding)
            val icon = itemView.getChildAt(0) as? ImageView ?: continue
            val iconSize = (baseIconSizePx * scale).toInt().coerceAtLeast(1)
            val iconParams = icon.layoutParams as? LayoutParams ?: continue
            iconParams.width = iconSize
            iconParams.height = iconSize
            icon.layoutParams = iconParams
        }
    }

    fun getContainerBoundsInView(): Rect? {
        if (!container.isLaidOut) return null
        return Rect(container.left, container.top, container.right, container.bottom)
    }

    fun getContainerMetricsOnScreen(): ContainerMetrics {
        val location = IntArray(2)
        container.getLocationOnScreen(location)
        val top = location[1]
        val height = container.height
        return ContainerMetrics(top, top + height, height)
    }

    private fun buildItemView(item: Item): View {
        if (item.isDivider) {
            return buildDividerView()
        }
        val iconDrawable = item.icon ?: return View(context)
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
            setImageDrawable(iconDrawable)
        }
        container.addView(imageView)
        return container
    }

    private fun buildDividerView(): View {
        val density = resources.displayMetrics.density
        val widthPx = (1f * density).toInt().coerceAtLeast(1)
        val heightPx = (32f * density).toInt()
        val marginPx = (8f * density).toInt()
        return View(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
                leftMargin = marginPx
                rightMargin = marginPx
            }
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOutline,
                    0
                )
            )
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
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
