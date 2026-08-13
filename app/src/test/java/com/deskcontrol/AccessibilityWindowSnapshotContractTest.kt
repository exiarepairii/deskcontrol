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
}
