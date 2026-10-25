package org.squaddle.app.ui.join

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.InviteLink
import org.squaddle.core.sync.PushOutcome

/** Real Compose semantics on the JVM via Robolectric (see [org.squaddle.app.ui.onboarding.OnboardingScreenTest]'s
 * own justification). Drives the deep-link-launch -> confirm -> join path through the actual
 * Composables, not just the ViewModel's state machine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JoinScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmingAnOpenInviteJoinsAndFiresOnJoined() {
        var joined = false
        var cancelled = false
        val link = InviteLink("relay.squaddle.org", ByteArray(32), ByteArray(16), ByteArray(32))
        val gateway = FakeSyncGateway().apply {
            peekRoomName = "Camping Trip"
            joinOutcome = PushOutcome.Ok
        }
        val viewModel = JoinViewModel(gateway)

        composeRule.setContent {
            JoinScreen(viewModel = viewModel, onJoined = { joined = true }, onCancelled = { cancelled = true })
        }
        composeRule.runOnIdle { viewModel.openWithLink(link) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Camping Trip").assertIsDisplayed()
        composeRule.onNodeWithText("Join").performClick()
        composeRule.waitForIdle()

        assert(joined) { "onJoined was never called" }
        assert(!cancelled)
    }

    @Test
    fun cancellingTheConfirmStepFiresOnCancelled() {
        var cancelled = false
        val link = InviteLink("relay.squaddle.org", ByteArray(32), ByteArray(16), ByteArray(32))
        val gateway = FakeSyncGateway().apply { peekRoomName = "Book Club" }
        val viewModel = JoinViewModel(gateway)

        composeRule.setContent {
            JoinScreen(viewModel = viewModel, onJoined = {}, onCancelled = { cancelled = true })
        }
        composeRule.runOnIdle { viewModel.openWithLink(link) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Cancel").performClick()

        assert(cancelled) { "onCancelled was never called" }
    }

    @Test
    fun joinFailureShowsErrorAndDismissFiresOnCancelled() {
        var cancelled = false
        val link = InviteLink("relay.squaddle.org", ByteArray(32), ByteArray(16), ByteArray(32))
        val gateway = FakeSyncGateway().apply {
            peekRoomName = "Book Club"
            joinOutcome = PushOutcome.Error(409, "room_full", "this room is full")
        }
        val viewModel = JoinViewModel(gateway)

        composeRule.setContent {
            JoinScreen(viewModel = viewModel, onJoined = {}, onCancelled = { cancelled = true })
        }
        composeRule.runOnIdle { viewModel.openWithLink(link) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Join").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("this room is full").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()

        assert(cancelled) { "onCancelled was never called after dismissing the error" }
    }
}
