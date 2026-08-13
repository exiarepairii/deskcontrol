package com.deskcontrol

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectedAppRestoreCoordinatorContractTest {
    @Test
    fun leavingActiveDisplayCancelsUnverifiedAcceptedAttempt() {
        val source = sequenceOf(
            File("src/main/java/com/deskcontrol/ProjectedAppRestoreCoordinator.kt"),
            File("app/src/main/java/com/deskcontrol/ProjectedAppRestoreCoordinator.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("ProjectedAppRestoreCoordinator.kt not found")
        val callback = source.substringAfter(
            "override fun onDisplayChanged"
        ).substringBefore("private fun evaluatePendingRequest")

        assertTrue(callback.contains("displayState != ProjectedAppRestoreState.DisplayState.ACTIVE"))
        assertTrue(callback.contains("acceptedAttempt = null"))
    }
}
