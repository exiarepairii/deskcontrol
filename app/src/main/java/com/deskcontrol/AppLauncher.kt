package com.deskcontrol

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Display
import androidx.annotation.StringRes
import java.util.concurrent.atomic.AtomicLong

object AppLauncher {
    private const val LAUNCH_STRATEGY = "DIRECT"
    private const val FLAG_ALLOW_UNTRUSTED_EMBEDDING_COMPAT = 0x10000000
    private const val FLAG_ALLOW_EMBEDDED_COMPAT = Int.MIN_VALUE
    private const val FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES_COMPAT = 0x00010000

    private val attemptSequence = AtomicLong()

    private enum class LaunchOrigin {
        USER,
        RESTORE_CONFIRMATION
    }

    enum class Outcome {
        EXTERNAL_REQUEST_ACCEPTED,
        FAILED
    }

    enum class FailureReason {
        NO_EXTERNAL_DISPLAY,
        FEATURE_UNSUPPORTED,
        NO_LAUNCH_INTENT,
        DISPLAY_SESSION_CHANGED,
        SECURITY_EXCEPTION,
        START_FAILED
    }

    data class Result(
        val outcome: Outcome,
        val flowId: String,
        val reason: FailureReason? = null,
        @StringRes val detailResId: Int? = null
    ) {
        val success: Boolean
            get() = outcome == Outcome.EXTERNAL_REQUEST_ACCEPTED
    }

    @Synchronized
    fun launchOnExternalDisplay(
        context: Context,
        packageName: String,
        className: String? = null
    ): Result {
        ProjectedAppRestoreCoordinator.onUserLaunchRequested()
        val flowId = newFlowId()
        val sourceDisplayId = sourceDisplayId(context)
        DiagnosticsLog.add(
            "LaunchRequest[$flowId]: event=USER_TAP strategy=$LAUNCH_STRATEGY package=$packageName " +
                "requestedComponent=${className ?: "resolve"} " +
                "context=${context.javaClass.simpleName} sourceDisplayId=$sourceDisplayId " +
                "uid=${Process.myUid()} user=${Process.myUserHandle()}"
        )

        val displayInfo = DisplaySessionManager.getExternalDisplayInfo()
            ?: return fail(
                context,
                FailureReason.NO_EXTERNAL_DISPLAY,
                R.string.app_launch_detail_no_external_display,
                stage = "REQUEST",
                flowId = flowId
            )

        if (!context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
            )
        ) {
            return fail(
                context,
                FailureReason.FEATURE_UNSUPPORTED,
                R.string.app_launch_detail_feature_unsupported,
                stage = "REQUEST",
                flowId = flowId
            )
        }

