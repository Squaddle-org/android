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
import org.squaddle.app.ui.room.IdeaDetailScreen
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
 * Covers the in-screen `BackHandler` paths from the back-navigation fix: onboarding step-back,
 * and the new/edit-idea + invite-options "discard changes?" confirmations - plus (idea detail
 * having since become a real `Route.Idea` destination, not a `BackHandler`-driven
 * `RoomScreen` state) that `RoomScreen` opens it via a plain callback and that
 * `IdeaDetailScreen`'s own system back is a plain `onBack()` call, not an interception, now that
 * `NavHost`'s own pop is what actually returns to the room. The route-to-route paths themselves
 * (Room -> RoomsList, Members/Invite/Settings/Idea/Event -> previous screen, the Join flow
 * landing back on RoomsList) live in `MainActivity.kt`'s real `NavHost` and are exercised by the
 * manual adb pass in `MANUAL_TESTING.md`, not here - a `NavHost` built from a `FragmentActivity`
 * plus this app's full manual DI container isn't practical to stand up under Robolectric without
 * duplicating most of `AppContainer`/`SquaddleApplication`.
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
    fun tappingAnIdeaInTheRoomListOpensItsDetailRoute() {
        val ideaA = idea(ByteArray(16) { 1 }, "Camping trip")
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(ideas = mapOf("01" to ideaA)), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val viewModel = RoomViewModel(room, myKey, gateway, FakeRoomMuteStore())
        var openedIdeaId: ByteArray? = null

        composeRule.setContent {
            RoomScreen(viewModel = viewModel, roomName = "Test Room", onBack = {}, onOpenIdea = { openedIdeaId = it })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Camping trip").performClick()
        composeRule.waitForIdle()

        // RoomScreen itself no longer renders idea detail inline (Route.Idea does, in
        // MainActivity.kt's NavHost) - tapping the row just reports which idea to open.
        assert(openedIdeaId.contentEquals(ideaA.id)) { "tapping the idea row should report its id via onOpenIdea" }
    }

    @Test
    fun backIconOnIdeaDetailScreenCallsOnBack() {
        // Deliberately the back ICON tap, not Espresso.pressBack() - same reasoning as
        // untouchedInviteOptionsLeaveImmediatelyViaTheBackIcon above: idea detail is a real
        // Route.Idea destination now, with no BackHandler of its own any more (none is needed -
        // NavHost's own default pop is what returns to the room on real system back). Rendered in
        // isolation here with no NavHost present, there is nothing to intercept system back at
        // all, so that path is exercised on-device instead (MANUAL_TESTING.md); the back icon's
        // onClick is the one path this screen fully owns on its own, which is what's meaningful
        // to unit-test in isolation.
        val ideaA = idea(ByteArray(16) { 1 }, "Camping trip")
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(ideas = mapOf("01" to ideaA)), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val viewModel = RoomViewModel(room, myKey, gateway, FakeRoomMuteStore())
        var onBackCalls = 0

        composeRule.setContent {
            IdeaDetailScreen(viewModel = viewModel, ideaId = ideaA.id, onBack = { onBackCalls++ })
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Camping trip").assertIsDisplayed()
        composeRule.onNodeWithText("Comments").assertIsDisplayed() // confirms this is the detail screen's own content

        composeRule.onNodeWithContentDescription("Back to ideas").performClick()
        composeRule.waitForIdle()

        assert(onBackCalls == 1) { "the back icon on idea detail should call onBack exactly once, got $onBackCalls" }
    }

    @Test
    fun ideaDetailScreenPopsItselfWhenTheIdeaDoesNotExist() {
        // Covers both "a deep link named an idea that doesn't exist (any more)" and "this idea
        // was just deleted by this client or another one" (IdeaDetailScreen's own doc comment) -
        // both end up at the same state once the next refresh lands: no idea with this id.
        val gateway = FakeSyncGateway().apply {
            materialization = RoomMaterialization(RoomState(), emptyMap())
            myRoleResult = Role.MEMBER
        }
        val viewModel = RoomViewModel(room, myKey, gateway, FakeRoomMuteStore())
        var onBackCalls = 0
        val missingIdeaId = ByteArray(16) { 9 }

        composeRule.setContent {
            IdeaDetailScreen(viewModel = viewModel, ideaId = missingIdeaId, onBack = { onBackCalls++ })
        }
        composeRule.waitForIdle()

        assert(onBackCalls == 1) { "idea detail should pop itself when its idea doesn't exist, got $onBackCalls calls" }
    }

    @Test
    fun onboardingStepBackReturnsFromShowSeedToModeSelect() {
        val viewModel = OnboardingViewModel(
            crypto = org.squaddle.app.data.FakeCrypto,
            identityVault = org.squaddle.app.crypto.FakeIdentityVault(),
            displayNameStore = org.squaddle.app.settings.FakeDisplayNameStore(),
            backupManager = org.squaddle.app.backup.FakeBackupManager(),
            appLocalStore = org.squaddle.app.store.FakeAppLocalStore(),
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
