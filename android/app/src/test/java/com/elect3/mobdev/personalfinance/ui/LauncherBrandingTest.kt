package com.elect3.mobdev.personalfinance.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Guards the packaged resource chain and reuse of the existing owl rather than a replacement logo. */
class LauncherBrandingTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/$path"))
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun launcherUsesAdaptiveOwlResourcesAtTheMinimumSupportedApi() {
        val app = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(android, "icon"))
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(android, "roundIcon"))
        val adaptive = xml("res/mipmap-anydpi-v26/ic_launcher.xml")
        assertEquals("adaptive-icon", adaptive.documentElement.tagName)
        val foreground = adaptive.getElementsByTagName("foreground").item(0) as org.w3c.dom.Element
        assertEquals("@drawable/owli_launcher_foreground", foreground.getAttributeNS(android, "drawable"))
    }

    @Test fun foregroundPreservesEveryExistingOwlPathInsideTheAdaptiveSafeZone() {
        fun paths(path: String): List<String> {
            val rows = xml(path).getElementsByTagName("path")
            return (0 until rows.length).map { (rows.item(it) as org.w3c.dom.Element).getAttributeNS(android, "pathData") }
        }
        assertEquals(paths("res/drawable/ic_owli_mark.xml"), paths("res/drawable/owli_launcher_foreground.xml"))
        val group = xml("res/drawable/owli_launcher_foreground.xml").getElementsByTagName("group").item(0) as org.w3c.dom.Element
        assertEquals("30", group.getAttributeNS(android, "translateX"))
        assertEquals("2", group.getAttributeNS(android, "scaleX"))
    }
}
