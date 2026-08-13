package com.deskcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayFlagFormatterTest {
    @Test
    fun decodesUntrustedMiPlayFlagsWithoutSignedHex() {
        val flags = 0x82040008.toInt()

        assertFalse(DisplayFlagFormatter.isTrusted(flags))
        assertEquals(
            "0x82040008(PRESENTATION|trusted=false|private=false|unknown=0x82040000)",
            DisplayFlagFormatter.format(flags)
        )
    }

    @Test
    fun decodesTrustedPresentationFlags() {
        val flags = 0x0000008B

        assertTrue(DisplayFlagFormatter.isTrusted(flags))
        assertEquals(
            "0x0000008B(PROTECTED|SECURE|PRESENTATION|TRUSTED|trusted=true|private=false|unknown=0x00000000)",
            DisplayFlagFormatter.format(flags)
        )
    }
}