        return launchDirect(
            context,
            packageName,
            className,
            displayInfo.displayId,
            flowId,
            origin = LaunchOrigin.USER
        )
    }

    @Synchronized
    fun launchRestoreOnExternalDisplay(
        context: Context,
        component: ComponentName,
        expectedDisplayId: Int,
        expectedSessionGeneration: Long
    ): Result {
        val flowId = newFlowId()
        val expectedIdentity = ControlAccessibilityService.ExternalSessionIdentity(
            displayId = expectedDisplayId,
            generation = expectedSessionGeneration
        )
        DiagnosticsLog.add(
            "LaunchRequest[$flowId]: event=RESTORE_CONFIRMATION strategy=$LAUNCH_STRATEGY " +
                "component=${component.flattenToShortString()} " +
                "requestedDisplayId=$expectedDisplayId generation=$expectedSessionGeneration " +
                "context=${context.javaClass.simpleName} sourceDisplayId=${sourceDisplayId(context)}"
        )
        val currentDisplay = DisplaySessionManager.getExternalDisplayInfo()
        val currentIdentity = ControlAccessibilityService.currentExternalSessionIdentity()
        if (DisplaySessionManager.getSelectedDisplayState() !=
                DisplaySessionManager.ExternalDisplayState.ACTIVE ||
            currentDisplay?.displayId != expectedDisplayId ||
            currentIdentity != expectedIdentity
        ) {
            return fail(
                context,
                FailureReason.DISPLAY_SESSION_CHANGED,
                R.string.app_launch_detail_display_session_changed,
                stage = "RESTORE_VALIDATION",
                flowId = flowId
            )
        }
        if (!context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
            )
        ) {
            return fail(
                context,
                FailureReason.FEATURE_UNSUPPORTED,
                R.string.app_launch_detail_feature_unsupported,
                stage = "RESTORE_VALIDATION",
                flowId = flowId
            )
        }
        return launchDirect(
            context = context,
            packageName = component.packageName,
            className = component.className,
            displayId = expectedDisplayId,
            flowId = flowId,
            origin = LaunchOrigin.RESTORE_CONFIRMATION,
            expectedSessionIdentity = expectedIdentity
        )
    }

    fun isExternalLaunchAllowed(
        context: Context,
        component: ComponentName,
        displayId: Int
    ): Boolean? {
        val intent = buildLaunchIntent(context, component)
        return try {
            context.getSystemService(ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(context, displayId, intent)
        } catch (ex: Exception) {
            DiagnosticsLog.add(
                "RestoreProjection: event=PREFLIGHT_EXCEPTION " +
                    "component=${component.flattenToShortString()} displayId=$displayId " +
                    "exception=${ex.javaClass.name} message=${safeMessage(ex)}"
            )
            null
        }
    }

    private fun launchDirect(
        context: Context,
        packageName: String,
        className: String?,
        displayId: Int,
        flowId: String,
        origin: LaunchOrigin,
        expectedSessionIdentity: ControlAccessibilityService.ExternalSessionIdentity? = null
    ): Result {
        val component = resolveLauncherComponent(context, packageName, className, flowId)
            ?: return fail(
                context,
                FailureReason.NO_LAUNCH_INTENT,
                R.string.app_launch_detail_no_launch_intent,
                stage = "EXTERNAL_HANDOFF",
                flowId = flowId
            )
        val launchIntent = buildLaunchIntent(context, component)

        logIntent(flowId, "EXTERNAL_HANDOFF", launchIntent)
        logTargetCapabilities(context, flowId, "EXTERNAL_HANDOFF", component)
        logDisplayPreflight(
            context,
            displayId,
            launchIntent,
            flowId,
            stage = "EXTERNAL_HANDOFF",
            checkpoint = "direct"
        )

        return try {
            val currentSessionIdentity =
                ControlAccessibilityService.currentExternalSessionIdentity()
                    ?.takeIf { it.displayId == displayId }
            if (expectedSessionIdentity != null &&
                currentSessionIdentity != expectedSessionIdentity
            ) {
                return fail(
                    context,
                    FailureReason.DISPLAY_SESSION_CHANGED,
                    R.string.app_launch_detail_display_session_changed,
                    stage = "RESTORE_VALIDATION",
                    flowId = flowId
                )
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            DiagnosticsLog.add(
                "Launch[$flowId]: event=DISPATCH stage=EXTERNAL_HANDOFF " +
                    "strategy=$LAUNCH_STRATEGY origin=${origin.name} " +
                    "sourceDisplayId=${sourceDisplayId(context)} " +
                    "requestedDisplayId=$displayId activityOptionsLaunchDisplayId=$displayId"
            )
            context.startActivity(launchIntent, options.toBundle())
            externalRequestAccepted(
                context,
                component,
                displayId,
                flowId,
                origin,
                currentSessionIdentity
            )
        } catch (se: SecurityException) {
            launchException(
                context,
                se,
                stage = "EXTERNAL_HANDOFF",
                flowId = flowId
            )
        } catch (ex: Exception) {
            launchException(
                context,
                ex,
                stage = "EXTERNAL_HANDOFF",
                flowId = flowId
            )
        }
    }

    private fun resolveLauncherComponent(
        context: Context,
        packageName: String,
        className: String?,
        flowId: String
    ): ComponentName? {
        if (!className.isNullOrBlank()) {
            return ComponentName(packageName, className)
        }

        val launcherAppsComponent = try {
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(packageName, Process.myUserHandle())
                .firstOrNull()
                ?.componentName
                ?.also {
                    DiagnosticsLog.add(
                        "Launch[$flowId]: event=COMPONENT_RESOLVED source=LauncherApps " +
                            "component=${it.flattenToShortString()}"
                    )
                }
        } catch (ex: Exception) {
            DiagnosticsLog.add(
                "Launch[$flowId]: event=COMPONENT_RESOLVE_FALLBACK package=$packageName " +
                    "exception=${ex.javaClass.name} message=${safeMessage(ex)}"
            )
            null
        }
        if (launcherAppsComponent != null) return launcherAppsComponent

        return context.packageManager.getLaunchIntentForPackage(packageName)?.component?.also {
            DiagnosticsLog.add(
                "Launch[$flowId]: event=COMPONENT_RESOLVED source=PackageManagerFallback " +
                    "component=${it.flattenToShortString()}"
            )
        }
    }

    private fun standardLauncherIntent(component: ComponentName): Intent {
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
    }

    private fun buildLaunchIntent(context: Context, component: ComponentName): Intent {
        val packageIntent = context.packageManager.getLaunchIntentForPackage(component.packageName)
        return (if (packageIntent?.component == component) {
            packageIntent
        } else {
            standardLauncherIntent(component)
        }).apply {
            setComponent(component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun logIntent(
        flowId: String,
        stage: String,
        intent: Intent
    ) {
        val categories = intent.categories?.sorted()?.joinToString("|") ?: "none"
        DiagnosticsLog.add(
            "Launch[$flowId]: event=INTENT stage=$stage strategy=$LAUNCH_STRATEGY " +
                "action=${intent.action ?: "none"} " +
                "component=${intent.component?.flattenToShortString() ?: "implicit"} " +
                "categories=$categories flags=${hex(intent.flags)} " +
                "flagsDecoded=${decodeIntentFlags(intent.flags)}"
        )
    }

    private fun logTargetCapabilities(
        context: Context,
        flowId: String,
        stage: String,
        component: ComponentName
    ) {
        val activityInfo = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getActivityInfo(component, 0)
            }
        } catch (ex: Exception) {
            DiagnosticsLog.add(
                "Launch[$flowId]: event=TARGET_INFO_UNAVAILABLE stage=$stage " +
                    "component=${component.flattenToShortString()} " +
                    "exception=${ex.javaClass.name} message=${safeMessage(ex)}"
            )
            return
        }

        val flags = activityInfo.flags
        val allowUntrusted = flags and FLAG_ALLOW_UNTRUSTED_EMBEDDING_COMPAT != 0
        val allowEmbeddedCompat = flags and FLAG_ALLOW_EMBEDDED_COMPAT != 0
        val canDisplayOnRemoteDevices =
            flags and FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES_COMPAT != 0
        val requiredDisplayCategory = if (Build.VERSION.SDK_INT >= 34) {
            activityInfo.requiredDisplayCategory ?: "none"
        } else {
            "unsupported_api"
        }
        val targetPackageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    component.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(component.packageName, 0)
            }
        }.getOrNull()
        val multipleTaskMayBeOverridden =
            activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                activityInfo.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_NEVER
        DiagnosticsLog.add(
            "Launch[$flowId]: event=TARGET_INFO stage=$stage " +
                "component=${component.flattenToShortString()} exported=${activityInfo.exported} " +
                "enabled=${activityInfo.enabled} launchMode=${launchModeName(activityInfo.launchMode)} " +
                "documentLaunchMode=${documentLaunchModeName(activityInfo.documentLaunchMode)} " +
                "taskAffinity=${activityInfo.taskAffinity ?: "none"} " +
                "packageVersionName=${targetPackageInfo?.versionName ?: "unavailable"} " +
                "packageVersionCode=${targetPackageInfo?.longVersionCode ?: -1} " +
                "targetSdk=${activityInfo.applicationInfo.targetSdkVersion} " +
                "requiredDisplayCategory=$requiredDisplayCategory " +
                "activityFlags=${hex(flags)} allowUntrustedEmbedding=$allowUntrusted " +
                "allowEmbeddedCompat=$allowEmbeddedCompat " +
                "canDisplayOnRemoteDevices=$canDisplayOnRemoteDevices " +
                "multipleTaskMayBeOverridden=$multipleTaskMayBeOverridden"
        )
    }

    private fun logDisplayPreflight(
        context: Context,
        displayId: Int,
        intent: Intent,
        flowId: String,
        stage: String,
        checkpoint: String
    ) {
        val allowed = try {
            context.getSystemService(ActivityManager::class.java)
                .isActivityStartAllowedOnDisplay(context, displayId, intent)
        } catch (ex: Exception) {
            DiagnosticsLog.add(
                "Launch[$flowId]: event=PREFLIGHT_EXCEPTION stage=$stage checkpoint=$checkpoint " +
                    "strategy=$LAUNCH_STRATEGY displayId=$displayId " +
                    "exception=${ex.javaClass.name} message=${safeMessage(ex)}"
            )
            null
        }
        DiagnosticsLog.add(
            "Launch[$flowId]: event=PREFLIGHT stage=$stage checkpoint=$checkpoint " +
                "strategy=$LAUNCH_STRATEGY displayId=$displayId allowed=$allowed " +
                "component=${intent.component?.flattenToShortString() ?: "implicit"} " +
                "flags=${hex(intent.flags)} " +
                "displayPolicy=[${DisplayDiagnostics.policySummary(context, displayId)}] " +
                "callerPolicyProbe=[${DisplayDiagnostics.policyProbeSummary(context, displayId)}]"
        )
    }

    private fun externalRequestAccepted(
        context: Context,
        component: ComponentName,
        displayId: Int,
        flowId: String,
        origin: LaunchOrigin,
        sessionIdentity: ControlAccessibilityService.ExternalSessionIdentity?
    ): Result {
        val packageName = component.packageName
        if (origin == LaunchOrigin.USER) {
            AppLaunchHistory.recordLaunch(context.applicationContext, packageName)
            SessionStore.lastLaunchedPackage = packageName
            ProjectedAppRestoreCoordinator.onUserLaunchAccepted(
                flowId = flowId,
                component = component,
                displayId = displayId,
                sessionIdentity = sessionIdentity
            )
        }
        SessionStore.lastLaunchFailure = null
        DiagnosticsLog.add(
            "Launch[$flowId]: event=EXTERNAL_API_ACCEPTED stage=EXTERNAL_HANDOFF " +
                "strategy=$LAUNCH_STRATEGY origin=${origin.name} package=$packageName " +
                "component=${component.flattenToShortString()} displayId=$displayId " +
                "session=${sessionIdentity ?: "none"}; " +
                "verified=false awaiting_window_observation=true"
        )
        ControlAccessibilityService.requestLaunchObservation(
            flowId,
            packageName,
            Display.DEFAULT_DISPLAY,
            phase = "POST_EXTERNAL_PHONE"
        )
        ControlAccessibilityService.requestLaunchObservation(
            flowId,
            packageName,
            displayId,
            phase = "POST_EXTERNAL_TARGET",
            expectedSessionIdentity = sessionIdentity
        )
        return Result(Outcome.EXTERNAL_REQUEST_ACCEPTED, flowId)
    }

    private fun launchException(
        context: Context,
        throwable: Throwable,
        stage: String,
        flowId: String
    ): Result {
        val reason = if (throwable is SecurityException) {
            FailureReason.SECURITY_EXCEPTION
        } else {
            FailureReason.START_FAILED
        }
        val detailResId = if (reason == FailureReason.SECURITY_EXCEPTION) {
            R.string.app_launch_detail_security_exception
        } else {
            R.string.app_launch_detail_unknown_failure
        }
        return fail(
            context,
            reason,
            detailResId,
            throwable,
            stage,
            flowId
        )
    }

    private fun fail(
        context: Context,
        reason: FailureReason,
        @StringRes detailResId: Int,
        throwable: Throwable? = null,
        stage: String,
        flowId: String
    ): Result {
        val reasonLabel = context.getString(reasonLabelResId(reason))
        val detail = context.getString(detailResId)
        SessionStore.lastLaunchFailure = context.getString(
            R.string.app_launch_failed_with_detail,
            reasonLabel,
            detail
        )
        val exceptionDetail = throwable?.let {
            " exception=${it.javaClass.name} message=${safeMessage(it)}"
        }.orEmpty()
        DiagnosticsLog.add(
            "Launch[$flowId]: event=FAILURE stage=$stage strategy=$LAUNCH_STRATEGY " +
                "reason=$reason detail=$detail$exceptionDetail"
        )
        return Result(Outcome.FAILED, flowId, reason, detailResId)
    }

    private fun sourceDisplayId(context: Context): Int {
        return runCatching { context.display.displayId }
            .getOrDefault(Display.INVALID_DISPLAY)
    }

    private fun newFlowId(): String {
        return "${SystemClock.elapsedRealtime()}-${attemptSequence.incrementAndGet()}"
    }

    private fun safeMessage(throwable: Throwable): String {
        return throwable.message
            ?.replace('\n', ' ')
            ?.take(800)
            ?: "none"
    }

    private fun hex(value: Int): String {
        return "0x${Integer.toUnsignedString(value, 16).padStart(8, '0')}"
    }

    private fun decodeIntentFlags(flags: Int): String {
        val labels = mutableListOf<String>()
        if (flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0) labels += "NEW_TASK"
        if (flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0) labels += "MULTIPLE_TASK"
        if (flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0) {
            labels += "RESET_TASK_IF_NEEDED"
        }
        if (flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT != 0) labels += "NEW_DOCUMENT"
        if (flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0) labels += "CLEAR_TASK"
        if (flags and Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT != 0) labels += "LAUNCH_ADJACENT"
        if (flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0) labels += "REORDER_TO_FRONT"
        return labels.ifEmpty { listOf("none") }.joinToString("|")
    }

    private fun launchModeName(value: Int): String {
        return when (value) {
            ActivityInfo.LAUNCH_MULTIPLE -> "multiple($value)"
            ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop($value)"
            ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask($value)"
            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance($value)"
            ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK -> "singleInstancePerTask($value)"
            else -> "unknown($value)"
        }
    }

    private fun documentLaunchModeName(value: Int): String {
        return when (value) {
            ActivityInfo.DOCUMENT_LAUNCH_NONE -> "none($value)"
            ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING -> "intoExisting($value)"
            ActivityInfo.DOCUMENT_LAUNCH_ALWAYS -> "always($value)"
            ActivityInfo.DOCUMENT_LAUNCH_NEVER -> "never($value)"
            else -> "unknown($value)"
        }
    }

    @StringRes
    fun reasonLabelResId(reason: FailureReason): Int {
        return when (reason) {
            FailureReason.NO_EXTERNAL_DISPLAY -> R.string.app_launch_reason_no_external_display
            FailureReason.FEATURE_UNSUPPORTED -> R.string.app_launch_reason_feature_unsupported
            FailureReason.NO_LAUNCH_INTENT -> R.string.app_launch_reason_no_launch_intent
            FailureReason.DISPLAY_SESSION_CHANGED ->
                R.string.app_launch_reason_display_session_changed
            FailureReason.SECURITY_EXCEPTION -> R.string.app_launch_reason_security_exception
            FailureReason.START_FAILED -> R.string.app_launch_reason_start_failed
        }
    }

    fun buildFailureMessage(context: Context, result: Result): String {
        val reason = result.reason
        if (reason == null) {
            return context.getString(R.string.app_launch_failed_generic)
        }
        val reasonLabel = context.getString(reasonLabelResId(reason))
        val detailResId = result.detailResId
        return if (detailResId == null) {
            context.getString(R.string.app_launch_failed_reason_only, reasonLabel)
        } else {
            context.getString(
                R.string.app_launch_failed_with_detail,
                reasonLabel,
                context.getString(detailResId)
            )
        }
    }
}
