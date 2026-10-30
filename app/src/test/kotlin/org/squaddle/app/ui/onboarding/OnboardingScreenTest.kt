package org.squaddle.app.ui.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.squaddle.app.backup.FakeBackupManager
import org.squaddle.app.crypto.FakeIdentityVault
import org.squaddle.app.data.FakeCrypto
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.settings.FakeDisplayNameStore
import org.squaddle.app.store.FakeAppLocalStore

/** Real Compose semantics on the JVM via Robolectric - no emulator/device needed (see the plan's
 * own justification). Drives the create-identity path end to end through the actual Composables,
 * not just the ViewModel's state machine (that's [OnboardingViewModelTest]'s job). The onboarding
 * content is scrollable (24 seed words don't fit a small screen), so steps below the fold need
 * performScrollTo() before performClick() - compose-ui-test's click dispatches a real synthetic
 * touch at the node's bounds, which only resolves correctly once the node is actually scrolled
 * into the root's laid-out viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createIdentityFlowReachesDone() {
        var finished = false
        val vault = FakeIdentityVault()
        val gateway = FakeSyncGateway()
        val viewModel = OnboardingViewModel(
            crypto = FakeCrypto,
            identityVault = vault,
            displayNameStore = FakeDisplayNameStore(),
            backupManager = FakeBackupManager(),
            appLocalStore = FakeAppLocalStore(),
            openSession = { _, _, _ -> gateway },
        )

        composeRule.setContent {
            OnboardingScreen(viewModel = viewModel, onFinished = { finished = true })
        }

        composeRule.onNodeWithText("Create a new identity").performClick()
        composeRule.onNodeWithTag("wroteItDownToggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        composeRule.onNodeWithTag("displayNameField").performScrollTo().performTextInput("Alex")
        composeRule.onNodeWithText("Finish").performScrollTo().performClick()

        composeRule.waitForIdle()
        assert(finished) { "onFinished was never called" }
        assert(vault.hasIdentity())
    }
}
