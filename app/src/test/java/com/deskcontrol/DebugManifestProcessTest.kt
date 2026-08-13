package com.deskcontrol

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebugManifestProcessTest {
    @Test
    fun accessibilityServiceAndDeviceTestReceiverStayInAppProcess() {
        val manifest = sequenceOf(
            File("src/debug/AndroidManifest.xml"),
            File("app/src/debug/AndroidManifest.xml")
        ).firstOrNull(File::isFile)
        assertNotNull("Debug AndroidManifest.xml not found", manifest)

        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(requireNotNull(manifest))

        assertComponentHasNoProcess(
            document.getElementsByTagName("service"),
            ".ControlAccessibilityService"
        )
        assertComponentHasNoProcess(
            document.getElementsByTagName("receiver"),
            ".GestureTestCommandReceiver"
        )
        assertReceiverRequiresDumpPermission(document)
    }

    private fun assertComponentHasNoProcess(
        nodes: org.w3c.dom.NodeList,
        componentName: String
    ) {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val element = (0 until nodes.length)
            .map { nodes.item(it) as org.w3c.dom.Element }
            .firstOrNull {
                it.getAttributeNS(androidNamespace, "name") == componentName
            }
        assertNotNull("Missing debug component $componentName", element)
        assertEquals("", requireNotNull(element).getAttributeNS(androidNamespace, "process"))
    }

    private fun assertReceiverRequiresDumpPermission(document: org.w3c.dom.Document) {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val nodes = document.getElementsByTagName("receiver")
        val receiver = (0 until nodes.length)
            .map { nodes.item(it) as org.w3c.dom.Element }
            .firstOrNull {
                it.getAttributeNS(androidNamespace, "name") == ".GestureTestCommandReceiver"
            }
        assertNotNull("Missing debug gesture test receiver", receiver)
        assertEquals(
            "android.permission.DUMP",
            requireNotNull(receiver).getAttributeNS(androidNamespace, "permission")
        )
    }
}
