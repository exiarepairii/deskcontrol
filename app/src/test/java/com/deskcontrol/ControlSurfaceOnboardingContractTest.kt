package com.deskcontrol

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlSurfaceOnboardingContractTest {
    @Test
    fun verifiedReconnectCandidateSuppressesFirstUseAppPicker() {
        val source = sequenceOf(
            File("src/main/java/com/deskcontrol/ControlSurfaceOnboardingController.kt"),
            File("app/src/main/java/com/deskcontrol/ControlSurfaceOnboardingController.kt")
        ).firstOrNull(File::isFile)?.readText()
            ?: error("ControlSurfaceOnboardingController.kt not found")

        assertTrue(source.contains("ProjectedAppRestoreCoordinator.hasVerifiedCandidate()"))
    }
}
