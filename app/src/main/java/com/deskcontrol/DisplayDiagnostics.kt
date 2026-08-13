package com.deskcontrol

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DeviceProductInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import java.util.Locale

object DisplayDiagnostics {
    data class ActivityPolicyProbe(
        val standardActivityAllowed: Boolean?,
        val allowEmbeddedActivityAllowed: Boolean?,
        val standardActivityLogValue: String,
        val allowEmbeddedActivityLogValue: String
    )

    fun currentSnapshot(context: Context): List<String> {
        val manager = context.getSystemService(DisplayManager::class.java)
        val displays = manager?.displays?.sortedBy(Display::getDisplayId).orEmpty()
        val presentationIds = manager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.map(Display::getDisplayId)
            ?.sorted()
            .orEmpty()
        val lines = mutableListOf(
            "DisplaySnapshot: count=${displays.size} " +
                "presentationIds=${presentationIds.joinToString(",").ifEmpty { "none" }} " +
                "selectedId=${DisplaySessionManager.getSelectedDisplayId() ?: "none"}"
        )
        displays.forEach { display ->
            lines += detailLine(display)
            lines += policyProbeLine(context, display)
        }
        return lines
    }

    fun compact(display: Display): String {
        return "id=${display.displayId} name=${clean(display.name)} valid=${display.isValid} " +
            "state=${stateName(display.state)} flags=${DisplayFlagFormatter.format(display.flags)} " +
            "mode=${formatMode(display.mode)}"
    }

