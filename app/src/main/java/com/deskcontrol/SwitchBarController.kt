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
    private var barHeightPx = 0
    private var isShown = false
    private var showRunnable: Runnable? = null
    private var hideRunnable: Runnable? = null

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
            barHeightPx = it.height
            applyHiddenState(immediate = true)
        }
    }

    fun onCursorMoved(x: Float, y: Float) {
        val height = displayInfo.height.toFloat()
        val inShowZone = y >= height - showThresholdPx
        val inBarRegion = y >= height - maxOf(hideThresholdPx, barHeightPx + view.bottomInsetPx)

        if (inShowZone) {
            scheduleShow("edge")
        } else if (isShown && !inBarRegion) {
            scheduleHide("leave")
        } else if (!inShowZone) {
            cancelShow()
        }
    }

    fun teardown() {
        cancelShow()
        cancelHide()
        runCatching { windowManager.removeView(view) }
    }

    private fun scheduleShow(reason: String) {
        if (isShown) return
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
        if (!isShown) return
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
        if (isShown) return
        isShown = true
        setTouchable(true)
        val targetTranslation = 0f
        view.animate().cancel()
        view.animate()
            .translationY(targetTranslation)
            .alpha(1f)
            .setDuration(showDurationMs)
            .setInterpolator(interpolator)
            .start()
        DiagnosticsLog.add("SwitchBar: show reason=$reason")
    }

    private fun hide(reason: String) {
        if (!isShown) return
        isShown = false
        setTouchable(false)
        applyHiddenState(immediate = false)
        DiagnosticsLog.add("SwitchBar: hide reason=$reason")
    }

    private fun applyHiddenState(immediate: Boolean) {
        val offset = barHeightPx.toFloat() + view.bottomInsetPx
        view.animate().cancel()
        if (immediate) {
            view.translationY = offset
            view.alpha = 0f
        } else {
            view.animate()
                .translationY(offset)
                .alpha(0f)
                .setDuration(hideDurationMs)
                .setInterpolator(interpolator)
                .start()
        }
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
        val favorites = SwitchBarStore.ensureFavorites(windowContext, launchablePackages)
        val recents = AppLaunchHistory.getRecent(windowContext, 2)
        val current = SessionStore.lastLaunchedPackage

        val items = mutableListOf<SwitchBarOverlayView.Item>()
        val added = LinkedHashSet<String>()
        if (current != null && appMap.containsKey(current)) {
            val app = appMap.getValue(current)
            items.add(
                SwitchBarOverlayView.Item(
                    label = app.label,
                    packageName = app.packageName,
                    icon = app.icon
                )
            )
            added.add(current)
        }
        favorites.forEach { pkg ->
            if (added.add(pkg)) {
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
        recents.forEach { pkg ->
            if (added.add(pkg)) {
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
}
