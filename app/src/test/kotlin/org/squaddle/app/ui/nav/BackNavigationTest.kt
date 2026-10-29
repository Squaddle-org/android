package org.squaddle.app.ui.nav

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.data.RoomMaterialization
import org.squaddle.app.push.FakeRoomMuteStore
import org.squaddle.app.ui.invite.InviteScreen
import org.squaddle.app.ui.invite.InviteViewModel
import org.squaddle.app.ui.onboarding.OnboardingScreen
import org.squaddle.app.ui.onboarding.OnboardingViewModel
import org.squaddle.app.ui.room.RoomScreen
import org.squaddle.app.ui.room.RoomViewModel
import org.squaddle.core.membership.Role
import org.squaddle.core.ops.Idea
import org.squaddle.core.ops.LwwStamp
import org.squaddle.core.ops.RoomState

/**
 * Real Compose semantics + a real [androidx.activity.OnBackPressedDispatcher] on the JVM via
 * Robolectric (same discipline as [org.squaddle.app.ui.join.JoinScreenTest]/
 * [org.squaddle.app.ui.onboarding.OnboardingScreenTest] - `createComposeRule()`'s default host is
 * a real `ComponentActivity`, so `BackHandler` composables register with a real dispatcher and
 * [Espresso.pressBack] actually exercises them, not just a mocked callback).
 *
 * Covers the in-screen `BackHandler` paths from the back-navigation fix: idea detail -> room,
 * onboarding step-back, and the new/edit-idea + invite-options "discard changes?" confirmations.
 * The route-to-route paths (Room -> RoomsList, Members/Invite/Settings -> previous screen, the
 * Join flow landing back on RoomsList) live in `MainActivity.kt`'s real `NavHost` and are
 * exercised by the manual adb pass in `MANUAL_TESTING.md`, not here - a `NavHost` built from a
 * `FragmentActivity` plus this app's full manual DI container isn't practical to stand up under
 * Robolectric without duplicating most of `AppContainer`/`SquaddleApplication`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val room = ByteArray(32) { 0x01 }
    private val myKey = ByteArray(32) { 0xAA.toByte() }

    private fun idea(id: ByteArray, title: String): Idea {
        val stamp = LwwStamp(1, myKey)
        return Idea(
            id = id, createdBy = myKey, title = title, titleStamp = stamp,
            desc = null, descStamp = stamp, tags = emptyList(), tagsStamp = stamp,
            status = null, statusStamp = stamp, deleted = false, deletedStamp = stamp,
        )
    }

    @Test
    fun backFromIdeaDetailReturnsToTheIdeaListWithoutCallingOnBack() {
        val ideaA = idea(ByteArray(16) { 1 }, "Camping trip")
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(ideas = mapOf("01" to ideaA)), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val viewModel = RoomViewModel(room, myKey, gateway, FakeRoomMuteStore())
        var onBackCalls = 0

        composeRule.setContent {
            RoomScreen(viewModel = viewModel, roomName = "Test Room", onBack = { onBackCalls++ })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Camping trip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Comments").assertIsDisplayed() // confirms we're in idea detail

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Camping trip").assertIsDisplayed() // back on the idea list
        assert(onBackCalls == 0) { "back from idea detail must not call onBack (that would exit the room) - got $onBackCalls calls" }
    }

    @Test
    fun onboardingStepBackReturnsFromShowSeedToModeSelect() {
        val viewModel = OnboardingViewModel(
            crypto = org.squaddle.app.data.FakeCrypto,
            identityVault = org.squaddle.app.crypto.FakeIdentityVault(),
            openSession = { _, _, _ -> FakeSyncGateway() },
        )

        composeRule.setContent {
            OnboardingScreen(viewModel = viewModel, onFinished = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Create a new identity").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your recovery phrase").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Create a new identity").assertIsDisplayed() // back at ModeSelect
    }

    @Test
    fun backFromANewIdeaDialogWithTypedTextAsksToDiscard() {
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val viewModel = RoomViewModel(room, myKey, gateway, FakeRoomMuteStore())

        composeRule.setContent {
            RoomScreen(viewModel = viewModel, roomName = "Test Room", onBack = {})
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("New idea").performClick() // the FAB
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ideaTitleField").performTextInput("Something I typed")
        composeRule.waitForIdle()

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        // "Keep editing" must leave the typed text intact, not silently drop it.
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ideaTitleField").assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("discardChangesConfirm").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ideaTitleField").assertIsNotDisplayed()
    }

    @Test
    fun backFromDirtyInviteOptionsAsksToDiscard() {
        val gateway = FakeSyncGateway()
        val viewModel = InviteViewModel(room, gateway)
        var onBackCalls = 0

        composeRule.setContent {
            InviteScreen(viewModel = viewModel, onBack = { onBackCalls++ })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("requiresApprovalSwitch").performClick() // moves the form off its defaults
        composeRule.waitForIdle()

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        assert(onBackCalls == 0) { "a dirty form must ask before leaving, not call onBack immediately - got $onBackCalls calls" }

        composeRule.onNodeWithTag("discardChangesConfirm").performClick()
        composeRule.waitForIdle()

        assert(onBackCalls == 1) { "confirming discard must actually call onBack - got $onBackCalls calls" }
    }

    @Test
    fun untouchedInviteOptionsLeaveImmediatelyViaTheBackIcon() {
        // Deliberately the nav-icon tap, not Espresso.pressBack(): when the form isn't dirty,
        // InviteScreen's own BackHandler is disabled (see its own doc comment) and system back's
        // "nothing intercepted it, pop the back stack" fallback is NavHost's job, not something
        // this screen does on its own in isolation - the nav-icon's onClick (requestBack) is the
        // one path entirely owned by this composable, so that's what's meaningful to unit-test
        // here. The equivalent system-back path is covered by the real NavHost manually (see
        // MANUAL_TESTING.md).
        val gateway = FakeSyncGateway()
        val viewModel = InviteViewModel(room, gateway)
        var onBackCalls = 0

        composeRule.setContent {
            InviteScreen(viewModel = viewModel, onBack = { onBackCalls++ })
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()

        assert(onBackCalls == 1) { "an untouched form has nothing to discard - the back icon should leave immediately, got $onBackCalls calls" }
    }
}
