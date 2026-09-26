package pt.cpcompanion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LegalDocumentDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun privacyIsReadableOfflineAndCanBeDismissed() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                LegalDocumentDialog("Privacy policy", "privacy.txt") { dismissed = true }
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("CP Companion privacy policy", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("CP Companion privacy policy", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }
}
