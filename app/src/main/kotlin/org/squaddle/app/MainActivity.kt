package org.squaddle.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.squaddle.app.data.InviteLink
import org.squaddle.app.data.RealSyncGateway
import org.squaddle.app.ui.invite.InviteScreen
import org.squaddle.app.ui.invite.InviteViewModel
import org.squaddle.app.ui.join.ClipboardInviteBanner
import org.squaddle.app.ui.join.JoinScreen
import org.squaddle.app.ui.join.JoinViewModel
import org.squaddle.app.ui.members.MembersScreen
import org.squaddle.app.ui.members.MembersViewModel
import org.squaddle.app.ui.onboarding.OnboardingScreen
import org.squaddle.app.ui.onboarding.OnboardingViewModel
import org.squaddle.app.ui.room.RoomScreen
import org.squaddle.app.ui.room.RoomViewModel
import org.squaddle.app.settings.DeveloperSettingsScreen
import org.squaddle.app.settings.DeveloperSettingsViewModel
import org.squaddle.app.ui.rooms.RoomsListScreen
import org.squaddle.app.ui.rooms.RoomsListViewModel
import org.squaddle.app.ui.theme.SquaddleTheme
import org.squaddle.core.encoding.Base32

/**
 * Single-activity app (android/CLAUDE.md). `singleTask` launch mode (manifest) means a relaunch
 * via the App Link while already running arrives through [onNewIntent], not a fresh [onCreate] -
 * [pendingDeepLink] is the one piece of state that has to live outside the Compose tree to
 * survive that.
 */
public class MainActivity : ComponentActivity() {
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink.value = intent?.data?.toString()
        setContent {
            SquaddleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SquaddleApp(pendingDeepLink)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = intent.data?.toString()
    }
}

private sealed interface StartupState {
    data object Loading : StartupState
    data object NeedsOnboarding : StartupState
    data object SignedIn : StartupState
}

private sealed interface RoomSubScreen {
    data object Ideas : RoomSubScreen
    data object Members : RoomSubScreen
    data object Invite : RoomSubScreen
}

@Composable
private fun SquaddleApp(pendingDeepLink: MutableState<String?>) {
    val container = LocalContext.current.appContainer()
    var startupState by remember { mutableStateOf<StartupState>(StartupState.Loading) }

    LaunchedEffect(Unit) {
        container.initialize()
        startupState = if (container.restoreSessionIfPossible()) StartupState.SignedIn else StartupState.NeedsOnboarding
    }

    when (startupState) {
        is StartupState.Loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is StartupState.NeedsOnboarding -> {
            val viewModel: OnboardingViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        OnboardingViewModel(
                            crypto = container.crypto,
                            identityVault = container.identityVault,
                            openSession = { identity, device, cert ->
                                val session = container.openSession(identity, device, cert)
                                RealSyncGateway(session, container.appLocalStore, container.effectiveRelayHost)
                            },
                        )
                    }
                },
            )
            OnboardingScreen(viewModel = viewModel, onFinished = { startupState = StartupState.SignedIn })
        }
        is StartupState.SignedIn -> SignedInApp(container, pendingDeepLink)
    }
}

