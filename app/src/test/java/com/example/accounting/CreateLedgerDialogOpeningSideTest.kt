package com.example.accounting

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.accounting.core.common.DrCr
import com.example.accounting.core.common.Money
import com.example.accounting.domain.accounting.AccountGroup
import com.example.accounting.domain.accounting.Ledger
import com.example.accounting.domain.accounting.PrimaryGroup
import com.example.accounting.presentation.components.CreateLedgerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 8, L4 - in the Edit Ledger dialog, choosing a group (the current one or another) must not overwrite the
 * ledger's existing opening-balance side; a NEW ledger still takes the chosen group's natural side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreateLedgerDialogOpeningSideTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val companyId = "C1"
    private val groups = listOf(
        AccountGroup("G_DEBTORS", companyId, "Sundry Debtors", PrimaryGroup.ASSETS),
        AccountGroup("G_FIXED", companyId, "Fixed Assets", PrimaryGroup.ASSETS),
        AccountGroup("G_CREDITORS", companyId, "Sundry Creditors", PrimaryGroup.LIABILITIES)
    )

    private class Captured { var side: DrCr? = null; var groupId: String? = null; var amount: Money? = null; var calls = 0 }

    private fun showEdit(existing: Ledger, c: Captured) {
        composeTestRule.setContent {
            CreateLedgerDialog(
                groups = groups, existingLedger = existing, onDismiss = {},
                onCreateLedger = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                onUpdateLedger = { _, _, group, amount, side, _, _, _, _, _, _, _, _, _, _, _, _, _ -> c.groupId = group; c.amount = amount; c.side = side; c.calls++ }
            )
        }
    }

    private fun pickGroup(label: String) {
        composeTestRule.onNodeWithText("Under Account Group *").performClick()
        composeTestRule.onNodeWithText(label).performClick()
    }

    private fun customerWithCreditOpening() = Ledger(
        ledgerId = "L1", companyId = companyId, groupId = "G_DEBTORS", name = "Customer With Advance",
        openingBalance = Money.fromPaise(5_000_00L), openingBalanceType = DrCr.CREDIT, primaryGroup = PrimaryGroup.ASSETS
    )

    @Test fun editMode_reSelectingTheCurrentGroup_keepsTheExistingOpeningSide() {
        val c = Captured(); showEdit(customerWithCreditOpening(), c)
        pickGroup("Sundry Debtors (Assets)")          // natural side is DEBIT; the stored side is CREDIT
        composeTestRule.onNodeWithText("Save Changes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, c.calls)
        assertEquals("G_DEBTORS", c.groupId)
        assertEquals(DrCr.CREDIT, c.side)
        assertEquals(5_000_00L, c.amount!!.paise)
    }

    @Test fun editMode_selectingAnotherGroup_keepsTheExistingOpeningSide() {
        val c = Captured(); showEdit(customerWithCreditOpening(), c)
        pickGroup("Fixed Assets (Assets)")            // also a DEBIT-natural group
        composeTestRule.onNodeWithText("Save Changes").performClick()
        composeTestRule.waitForIdle()
        assertEquals("G_FIXED", c.groupId)
        assertEquals(DrCr.CREDIT, c.side)
    }

    @Test fun editMode_selectingAGroupWithTheOppositeNaturalSide_stillKeepsTheExistingSide() {
        val debit = customerWithCreditOpening().copy(openingBalanceType = DrCr.DEBIT)
        val c = Captured(); showEdit(debit, c)
        pickGroup("Sundry Creditors (Liabilities)")   // natural side is CREDIT; the stored side is DEBIT
        composeTestRule.onNodeWithText("Save Changes").performClick()
        composeTestRule.waitForIdle()
        assertEquals("G_CREDITORS", c.groupId)
        assertEquals(DrCr.DEBIT, c.side)
    }

    @Test fun editMode_withoutTouchingTheGroup_savesTheStoredOpeningUnchanged() {
        val c = Captured(); showEdit(customerWithCreditOpening(), c)
        composeTestRule.onNodeWithText("Save Changes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(DrCr.CREDIT, c.side); assertEquals(5_000_00L, c.amount!!.paise); assertEquals("G_DEBTORS", c.groupId)
    }

    @Test fun createMode_stillTakesTheChosenGroupsNaturalSide() {
        var side: DrCr? = null
        composeTestRule.setContent {
            CreateLedgerDialog(
                groups = groups, onDismiss = {},
                onCreateLedger = { _, _, _, s, _, _, _, _, _, _, _, _, _, _, _, _, _ -> side = s }
            )
        }
        composeTestRule.onNodeWithText("Ledger Name *").performClick()
        pickGroup("Sundry Creditors (Liabilities)")
        composeTestRule.onNodeWithText("Ledger Name *").performClick()
        // the Save button needs a name; type it through the semantic text field
        composeTestRule.onNodeWithText("Ledger Name *").performTextInputSafe("New Ledger")
        composeTestRule.onNodeWithText("Save Ledger").performClick()
        composeTestRule.waitForIdle()
        assertTrue("a new ledger under a Liability group takes the CREDIT natural side", side == DrCr.CREDIT)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTextInputSafe(text: String) =
        performTextInput(text)
}
