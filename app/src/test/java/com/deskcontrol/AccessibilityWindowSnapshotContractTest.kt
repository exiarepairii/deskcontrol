package com.deskcontrol

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityWindowSnapshotContractTest {
    @Test
    fun launchVerificationEnumeratesWindowsOnEveryDisplay() {
        val source = sequenceOf(
            File("src/main/java/com/deskcontrol/ControlAccessibilityService.kt"),
            File("app/src/main/java/com/deskcontrol/ControlAccessibilityService.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("ControlAccessibilityService.kt not found")
        val method = source.substringAfter(
            "private fun snapshotWindows(): List<AccessibilityWindowInfo>"
        ).substringBefore("private fun logLaunchObservation")

        assertTrue(method.contains("windowsOnAllDisplays"))
        assertTrue(method.contains("windowsByDisplay.valueAt(index)"))
        assertFalse(method.contains("return windows?.toList()"))
    }

    @Test
    fun acceptedExternalLaunchCanRestartAnExhaustedOverlayAttach() {
        val serviceSource = sequenceOf(
            File("src/main/java/com/deskcontrol/ControlAccessibilityService.kt"),
            File("app/src/main/java/com/deskcontrol/ControlAccessibilityService.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("ControlAccessibilityService.kt not found")
        val reattach = serviceSource.substringAfter(
            "private fun scheduleReattachAfterExternalLaunch"
        ).substringBefore("private fun isPackageVisible")
        assertTrue(reattach.contains("ExternalDisplaySessionLifecycle.State.EXHAUSTED"))
        assertTrue(reattach.contains("attachToDisplay(info)"))
        assertTrue(reattach.contains("pendingPostLaunchReattach"))

        val attachFailure = serviceSource.substringAfter(
            "private fun attemptDisplaySessionAttach"
        ).substringBefore("private fun tryAttachToDisplay")
        assertTrue(attachFailure.contains("maybeRunPostLaunchReattach()"))

        val launcherSource = sequenceOf(
            File("src/main/java/com/deskcontrol/AppLauncher.kt"),
            File("app/src/main/java/com/deskcontrol/AppLauncher.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("AppLauncher.kt not found")
        val accepted = launcherSource.substringAfter(
            "private fun externalRequestAccepted"
        ).substringBefore("private fun launchException")
        assertTrue(accepted.contains("requestReattachAfterExternalLaunch"))
    }
}