@Composable
private fun SignedInApp(container: org.squaddle.app.di.AppContainer, pendingDeepLink: MutableState<String?>) {
    val gateway = remember { container.syncGateway() }
    var joinLink by remember { mutableStateOf<InviteLink?>(null) }

    // Best-effort retry of PUT /users/{user}/devices/{device} on every cold start: idempotent
    // (self-certifying, just overwrites the same stored cert - relay/internal/api/devices.go's
    // putDevice) and cheap, so this is a safe, stateless way to recover from onboarding's own
    // registerDevice() call having failed (e.g. relay unreachable at the time) without needing
    // to persist any "still pending" flag.
    LaunchedEffect(Unit) {
        gateway.registerDevice()
    }

    // App Link: consume a pending deep link once, whenever one arrives (first launch, or a
    // relaunch via onNewIntent while already signed in).
    LaunchedEffect(pendingDeepLink.value) {
        val text = pendingDeepLink.value
        if (text != null) {
            gateway.parseInviteLink(text)?.let { joinLink = it }
            pendingDeepLink.value = null
        }
    }

    // Clipboard fallback (ARCHITECTURE.md): checked once per cold start on resume, never polled
    // continuously - Android 10+ restricts background clipboard reads anyway, and this avoids
    // any appearance of surveilling the clipboard.
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    var clipboardSuggestion by remember { mutableStateOf<InviteLink?>(null) }
    var dismissedClipboardText by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val text = clipboardManager.getText()?.text
                if (text != null && text != dismissedClipboardText && joinLink == null) {
                    gateway.parseInviteLink(text)?.let { clipboardSuggestion = it }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val currentJoinLink = joinLink
    if (currentJoinLink != null) {
        val joinViewModel: JoinViewModel = viewModel(
            key = "join_${Base32.encode(currentJoinLink.roomId)}_${Base32.encode(currentJoinLink.inviteId)}",
            factory = viewModelFactory { initializer { JoinViewModel(gateway) } },
        )
        LaunchedEffect(currentJoinLink) { joinViewModel.openWithLink(currentJoinLink) }
        JoinScreen(
            viewModel = joinViewModel,
            onJoined = { joinLink = null },
            onCancelled = { joinLink = null },
        )
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            RoomsOrRoom(container, gateway)
            clipboardSuggestion?.let { suggestion ->
                ClipboardInviteBanner(
                    onOpen = { joinLink = suggestion; clipboardSuggestion = null },
                    onDismiss = {
                        dismissedClipboardText = clipboardManager.getText()?.text
                        clipboardSuggestion = null
                    },
                    modifier = Modifier.Companion.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun RoomsOrRoom(container: org.squaddle.app.di.AppContainer, gateway: org.squaddle.app.data.SyncGateway) {
    var openRoomId by remember { mutableStateOf<ByteArray?>(null) }
    var showDeveloperSettings by remember { mutableStateOf(false) }
    val roomId = openRoomId
    if (showDeveloperSettings) {
        val viewModel: DeveloperSettingsViewModel = viewModel(
            factory = viewModelFactory {
                initializer { DeveloperSettingsViewModel(container.developerSettingsStore, container.effectiveRelayHost) }
            },
        )
        DeveloperSettingsScreen(viewModel = viewModel, onBack = { showDeveloperSettings = false })
    } else if (roomId == null) {
        val viewModel: RoomsListViewModel = viewModel(
            factory = viewModelFactory {
                initializer { RoomsListViewModel(gateway, container.appLocalStore) }
            },
        )
        RoomsListScreen(viewModel = viewModel, onOpenRoom = { openRoomId = it }, onOpenDeveloperSettings = { showDeveloperSettings = true })
    } else {
        var subScreen by remember(roomId) { mutableStateOf<RoomSubScreen>(RoomSubScreen.Ideas) }
        val roomName = container.appLocalStore.knownRoom(roomId)?.display_name ?: "Room"
        when (subScreen) {
            is RoomSubScreen.Members -> {
                val membersViewModel: MembersViewModel = viewModel(
                    key = "members_${Base32.encode(roomId)}",
                    factory = viewModelFactory {
                        initializer { MembersViewModel(roomId, container.session!!.userIdentity.userKey, gateway) }
                    },
                )
                MembersScreen(viewModel = membersViewModel, onBack = { subScreen = RoomSubScreen.Ideas })
            }
            is RoomSubScreen.Invite -> {
                val inviteViewModel: InviteViewModel = viewModel(
                    key = "invite_${Base32.encode(roomId)}",
                    factory = viewModelFactory {
                        initializer { InviteViewModel(roomId, gateway) }
                    },
                )
                InviteScreen(viewModel = inviteViewModel, onBack = { subScreen = RoomSubScreen.Ideas })
            }
            is RoomSubScreen.Ideas -> {
                val viewModel: RoomViewModel = viewModel(
                    key = Base32.encode(roomId),
                    factory = viewModelFactory {
                        initializer { RoomViewModel(roomId, container.session!!.userIdentity.userKey, gateway) }
                    },
                )
                RoomScreen(
                    viewModel = viewModel,
                    roomName = roomName,
                    onBack = { openRoomId = null },
                    onOpenMembers = { subScreen = RoomSubScreen.Members },
                    onOpenInvite = { subScreen = RoomSubScreen.Invite },
                )
            }
        }
    }
}
