package com.deskcontrol

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

class SwitchBarController(
    private val serviceContext: Context,
    private val windowContext: Context,
    private val windowManager: WindowManager,
    private val displayInfo: DisplaySessionManager.ExternalDisplayInfo
) {
    private enum class State {
        HIDDEN,
        SHOWING,
        SHOWN,
        HIDING
    }

    private val handler = Handler(Looper.getMainLooper())
    private val view = SwitchBarOverlayView(windowContext)
    private val interpolator = FastOutSlowInInterpolator()
    private val density = windowContext.resources.displayMetrics.density
    private val showThresholdPx = (6f * density).toInt()
    private val hideThresholdPx = (28f * density).toInt()
    private val showDelayMs = 120L
    private val hideDelayMs = 260L
    private val showDurationMs = 170L
    private val hideDurationMs = 130L
    private var baseBarHeightPx = 0
    private var currentBarHeightPx = 0
    private var state = State.HIDDEN
    private var showRunnable: Runnable? = null
    private var hideRunnable: Runnable? = null
    private var forceVisible = false
    init {
        view.alpha = 0f
        view.translationY = 0f
        view.setOnItemClickListener { item -> handleItemClick(item) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.x = 0
        params.y = 0
        runCatching { windowManager.addView(view, params) }.onFailure {
            DiagnosticsLog.add("SwitchBar: attach failed ${it.message}")
        }
        view.doOnLayout {
            if (baseBarHeightPx == 0) {
                baseBarHeightPx = it.height
            }
            currentBarHeightPx = it.height
            DiagnosticsLog.add(
                "SwitchBar: layout baseHeight=$baseBarHeightPx h=${it.height} w=${it.width}"
            )
            updateScale()
            applyHiddenState(immediate = true)
        }
    }

    fun onCursorMoved(x: Float, y: Float) {
        if (forceVisible) {
            scheduleShow("force")
            cancelHide()
            return
        }
        val height = displayInfo.height.toFloat()
        val inShowZone = y >= height - showThresholdPx
        val barHeight = if (currentBarHeightPx > 0) currentBarHeightPx else baseBarHeightPx
        val inHideZone = y >= height - maxOf(hideThresholdPx, barHeight + view.bottomInsetPx)
        val bounds = view.getContainerBoundsInView()
        val slopPx = (6f * density).toInt()
        val insideBar = if (bounds == null) {
            inHideZone
        } else {
            inHideZone &&
                x >= bounds.left - slopPx &&
                x <= bounds.right + slopPx
        }

        onBarHoverChanged(insideBar)

        if (inShowZone) {
            scheduleShow("edge")
            cancelHide()
        } else {
            cancelShow()
            if (state == State.SHOWN && !insideBar) {
                scheduleHide("leave")
            }
        }
    }

    fun teardown() {
        cancelShow()
        cancelHide()
        runCatching { windowManager.removeView(view) }
    }

    fun refreshScale() {
        updateScale()
    }

    fun refreshItems() {
        rebuildItems()
    }

    fun setForceVisible(enabled: Boolean) {
        forceVisible = enabled
        if (enabled) {
            cancelHide()
            show("force")
        }
    }

    fun onBarHoverChanged(insideBar: Boolean) {
        if (state == State.SHOWN && insideBar) {
            cancelHide()
        }
    }

    private fun scheduleShow(reason: String) {
        if (state != State.HIDDEN) return
        cancelHide()
        if (showRunnable != null) return
        showRunnable = Runnable {
            showRunnable = null
            rebuildItems()
            show(reason)
        }
        handler.postDelayed(showRunnable!!, showDelayMs)
    }

    private fun scheduleHide(reason: String) {
        if (state != State.SHOWN) return
        if (forceVisible) return
        if (hideRunnable != null) return
        hideRunnable = Runnable {
            hideRunnable = null
            hide(reason)
        }
        handler.postDelayed(hideRunnable!!, hideDelayMs)
    }

    private fun cancelShow() {
        showRunnable?.let { handler.removeCallbacks(it) }
        showRunnable = null
    }

    private fun cancelHide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun show(reason: String) {
        if (state == State.SHOWN || state == State.SHOWING) return
        state = State.SHOWING
        setTouchable(true)
        val targetTranslation = 0f
        view.animate().cancel()
        view.animate()
            .translationY(targetTranslation)
            .alpha(1f)
            .setDuration(showDurationMs)
            .setInterpolator(interpolator)
            .withEndAction {
                if (state == State.SHOWING) {
                    state = State.SHOWN
                }
            }
            .start()
        DiagnosticsLog.add(
            "SwitchBar: show reason=$reason scale=${SettingsStore.switchBarScale} " +
                "baseH=$baseBarHeightPx curH=$currentBarHeightPx inset=${view.bottomInsetPx}"
        )
    }

    private fun hide(reason: String) {
        if (state == State.HIDDEN || state == State.HIDING) return
        state = State.HIDING
        setTouchable(false)
        applyHiddenState(immediate = false)
        DiagnosticsLog.add(
            "SwitchBar: hide reason=$reason scale=${SettingsStore.switchBarScale} " +
                "baseH=$baseBarHeightPx curH=$currentBarHeightPx inset=${view.bottomInsetPx}"
        )
    }

    private fun applyHiddenState(immediate: Boolean) {
        val barHeight = if (currentBarHeightPx > 0) currentBarHeightPx else baseBarHeightPx
        val offset = barHeight.toFloat() + view.bottomInsetPx
        view.animate().cancel()
        if (immediate) {
            view.translationY = offset
            view.alpha = 0f
            state = State.HIDDEN
        } else {
            view.animate()
                .translationY(offset)
                .alpha(0f)
                .setDuration(hideDurationMs)
                .setInterpolator(interpolator)
                .withEndAction {
                    if (state == State.HIDING) {
                        state = State.HIDDEN
                    }
                }
                .start()
        }
        DiagnosticsLog.add(
            "SwitchBar: hidden offset=$offset scale=${SettingsStore.switchBarScale} " +
                "baseH=$baseBarHeightPx curH=$currentBarHeightPx inset=${view.bottomInsetPx}"
        )
    }

    private fun setTouchable(touchable: Boolean) {
        val params = view.layoutParams as WindowManager.LayoutParams
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.flags = if (touchable) baseFlags else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun rebuildItems() {
        val apps = LaunchableAppCatalog.load(windowContext)
        val appMap = apps.associateBy { it.packageName }
        val launchablePackages = apps.map { it.packageName }
        val favorites = SwitchBarStore.getFavoriteSlots(windowContext)
        val pinned = favorites.mapNotNull { pkg ->
            pkg?.takeIf { it in launchablePackages }
        }
        val recents = AppLaunchHistory.getRecent(windowContext, 6)
            .filter { it !in pinned }
            .take(2)

        val items = mutableListOf<SwitchBarOverlayView.Item>()
        pinned.forEach { pkg ->
            val app = appMap[pkg] ?: return@forEach
            items.add(
                SwitchBarOverlayView.Item(
                    label = app.label,
                    packageName = app.packageName,
                    icon = app.icon
                )
            )
        }
        val allAppsIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
            windowContext,
            R.drawable.ic_all_apps
        )
        if (allAppsIcon != null) {
            items.add(
                SwitchBarOverlayView.Item(
                    label = windowContext.getString(R.string.switch_bar_all_apps),
                    packageName = null,
                    icon = allAppsIcon,
                    isAllApps = true
                )
            )
        }
        if (recents.isNotEmpty()) {
            items.add(
                SwitchBarOverlayView.Item(
                    label = "",
                    packageName = null,
                    icon = null,
                    isDivider = true
                )
            )
            recents.forEach { pkg ->
                val app = appMap[pkg] ?: return@forEach
                items.add(
                    SwitchBarOverlayView.Item(
                        label = app.label,
                        packageName = app.packageName,
                        icon = app.icon
                    )
                )
            }
        }
        view.setItems(items)
    }

    private fun handleItemClick(item: SwitchBarOverlayView.Item) {
        cancelShow()
        cancelHide()
        hide("click")
        if (item.isAllApps) {
            AppDrawerActivity.launchOnExternalDisplay(serviceContext, displayInfo.displayId)
            DiagnosticsLog.add("SwitchBar: open drawer")
            return
        }
        val packageName = item.packageName ?: return
        val result = AppLauncher.launchOnExternalDisplay(serviceContext, packageName)
        if (result.success) {
            DiagnosticsLog.add("SwitchBar: launch success package=$packageName")
        } else {
            val message = AppLauncher.buildFailureMessage(windowContext, result)
            Toast.makeText(windowContext, message, Toast.LENGTH_LONG).show()
            DiagnosticsLog.add("SwitchBar: launch failure package=$packageName reason=${result.reason}")
        }
    }

    private fun updateScale() {
        val scale = SettingsStore.switchBarScale.coerceIn(0.7f, 1.3f)
        view.setContentScale(scale)
        DiagnosticsLog.add(
            "SwitchBar: scale=$scale baseH=$baseBarHeightPx curH=$currentBarHeightPx " +
                "viewH=${view.height} inset=${view.bottomInsetPx}"
        )
        view.doOnLayout {
            currentBarHeightPx = it.height
            if (state == State.SHOWN || state == State.SHOWING) {
                view.translationY = 0f
                view.alpha = 1f
            } else {
                applyHiddenState(immediate = true)
            }
        }
    }

}
