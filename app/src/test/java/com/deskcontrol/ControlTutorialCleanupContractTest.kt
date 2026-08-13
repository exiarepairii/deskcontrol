package com.deskcontrol

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTutorialCleanupContractTest {
    @Test
    fun displaySessionTeardownRestoresTutorialCursorOverride() {
        val source = sequenceOf(
            File("src/main/java/com/deskcontrol/ControlAccessibilityService.kt"),
            File("app/src/main/java/com/deskcontrol/ControlAccessibilityService.kt")
        ).firstOrNull(File::isFile)?.readText()
        requireNotNull(source) { "ControlAccessibilityService.kt not found" }

        val teardown = functionBody(
            source,
            "private fun clearDisplaySessionResources()"
        )
        assertTrue(teardown.contains("removeControlTutorial()"))
        assertFalse(teardown.contains("tutorialPreviousCursorForceVisible = null"))

        val cleanup = functionBody(source, "private fun removeControlTutorial()")
        assertFalse(cleanup.contains("controlTutorialView ?: return"))
        assertInOrder(
            cleanup,
            "val previousCursorForceVisible = tutorialPreviousCursorForceVisible",
            "controlTutorialView = null",
            "tutorialPreviousCursorForceVisible = null",
            "previousCursorForceVisible?.let(::setCursorForceVisible)"
        )
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        require(signatureIndex >= 0) { "Missing function: $signature" }
        val openingBrace = source.indexOf('{', signatureIndex)
        require(openingBrace >= 0) { "Missing body for: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(openingBrace + 1, index)
                    }
                }
            }
        }
        error("Unterminated body for: $signature")
    }

    private fun assertInOrder(source: String, vararg fragments: String) {
        var previousIndex = -1
        fragments.forEach { fragment ->
            val index = source.indexOf(fragment)
            assertTrue("Missing fragment: $fragment", index >= 0)
            assertTrue("Out-of-order fragment: $fragment", index > previousIndex)
            previousIndex = index
        }
    }
}