    @Suppress("DEPRECATION")
    fun detailLine(display: Display): String {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        val supportedModes = display.supportedModes
            .sortedBy { it.modeId }
            .joinToString(";") { formatMode(it) }
            .ifEmpty { "none" }
        val hdrTypes = runCatching {
            display.hdrCapabilities.supportedHdrTypes.joinToString(",")
        }.getOrDefault("unavailable").ifEmpty { "none" }
        val product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            formatProductInfo(display.deviceProductInfo)
        } else {
            "unsupported_api"
        }
        val cutout = display.cutout?.let {
            "safe=${it.safeInsetLeft},${it.safeInsetTop},${it.safeInsetRight},${it.safeInsetBottom};" +
                "bounds=${it.boundingRects.size}"
        } ?: "none"
        return "DisplayDetail: id=${display.displayId} name=${clean(display.name)} " +
            "valid=${display.isValid} state=${stateName(display.state)} rotation=${rotationName(display.rotation)} " +
            "flags=${DisplayFlagFormatter.format(display.flags)} real=${metrics.widthPixels}x${metrics.heightPixels} " +
            "densityDpi=${metrics.densityDpi} density=${formatDecimal(metrics.density)} " +
            "scaledDensity=${formatDecimal(metrics.scaledDensity)} xdpi=${formatDecimal(metrics.xdpi)} " +
            "ydpi=${formatDecimal(metrics.ydpi)} currentMode=${formatMode(display.mode)} " +
            "refreshRate=${formatDecimal(display.refreshRate)} supportedModes=[$supportedModes] " +
            "hdr=${display.isHdr} hdrTypes=[$hdrTypes] wideColor=${display.isWideColorGamut} " +
            "minimalPostProcessing=${display.isMinimalPostProcessingSupported} cutout=$cutout product=[$product]"
    }

    fun policySummary(context: Context, displayId: Int): String {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return "id=$displayId missing=true"
        return "id=$displayId name=${clean(display.name)} state=${stateName(display.state)} " +
            "flags=${DisplayFlagFormatter.format(display.flags)} mode=${formatMode(display.mode)}"
    }

    fun policyProbeLine(context: Context, display: Display): String {
        return policyProbeLine(display, activityPolicyProbe(context, display.displayId))
    }

    fun policyProbeLine(display: Display, probe: ActivityPolicyProbe): String {
        return "DisplayPolicyProbe: id=${display.displayId} " +
            policyProbeSummary(display, probe)
    }

    fun activityPolicyProbe(context: Context, displayId: Int): ActivityPolicyProbe {
        val standard = probeActivity(context, displayId, DiagnosticsActivity::class.java)
        val embedded = probeActivity(
            context,
            displayId,
            EmbeddedDisplayProbeActivity::class.java
        )
        return ActivityPolicyProbe(
            standardActivityAllowed = standard.allowed,
            allowEmbeddedActivityAllowed = embedded.allowed,
            standardActivityLogValue = standard.logValue,
            allowEmbeddedActivityLogValue = embedded.logValue
        )
    }

    fun policyProbeSummary(context: Context, displayId: Int): String {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return "id=$displayId missing=true"
        return policyProbeSummary(display, activityPolicyProbe(context, display.displayId))
    }

    private fun policyProbeSummary(display: Display, probe: ActivityPolicyProbe): String {
        return "standardActivity=${probe.standardActivityLogValue} " +
            "allowEmbeddedActivity=${probe.allowEmbeddedActivityLogValue} " +
            "trusted=${DisplayFlagFormatter.isTrusted(display.flags)} " +
            "private=${DisplayFlagFormatter.isPrivate(display.flags)}"
    }

    private data class ProbeValue(val allowed: Boolean?, val logValue: String)

    private fun probeActivity(
        context: Context,
        displayId: Int,
        activityClass: Class<out Activity>
    ): ProbeValue {
        val intent = Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            val allowed = context.getSystemService(ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(context, displayId, intent)
            ProbeValue(allowed = allowed, logValue = allowed.toString())
        }.getOrElse {
            ProbeValue(
                allowed = null,
                logValue = "unavailable:${it.javaClass.simpleName}"
            )
        }
    }

    private fun formatMode(mode: Display.Mode): String {
        return "${mode.modeId}:${mode.physicalWidth}x${mode.physicalHeight}@" +
            "${formatDecimal(mode.refreshRate)}"
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun formatProductInfo(info: DeviceProductInfo?): String {
        if (info == null) return "none"
        return "name=${clean(info.name)};pnp=${clean(info.manufacturerPnpId)};" +
            "productId=${clean(info.productId)};modelYear=${info.modelYear};" +
            "manufactureYear=${info.manufactureYear};manufactureWeek=${info.manufactureWeek};" +
            "connection=${connectionName(info.connectionToSinkType)}"
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun connectionName(connection: Int): String = when (connection) {
        DeviceProductInfo.CONNECTION_TO_SINK_UNKNOWN -> "unknown($connection)"
        DeviceProductInfo.CONNECTION_TO_SINK_BUILT_IN -> "built_in($connection)"
        DeviceProductInfo.CONNECTION_TO_SINK_DIRECT -> "direct($connection)"
        DeviceProductInfo.CONNECTION_TO_SINK_TRANSITIVE -> "transitive($connection)"
        else -> "unknown($connection)"
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_UNKNOWN -> "UNKNOWN($state)"
        Display.STATE_OFF -> "OFF($state)"
        Display.STATE_ON -> "ON($state)"
        Display.STATE_DOZE -> "DOZE($state)"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND($state)"
        Display.STATE_VR -> "VR($state)"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND($state)"
        else -> "UNKNOWN($state)"
    }

    private fun rotationName(rotation: Int): String = when (rotation) {
        Surface.ROTATION_0 -> "ROTATION_0($rotation)"
        Surface.ROTATION_90 -> "ROTATION_90($rotation)"
        Surface.ROTATION_180 -> "ROTATION_180($rotation)"
        Surface.ROTATION_270 -> "ROTATION_270($rotation)"
        else -> "UNKNOWN($rotation)"
    }

    private fun formatDecimal(value: Float): String =
        String.format(Locale.US, "%.2f", value)

    private fun clean(value: Any?): String = value
        ?.toString()
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.ifEmpty { "none" }
        ?.take(160)
        ?: "none"
}
