package org.squaddle.app.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Against a [FakeDeveloperSettingsStore] - no Android `SharedPreferences`. */
class DeveloperSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun startsWithTheStoredOverrideAndTheBuildDefault() {
        val store = FakeDeveloperSettingsStore().apply { setRelayHostOverride("dev.example.org") }
        val vm = DeveloperSettingsViewModel(store, defaultRelayHost = "relay.squaddle.org")
        assertEquals("dev.example.org", vm.uiState.value.relayHostInput)
        assertEquals("relay.squaddle.org", vm.uiState.value.defaultRelayHost)
    }

    @Test
    fun savePersistsTheOverride() = runTest(testDispatcher) {
        val store = FakeDeveloperSettingsStore()
        val vm = DeveloperSettingsViewModel(store, defaultRelayHost = "relay.squaddle.org")

        vm.updateInput("dev.example.org")
        vm.save()
        advanceUntilIdle()

        assertEquals("dev.example.org", store.relayHostOverride())
        assertEquals("Saved", vm.uiState.value.savedMessage)
    }

    @Test
    fun resetToDefaultClearsTheOverride() = runTest(testDispatcher) {
        val store = FakeDeveloperSettingsStore().apply { setRelayHostOverride("dev.example.org") }
        val vm = DeveloperSettingsViewModel(store, defaultRelayHost = "relay.squaddle.org")

        vm.resetToDefault()
        advanceUntilIdle()

        assertNull(store.relayHostOverride())
        assertEquals("", vm.uiState.value.relayHostInput)
    }
}
