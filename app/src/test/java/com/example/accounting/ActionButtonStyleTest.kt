package com.example.accounting

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.accounting.presentation.components.ActionButton
import com.example.accounting.presentation.components.ActionButtonStyle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The DESTRUCTIVE [ActionButtonStyle] behaves as a normal button (label, click, enabled state); existing styles are unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActionButtonStyleTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test fun everyStyleRendersItsLabelAndFiresOnClick() {
        var clicks = 0
        composeTestRule.setContent {
            androidx.compose.foundation.layout.Column {
                ActionButtonStyle.entries.forEach { style ->
                    ActionButton(text = "Btn ${style.name}", style = style, onClick = { clicks++ })
                }
            }
        }
        ActionButtonStyle.entries.forEach { style ->
            composeTestRule.onNodeWithText("Btn ${style.name}").assertIsEnabled().performClick()
        }
        assertEquals(ActionButtonStyle.entries.size, clicks)
    }

    @Test fun destructiveStyle_disabled_doesNotFire() {
        var clicks = 0
        composeTestRule.setContent {
            ActionButton(text = "Delete", style = ActionButtonStyle.DESTRUCTIVE, enabled = false, onClick = { clicks++ })
        }
        composeTestRule.onNodeWithText("Delete").assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test fun defaultStyleIsStillPrimary_andDestructiveIsAnExplicitChoice() {
        assertEquals(ActionButtonStyle.PRIMARY, ActionButtonStyle.entries.first())
        assertEquals(listOf("PRIMARY", "SECONDARY", "TEXT", "DESTRUCTIVE"), ActionButtonStyle.entries.map { it.name })
    }
}
