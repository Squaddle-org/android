package org.squaddle.app.ui.rooms

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.squaddle.app.data.FakeSyncGateway
import org.squaddle.app.store.FakeAppLocalStore
import org.squaddle.app.ui.theme.SquaddleTheme

/**
 * The design-pass spec's "lock it in" step: real screens, rendered under Robolectric (no
 * emulator needed) and diffed against committed reference PNGs in src/test/screenshots/ - a
 * regression in spacing, color, or type fails `./gradlew verifyRoborazziDebug` in CI instead of
 * only surfacing in a manual look-through. Record new references with
 * `./gradlew recordRoborazziDebug` after a deliberate visual change.
 *
 * Scoped to [RoomsListScreen] for this first pass (empty + populated, light/dark, EN/DE, phone/
 * tablet width) - it needs only [FakeSyncGateway]/[FakeAppLocalStore], the same fixtures the
 * existing [RoomsListViewModelTest] already uses, with no real membership/crypto chain to build.
 * [org.squaddle.app.ui.room.RoomScreen]/[org.squaddle.app.ui.members.MembersScreen] need a real
 * [org.squaddle.core.ops.RoomState]/[org.squaddle.core.membership.MembershipHistory] fixture
 * first (see [org.squaddle.app.ui.room.RoomViewModelTest]/
 * [org.squaddle.app.ui.members.MembersViewModelTest] for how those are built) - the same
 * `setContent { SquaddleTheme { ... } }` + `captureRoboImage(...)` shape extends to them directly
 * once that fixture exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomsListScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun roomId(byte: Byte): ByteArray = ByteArray(32) { byte }

    private fun populatedViewModel(): RoomsListViewModel {
        val store = FakeAppLocalStore()
        store.insertKnownRoom(roomId(1), "Climbing crew", "relay.squaddle.org", joinedAt = 100L, lastActivityAt = 300L)
        store.insertKnownRoom(roomId(2), "Family 🎄", "relay.squaddle.org", joinedAt = 50L, lastActivityAt = 200L)
        return RoomsListViewModel(FakeSyncGateway(), store)
    }

    private fun emptyViewModel(): RoomsListViewModel = RoomsListViewModel(FakeSyncGateway(), FakeAppLocalStore())

    private fun capture(viewModel: RoomsListViewModel, name: String) {
        composeRule.setContent {
            SquaddleTheme { RoomsListScreen(viewModel = viewModel, onOpenRoom = {}) }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("src/test/screenshots/rooms/$name.png")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun populated_light_en_phone() = capture(populatedViewModel(), "populated_light_en_phone")

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
    fun populated_dark_en_phone() = capture(populatedViewModel(), "populated_dark_en_phone")

    @Test
    @Config(sdk = [34], qualifiers = "de-w411dp-h891dp-xhdpi")
    fun populated_light_de_phone() = capture(populatedViewModel(), "populated_light_de_phone")

    @Test
    @Config(sdk = [34], qualifiers = "sw720dp-w1024dp-h768dp-xhdpi")
    fun populated_light_en_tablet() = capture(populatedViewModel(), "populated_light_en_tablet")

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
    fun empty_light_en_phone() = capture(emptyViewModel(), "empty_light_en_phone")

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xhdpi")
    fun empty_dark_en_phone() = capture(emptyViewModel(), "empty_dark_en_phone")
}
