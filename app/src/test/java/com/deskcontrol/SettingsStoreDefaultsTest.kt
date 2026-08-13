package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsStoreDefaultsTest {
    @Test
    fun motionMouseHapticFeedbackDefaultsToDisabled() {
        assertFalse(SettingsStore.DEFAULT_RAY_HAPTIC_FEEDBACK_ENABLED)
        assertEquals(
            SettingsStore.DEFAULT_RAY_HAPTIC_FEEDBACK_ENABLED,
            SettingsStore.rayHapticFeedbackEnabled
        )
    }
}
