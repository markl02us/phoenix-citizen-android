package com.phoenix.citizen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI smoke test: launch the app, open the Alerts tab, confirm the area-alerts
 * screen renders and the "Add an area" affordance is present. The app forces the
 * Italian locale, so we match Italian strings.
 */
@RunWith(AndroidJUnit4::class)
class AreaAlertsUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun alertsTab_opensAndShowsEditor() {
        // Bottom nav label (Italian) for the Alerts tab.
        composeRule.onNodeWithText("Avvisi").performClick()
        composeRule.waitForIdle()

        // Screen heading + add affordance render.
        composeRule.onNodeWithText("Avvisi per le mie aree").assertIsDisplayed()
        composeRule.onNodeWithText("Aggiungi un'area").assertIsDisplayed()

        // Opening the editor shows the radius slider label.
        composeRule.onNodeWithText("Aggiungi un'area").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Salva area").assertIsDisplayed()
    }
}
