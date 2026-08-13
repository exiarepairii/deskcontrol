package com.deskcontrol

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectedAppRestoreCoordinatorContractTest {
    private fun source(): String = sequenceOf(
        File("src/main/java/com/deskcontrol/ProjectedAppRestoreCoordinator.kt"),
        File("app/src/main/java/com/deskcontrol/ProjectedAppRestoreCoordinator.kt")
    ).firstOrNull(File::isFile)?.readText()
        ?: error("ProjectedAppRestoreCoordinator.kt not found")

    @Test
    fun leavingActiveDisplayCancelsUnverifiedAcceptedAttempt() {
        val callback = source().substringAfter(
            "override fun onDisplayChanged"
        ).substringBefore("private fun evaluatePendingRequest")

        assertTrue(callback.contains("displayState != ProjectedAppRestoreState.DisplayState.ACTIVE"))
        assertTrue(callback.contains("acceptedAttempt?.let { it.displayId != displayId } == true"))
        assertTrue(callback.contains("acceptedAttempt = null"))
    }

    @Test
    fun restorePromptDoesNotWaitForAccessibilityOverlayReadiness() {
        val evaluation = source().substringAfter(
            "private fun evaluatePendingRequest"
        ).substringBefore("private fun finishNaturalRestoreGrace")

        assertTrue(evaluation.contains("NATURAL_RESTORE_GRACE_MS"))
        assertTrue(!evaluation.contains("currentExternalSessionIdentity"))
        assertTrue(!evaluation.contains("WAITING_READY"))
    }

    @Test
    fun acceptedLaunchIsRetainedWhenAccessibilityTargetIsNotReady() {
        val accepted = source().substringAfter(
            "fun onUserLaunchAccepted"
        ).substringBefore("fun onLaunchWindowVerified")

        assertTrue(accepted.contains("acceptedAttempt = AcceptedAttempt"))
        assertTrue(accepted.contains("CANDIDATE_DEFERRED"))
        assertTrue(accepted.contains("retained=true"))
    }
}
