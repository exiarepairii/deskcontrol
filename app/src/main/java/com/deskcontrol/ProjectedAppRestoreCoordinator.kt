package com.deskcontrol

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference

/**
 * Offers one foreground, user-confirmed relaunch after an external display wakes or reconnects.
 *
 * Candidates are process-local and come only from accessibility-window verification of an
 * explicit user launch. A physical reconnect can reuse that candidate on the newly assigned
 * display id, but an in-place display selection change and process death drop it. This object
 * never starts an Activity from the background.
 */
object ProjectedAppRestoreCoordinator : DisplaySessionManager.Listener,
    Application.ActivityLifecycleCallbacks {

    private const val NATURAL_RESTORE_GRACE_MS = 1_200L
    private const val ACCEPTED_ATTEMPT_TTL_MS = 120_000L
    private val ACCEPTED_ATTEMPT_CHECK_DELAYS_MS = longArrayOf(0L, 300L, 1_200L, 3_000L)

    private data class AcceptedAttempt(
        val flowId: String,
        val component: ComponentName,
        val displayId: Int,
        val acceptedAtElapsedMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val state = ProjectedAppRestoreState()
    private var application: Application? = null
    private var acceptedAttempt: AcceptedAttempt? = null
    private var foregroundHost = WeakReference<AppCompatActivity>(null)
    private var promptReadyKey: Long? = null
    private var dialog: AlertDialog? = null
    private var dialogKey: Long? = null
    private var graceRunnable: Runnable? = null
    private var graceKey: Long? = null

    private val runtimeStateListener: () -> Unit = {
        mainHandler.post {
            verifyAcceptedAttemptIfVisible()
            acceptedAttempt?.flowId?.let(::scheduleAcceptedAttemptChecks)
            evaluatePendingRequest()
        }
    }

    @Synchronized
    fun init(app: Application) {
        if (application != null) return
        application = app
        app.registerActivityLifecycleCallbacks(this)
        DisplaySessionManager.addListener(this)
        ControlAccessibilityService.addRuntimeStateListener(runtimeStateListener)
        DiagnosticsLog.add("RestoreProjection: event=INITIALIZED policy=ASK_FOREGROUND")
    }

    fun onUserLaunchRequested() {
        runOnMain {
            acceptedAttempt = null
            state.onUserLaunchRequested()
            cancelGrace()
            promptReadyKey = null
            dismissDialogWithoutDecision()
            DiagnosticsLog.add("RestoreProjection: event=CANCELLED reason=new_user_launch")
        }
    }

    fun onUserLaunchAccepted(
        flowId: String,
        component: ComponentName,
        displayId: Int,
        targetIdentity: ControlAccessibilityService.ExternalSessionIdentity?
    ) {
        runOnMain {
            if (DisplaySessionManager.getSelectedDisplayState() !=
                    DisplaySessionManager.ExternalDisplayState.ACTIVE ||
                DisplaySessionManager.getSelectedDisplayId() != displayId ||
                (targetIdentity != null && targetIdentity.displayId != displayId)
            ) {
                acceptedAttempt = null
                DiagnosticsLog.add(
                    "RestoreProjection: event=CANDIDATE_REJECTED flowId=$flowId " +
                        "component=${component.flattenToShortString()} displayId=$displayId " +
                        "reason=display_session_changed target=${targetIdentity ?: "none"}"
                )
                return@runOnMain
            }
            acceptedAttempt = AcceptedAttempt(
                flowId = flowId,
                component = component,
                displayId = displayId,
                acceptedAtElapsedMs = SystemClock.elapsedRealtime()
            )
            DiagnosticsLog.add(
                "RestoreProjection: event=${if (targetIdentity == null) "CANDIDATE_DEFERRED" else "AWAITING_VERIFICATION"} " +
                    "flowId=$flowId " +
                    "component=${component.flattenToShortString()} displayId=$displayId " +
                    "target=${targetIdentity ?: "none"} retained=true"
            )
            scheduleAcceptedAttemptChecks(flowId)
        }
    }

    fun onLaunchWindowVerified(
        flowId: String,
        packageName: String,
        displayId: Int,
        sessionGeneration: Long?
    ) {
        runOnMain {
            recordVerifiedAttempt(flowId, packageName, displayId, sessionGeneration)
        }
    }

    fun onAccessibilityWindowsChanged() {
        runOnMain {
            verifyAcceptedAttemptIfVisible()
            val request = state.currentRequest() ?: return@runOnMain
            if (ControlAccessibilityService.isPackageVisibleOnDisplay(
                    request.candidate.packageName,
                    request.candidate.displayId
                )
            ) {
                completeNaturalRestore(request)
            }
        }
    }

    private fun scheduleAcceptedAttemptChecks(flowId: String) {
        ACCEPTED_ATTEMPT_CHECK_DELAYS_MS.forEach { delayMs ->
            mainHandler.postDelayed(
                {
                    if (acceptedAttempt?.flowId == flowId) {
                        verifyAcceptedAttemptIfVisible()
                    }
                },
                delayMs
            )
        }
    }

    private fun verifyAcceptedAttemptIfVisible() {
        val attempt = acceptedAttempt ?: return
        val ageMs = SystemClock.elapsedRealtime() - attempt.acceptedAtElapsedMs
        if (ageMs > ACCEPTED_ATTEMPT_TTL_MS) {
            acceptedAttempt = null
            DiagnosticsLog.add(
                "RestoreProjection: event=CANDIDATE_EXPIRED flowId=${attempt.flowId} " +
                    "displayId=${attempt.displayId} ageMs=$ageMs"
            )
            return
        }
        if (DisplaySessionManager.getSelectedDisplayState() !=
                DisplaySessionManager.ExternalDisplayState.ACTIVE ||
            DisplaySessionManager.getSelectedDisplayId() != attempt.displayId ||
            !ControlAccessibilityService.isPackageVisibleOnDisplay(
                attempt.component.packageName,
                attempt.displayId
            )
        ) {
            return
        }
        recordVerifiedAttempt(
            flowId = attempt.flowId,
            packageName = attempt.component.packageName,
            displayId = attempt.displayId,
            sessionGeneration = ControlAccessibilityService.currentExternalSessionIdentity()
                ?.generation
        )
    }

    private fun recordVerifiedAttempt(
        flowId: String,
        packageName: String,
        displayId: Int,
        sessionGeneration: Long?
    ) {
        val attempt = acceptedAttempt
        if (attempt == null ||
            attempt.flowId != flowId ||
            attempt.component.packageName != packageName ||
            attempt.displayId != displayId ||
            SystemClock.elapsedRealtime() - attempt.acceptedAtElapsedMs >
                ACCEPTED_ATTEMPT_TTL_MS ||
            DisplaySessionManager.getSelectedDisplayState() !=
                DisplaySessionManager.ExternalDisplayState.ACTIVE ||
            DisplaySessionManager.getSelectedDisplayId() != displayId
        ) {
            return
        }
        val candidate = ProjectedAppRestoreState.Candidate(
            packageName = attempt.component.packageName,
            className = attempt.component.className,
            displayId = displayId,
            flowId = flowId
        )
        val recorded = state.recordVerified(candidate)
        acceptedAttempt = null
        DiagnosticsLog.add(
            "RestoreProjection: event=CANDIDATE_VERIFIED accepted=$recorded flowId=$flowId " +
                "component=${attempt.component.flattenToShortString()} displayId=$displayId " +
                "observedSessionGeneration=${sessionGeneration ?: "none"}"
        )
    }

    fun onSessionStopped() {
        runOnMain {
            clearAll("session_stopped")
        }
    }

    /** Used only to keep first-use app selection from covering a pending restore question. */
    fun hasVerifiedCandidate(): Boolean = state.hasVerifiedCandidate()

    /** Cancels in-flight UI/verification while retaining a verified app for a reconnect prompt. */
    fun onDisplayDisconnected() {
        runOnMain {
            acceptedAttempt = null
            cancelGrace()
            promptReadyKey = null
            dismissDialogWithoutDecision()
            DiagnosticsLog.add("RestoreProjection: event=DISPLAY_DISCONNECTED candidate=retained")
        }
    }

    override fun onDisplayChanged(info: DisplaySessionManager.ExternalDisplayInfo?) {
        runOnMain {
            val displayState = when (DisplaySessionManager.getSelectedDisplayState()) {
                DisplaySessionManager.ExternalDisplayState.NONE ->
                    ProjectedAppRestoreState.DisplayState.NONE
                DisplaySessionManager.ExternalDisplayState.SUSPENDED ->
                    ProjectedAppRestoreState.DisplayState.SUSPENDED
                DisplaySessionManager.ExternalDisplayState.ACTIVE ->
                    ProjectedAppRestoreState.DisplayState.ACTIVE
            }
            val displayId = DisplaySessionManager.getSelectedDisplayId()
            val request = state.onDisplaySnapshot(displayState, displayId)
            if (displayState != ProjectedAppRestoreState.DisplayState.ACTIVE) {
                acceptedAttempt = null
                cancelGrace()
                promptReadyKey = null
                dismissDialogWithoutDecision()
            } else if (acceptedAttempt?.let { it.displayId != displayId } == true) {
                acceptedAttempt = null
            }
            if (request != null) {
                DiagnosticsLog.add(
                    "RestoreProjection: event=WAKE_DETECTED request=${request.key} " +
                        "component=${component(request).flattenToShortString()} " +
                        "displayId=${request.candidate.displayId} trigger=${request.trigger.name}"
                )
            }
            evaluatePendingRequest()
        }
    }

    private fun evaluatePendingRequest() {
        val request = state.currentRequest() ?: run {
            cancelGrace()
            promptReadyKey = null
            dismissDialogWithoutDecision()
            return
        }
        if (DisplaySessionManager.getSelectedDisplayState() !=
                DisplaySessionManager.ExternalDisplayState.ACTIVE ||
            DisplaySessionManager.getSelectedDisplayId() != request.candidate.displayId
        ) {
            return
        }
        if (ControlAccessibilityService.isPackageVisibleOnDisplay(
                request.candidate.packageName,
                request.candidate.displayId
            )
        ) {
            completeNaturalRestore(request)
            return
        }
        if (promptReadyKey == request.key) {
            maybeShowPrompt()
            return
        }
        if (graceKey == request.key) return
        cancelGrace()
        val runnable = Runnable {
            graceRunnable = null
            graceKey = null
            finishNaturalRestoreGrace(request.key)
        }
        graceRunnable = runnable
        graceKey = request.key
        mainHandler.postDelayed(runnable, NATURAL_RESTORE_GRACE_MS)
        DiagnosticsLog.add(
            "RestoreProjection: event=NATURAL_RESTORE_GRACE request=${request.key} " +
                "delayMs=$NATURAL_RESTORE_GRACE_MS overlayReady=" +
                ControlAccessibilityService.isReady()
        )
    }

    private fun finishNaturalRestoreGrace(requestKey: Long) {
        val request = state.currentRequest()?.takeIf { it.key == requestKey } ?: return
        if (DisplaySessionManager.getSelectedDisplayState() !=
                DisplaySessionManager.ExternalDisplayState.ACTIVE ||
            DisplaySessionManager.getSelectedDisplayId() != request.candidate.displayId
        ) {
            return
        }
        if (ControlAccessibilityService.isPackageVisibleOnDisplay(
                request.candidate.packageName,
                request.candidate.displayId
            )
        ) {
            completeNaturalRestore(request)
            return
        }
        val app = application ?: return
        val target = component(request)
        if (!isComponentAvailable(app, target)) {
            invalidateCandidate("component_unavailable", request)
            return
        }
        val allowed = AppLauncher.isExternalLaunchAllowed(
            app,
            target,
            request.candidate.displayId
        )
        DiagnosticsLog.add(
            "RestoreProjection: event=PREFLIGHT request=${request.key} allowed=$allowed " +
                "component=${target.flattenToShortString()} " +
                "displayId=${request.candidate.displayId}"
        )
        if (allowed != true) {
            invalidateCandidate("display_policy_not_allowed:$allowed", request)
            return
        }
        promptReadyKey = request.key
        maybeShowPrompt()
    }

    private fun maybeShowPrompt() {
        val request = state.currentRequest() ?: return
        if (promptReadyKey != request.key) return
        if (dialog?.isShowing == true && dialogKey == request.key) return
        val host = foregroundHost.get() ?: run {
            logPromptDeferred(request, "no_foreground_activity")
            return
        }
        if (host.isFinishing || host.isDestroyed ||
            !host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            host is AppPickerActivity || displayId(host) != Display.DEFAULT_DISPLAY
        ) {
            logPromptDeferred(request, "no_eligible_foreground_activity")
            return
        }
        val keyguard = host.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            logPromptDeferred(request, "keyguard_locked")
            return
        }
        if (ControlAccessibilityService.isPackageVisibleOnDisplay(
                request.candidate.packageName,
                request.candidate.displayId
            )
        ) {
            completeNaturalRestore(request)
            return
        }
        if (!revalidateRequest(request)) return
        val target = component(request)
        val label = loadActivityLabel(host, target) ?: run {
            invalidateCandidate("component_unavailable", request)
            return
        }
        dismissDialogWithoutDecision()
        val newDialog = AlertDialog.Builder(host)
            .setTitle(R.string.restore_projected_app_title)
            .setMessage(host.getString(R.string.restore_projected_app_message, label))
            .setPositiveButton(R.string.restore_projected_app_action) { _, _ ->
                handlePositiveDecision(request.key, host)
            }
            .setNegativeButton(R.string.restore_projected_app_not_now) { _, _ ->
                handleNegativeDecision(request.key, "not_now")
            }
            .setOnCancelListener {
                handleNegativeDecision(request.key, "cancelled")
            }
            .create()
        dialog = newDialog
        dialogKey = request.key
        newDialog.setOnDismissListener {
            if (dialog === newDialog) {
                dialog = null
                dialogKey = null
            }
        }
        newDialog.show()
        DiagnosticsLog.add(
            "RestoreProjection: event=PROMPT_SHOWN request=${request.key} " +
                "component=${target.flattenToShortString()} " +
                "displayId=${request.candidate.displayId} trigger=${request.trigger.name}"
        )
    }

    private fun handlePositiveDecision(requestKey: Long, host: AppCompatActivity) {
        val request = state.currentRequest()?.takeIf { it.key == requestKey } ?: return
        if (!revalidateRequest(request)) return
        if (ControlAccessibilityService.isPackageVisibleOnDisplay(
                request.candidate.packageName,
                request.candidate.displayId
            )
        ) {
            completeNaturalRestore(request)
            return
        }
        val claimed = state.consumeRequest(requestKey) ?: return
        promptReadyKey = null
        DiagnosticsLog.add(
            "RestoreProjection: event=DECISION request=$requestKey decision=OPEN_AGAIN " +
                "trigger=${claimed.trigger.name}"
        )
        val result = AppLauncher.launchRestoreOnExternalDisplay(
            context = host,
            component = component(claimed),
            expectedDisplayId = claimed.candidate.displayId
        )
        val message = if (result.success) {
            host.getString(
                R.string.app_launch_requested_toast,
                loadActivityLabel(host, component(claimed)) ?: claimed.candidate.packageName
            )
        } else {
            AppLauncher.buildFailureMessage(host, result)
        }
        Toast.makeText(
            host,
            message,
            if (result.success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()
    }

    private fun handleNegativeDecision(requestKey: Long, reason: String) {
        val claimed = state.consumeRequest(requestKey) ?: return
        promptReadyKey = null
        DiagnosticsLog.add(
            "RestoreProjection: event=DECISION request=$requestKey decision=NOT_NOW " +
                "reason=$reason component=${component(claimed).flattenToShortString()}"
        )
    }

    private fun revalidateRequest(request: ProjectedAppRestoreState.Request): Boolean {
        if (DisplaySessionManager.getSelectedDisplayState() !=
                DisplaySessionManager.ExternalDisplayState.ACTIVE ||
            DisplaySessionManager.getSelectedDisplayId() != request.candidate.displayId
        ) {
            return false
        }
        val app = application ?: return false
        val target = component(request)
        if (!isComponentAvailable(app, target)) {
            invalidateCandidate("component_unavailable", request)
            return false
        }
        if (AppLauncher.isExternalLaunchAllowed(app, target, request.candidate.displayId) != true) {
            invalidateCandidate("display_policy_changed", request)
            return false
        }
        return true
    }

    private fun completeNaturalRestore(request: ProjectedAppRestoreState.Request) {
        if (state.consumeRequest(request.key) == null) return
        cancelGrace()
        promptReadyKey = null
        dismissDialogWithoutDecision()
        DiagnosticsLog.add(
            "RestoreProjection: event=NATURAL_RESTORE request=${request.key} " +
                "package=${request.candidate.packageName} displayId=${request.candidate.displayId}"
        )
    }

    private fun invalidateCandidate(
        reason: String,
        request: ProjectedAppRestoreState.Request? = state.currentRequest()
    ) {
        val requestKey = request?.key
        state.clear()
        acceptedAttempt = null
        cancelGrace()
        promptReadyKey = null
        dismissDialogWithoutDecision()
        synchronizeDisplaySnapshot()
        DiagnosticsLog.add(
            "RestoreProjection: event=CANCELLED request=${requestKey ?: "none"} reason=$reason"
        )
    }

    private fun clearAll(reason: String) {
        state.clear()
        acceptedAttempt = null
        cancelGrace()
        promptReadyKey = null
        dismissDialogWithoutDecision()
        DiagnosticsLog.add("RestoreProjection: event=CANCELLED reason=$reason")
    }

    private fun synchronizeDisplaySnapshot() {
        val mapped = when (DisplaySessionManager.getSelectedDisplayState()) {
            DisplaySessionManager.ExternalDisplayState.NONE ->
                ProjectedAppRestoreState.DisplayState.NONE
            DisplaySessionManager.ExternalDisplayState.SUSPENDED ->
                ProjectedAppRestoreState.DisplayState.SUSPENDED
            DisplaySessionManager.ExternalDisplayState.ACTIVE ->
                ProjectedAppRestoreState.DisplayState.ACTIVE
        }
        state.onDisplaySnapshot(mapped, DisplaySessionManager.getSelectedDisplayId())
    }

    private fun cancelGrace() {
        graceRunnable?.let(mainHandler::removeCallbacks)
        graceRunnable = null
        graceKey = null
    }

    private fun dismissDialogWithoutDecision() {
        val current = dialog
        dialog = null
        dialogKey = null
        current?.setOnCancelListener(null)
        current?.dismiss()
    }

    private fun logPromptDeferred(request: ProjectedAppRestoreState.Request, reason: String) {
        DiagnosticsLog.add(
            "RestoreProjection: event=PROMPT_DEFERRED request=${request.key} reason=$reason"
        )
    }

    private fun component(request: ProjectedAppRestoreState.Request): ComponentName =
        ComponentName(request.candidate.packageName, request.candidate.className)

    private fun isComponentAvailable(context: Context, component: ComponentName): Boolean {
        return runCatching {
            val info = getActivityInfo(context.packageManager, component)
            info.enabled && info.applicationInfo.enabled
        }.getOrDefault(false)
    }

    private fun loadActivityLabel(context: Context, component: ComponentName): String? {
        return runCatching {
            val info = getActivityInfo(context.packageManager, component)
            info.loadLabel(context.packageManager).toString().takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private fun getActivityInfo(pm: PackageManager, component: ComponentName) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }

    @Suppress("DEPRECATION")
    private fun displayId(activity: Activity): Int =
        activity.display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun onActivityPostResumed(activity: Activity) {
        val host = activity as? AppCompatActivity ?: return
        if (host is AppPickerActivity || displayId(host) != Display.DEFAULT_DISPLAY) return
        foregroundHost = WeakReference(host)
        mainHandler.post(::maybeShowPrompt)
    }

    override fun onActivityPaused(activity: Activity) {
        if (foregroundHost.get() === activity) {
            foregroundHost.clear()
            dismissDialogWithoutDecision()
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (foregroundHost.get() === activity) {
            foregroundHost.clear()
            dismissDialogWithoutDecision()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
