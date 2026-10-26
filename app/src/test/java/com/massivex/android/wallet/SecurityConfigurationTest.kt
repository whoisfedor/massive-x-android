package com.massivex.android.wallet

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SecurityConfigurationTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))
    @Test fun manifestOnlyRequiredPermissionsAndDisablesBackups() {
        val d = xml("src/main/AndroidManifest.xml"); val permissions = d.getElementsByTagName("uses-permission")
        assertEquals(5, permissions.length)
        assertEquals(setOf("android.permission.USE_BIOMETRIC", "android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.ACCESS_NETWORK_STATE"), (0 until permissions.length).map { permissions.item(it).attributes.getNamedItem("android:name").nodeValue }.toSet())
        val app = d.getElementsByTagName("application").item(0).attributes
        assertEquals("@xml/network_security_config", app.getNamedItem("android:networkSecurityConfig").nodeValue)
        assertEquals("false", app.getNamedItem("android:allowBackup").nodeValue)
        assertEquals("false", app.getNamedItem("android:usesCleartextTraffic").nodeValue)
        assertEquals("@xml/backup_rules", app.getNamedItem("android:fullBackupContent").nodeValue)
        assertEquals("@xml/data_extraction_rules", app.getNamedItem("android:dataExtractionRules").nodeValue)
    }
    @Test fun excludesEveryBackupGenerationAndTransferDomain() {
        val expected = setOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        val files = listOf("src/main/res/xml/backup_rules.xml" to listOf("full-backup-content"), "src/main/res/xml/data_extraction_rules.xml" to listOf("cloud-backup", "device-transfer"))
        files.forEach { (file, groups) -> val d = xml(file); groups.forEach { group ->
            val parent = d.getElementsByTagName(group).item(0) as org.w3c.dom.Element
            val exclusions = parent.getElementsByTagName("exclude")
            val domains = (0 until exclusions.length).map { i -> val e = exclusions.item(i) as org.w3c.dom.Element; assertEquals(".", e.getAttribute("path")); e.getAttribute("domain") }.toSet()
            assertEquals(expected, domains)
        } }
    }
}
