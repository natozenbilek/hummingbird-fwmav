package com.natozenbilek.remotecontrol

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlightScreenUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun flightScreen_showsTelemetryAndJoysticksTogether() {
        // MainActivity already shows FlightScreen by default.
        // Here we verify that critical UI elements are displayed simultaneously.

        // Telemetry card title
        composeRule.onNodeWithText("Telemetry").assertIsDisplayed()

        // Joystick titles
        composeRule.onNodeWithText("Throttle / Yaw").assertIsDisplayed()
        composeRule.onNodeWithText("Roll / Pitch").assertIsDisplayed()

        // Flight controls band title
        composeRule.onNodeWithText("FLIGHT CONTROLS").assertIsDisplayed()
    }
}





