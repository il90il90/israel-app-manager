package com.il90.appmanager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing is installed without Android's confirmation dialog. An installer that can skip it
 * looks like a dropper to Play Protect, so both halves of that ability stay out of the app:
 * the permission, and the session flag that uses it.
 */
class InstallPolicyTest {
    private fun source(path: String) = File(path).readText()

    /** Without comments, so a permission or flag that is only explained does not count. */
    private fun String.withoutComments() = this
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the manifest does not ask to update packages without user action`() {
        val manifest = source("src/main/AndroidManifest.xml").withoutComments()
        assertTrue(manifest.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(manifest.contains("UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
    }

    @Test
    fun `install sessions ask for the user's confirmation`() {
        val installer = source("src/main/java/com/il90/appmanager/install/InstallManager.kt").withoutComments()
        assertTrue(installer.contains("setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)"))
        assertFalse(installer.contains("USER_ACTION_NOT_REQUIRED"))
    }
}
