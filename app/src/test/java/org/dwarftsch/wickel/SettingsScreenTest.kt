package org.dwarftsch.wickel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.dwarftsch.wickel.data.AppSettings
import org.dwarftsch.wickel.data.CertSource
import org.dwarftsch.wickel.data.DataSourceMode
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {

    private val treeUri: Uri = Uri.parse("content://wickel.test.certs/tree/certs")

    @Before
    fun vorbereiten() {
        Robolectric.setupContentProvider(FakeCertProvider::class.java, "wickel.test.certs")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = AppSettings(app)
        settings.mode = DataSourceMode.API
        settings.apiBaseUrl = "https://example.invalid/"
        settings.apiKey = "geheim"
        settings.certFolderUri = treeUri.toString()
        app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val quelle = CertSource(app, settings)
        assertTrue(runCatching { runBlocking { quelle.readCredentials() } }.isSuccess)
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun oeffneEinstellungen() {
        compose.onNodeWithContentDescription("Einstellungen").performClick()
        compose.waitForIdle()
    }

    private fun systemZurueck() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun schalterUmlegenUndSystemZurueck() {
        compose.waitForIdle()
        oeffneEinstellungen()
        compose.onNodeWithText("Zertifikate gefunden").assertExists()
        repeat(3) {
            compose.onNodeWithText("Stoffwindel-Funktion").performClick()
            compose.waitForIdle()
        }
        systemZurueck()
        compose.onNodeWithContentDescription("Einstellungen").assertExists()
    }

    @Test
    fun mehrfachRein_undRaus() {
        compose.waitForIdle()
        repeat(3) {
            oeffneEinstellungen()
            compose.onNodeWithText("Stoffwindel-Funktion").performClick()
            compose.waitForIdle()
            systemZurueck()
        }
        compose.onNodeWithContentDescription("Einstellungen").assertExists()
    }

    @Test
    fun modusWechselnUndSchalterUmlegen() {
        compose.waitForIdle()
        oeffneEinstellungen()
        compose.onNodeWithText("Server (API-Key)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stoffwindel-Funktion").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Server (mTLS-API)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stoffwindel-Funktion").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Lokal (SQLite)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stoffwindel-Funktion").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Server (mTLS-API)").performClick()
        compose.waitForIdle()
        systemZurueck()
        compose.onNodeWithContentDescription("Einstellungen").assertExists()
    }

    @Test
    fun erklaerungsDialogeUndErneutPruefen() {
        compose.waitForIdle()
        oeffneEinstellungen()
        compose.onNodeWithText("Erneut prüfen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Aufbau API").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Schließen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Stoffwindel-Funktion").performClick()
        compose.waitForIdle()
        systemZurueck()
        compose.onNodeWithContentDescription("Einstellungen").assertExists()
    }
}
