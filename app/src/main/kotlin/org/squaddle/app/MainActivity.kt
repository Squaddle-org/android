package org.squaddle.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.squaddle.app.data.InviteLink
import org.squaddle.app.data.RealSyncGateway
import org.squaddle.app.push.PushRegistrar
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
import org.squaddle.app.ui.settings.SettingsScreen
import org.squaddle.app.ui.settings.SettingsViewModel
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
public class MainActivity : FragmentActivity() {
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

/** `link=<url-encoded invite link>` convention for a Play referrer string that isn't itself a
 * bare invite link (some install flows append it as a query-string-shaped param rather than the
 * raw link) - `null` if no such param is present. Never throws on a malformed encoding. */
internal fun extractLinkParam(referrer: String): String? {
    val match = Regex("(?:^|[&?])link=([^&]+)").find(referrer) ?: return null
    return try {
        java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
    } catch (e: Exception) {
        null
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
    data object Voucher : RoomSubScreen
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
    val context = LocalContext.current
    var joinLink by remember { mutableStateOf<InviteLink?>(null) }
    var showInstallNtfyHint by remember { mutableStateOf(false) }
    var outboxCount by remember { mutableStateOf(0) }

    // Best-effort retry of PUT /users/{user}/devices/{device} on every cold start: idempotent
    // (self-certifying, just overwrites the same stored cert - relay/internal/api/devices.go's
    // putDevice) and cheap, so this is a safe, stateless way to recover from onboarding's own
    // registerDevice() call having failed (e.g. relay unreachable at the time) without needing
    // to persist any "still pending" flag.
    LaunchedEffect(Unit) {
        gateway.registerDevice()
    }

    // Same cold-start retry posture as registerDevice() above - PushRegistrar re-resolves the
    // distributor and re-registers every launch, which is exactly how a NETWORK/INTERNAL_ERROR
    // registration failure (SquaddlePushService.onRegistrationFailed) ever gets retried.
    LaunchedEffect(Unit) {
        PushRegistrar.ensureRegistered(context, gateway)
        showInstallNtfyHint = PushRegistrar.noDistributorInstalled(context)
    }

    // M4b item 11's offline/outbox banner: a stuck outbox (ops that haven't pushed yet) is
    // exactly what "you're offline, or sync is failing" looks like from the client's own point
    // of view, so this skips a separate ConnectivityManager listener entirely. Local-SQLite-only
    // (no network), same lightweight-polling posture as RealSyncGateway.connectLiveUpdates's own
    // local change detection.
    LaunchedEffect(Unit) {
        while (true) {
            outboxCount = gateway.outboxCount()
            kotlinx.coroutines.delay(5000)
        }
    }

    // POST_NOTIFICATIONS is a runtime permission only on API 33+ (below that it doesn't exist
    // and notifications just work); SyncAllRoomsWorker already checks the grant before calling
    // notify() regardless, so a denial here just means "no notifications shown," never a crash.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    // Play flavor's deferred deep link (android/CLAUDE.md M4b item 9) - a second source feeding
    // the exact same join-flow entry point as the clipboard/App-Link checks, not new join logic.
    // `InstallReferrerChecker` is the `foss`-flavor-always-null version unless this is a `play`
    // build (see that class's own two flavor-specific source files).
    LaunchedEffect(Unit) {
        val referrer = org.squaddle.app.referrer.InstallReferrerChecker().referrerUrl(context) ?: return@LaunchedEffect
        if (joinLink != null) return@LaunchedEffect
        val link = gateway.parseInviteLink(referrer) ?: extractLinkParam(referrer)?.let { gateway.parseInviteLink(it) }
        link?.let { joinLink = it }
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
            // Mutually exclusive: a stuck outbox is the more urgent, currently-active problem,
            // so it wins this one shared banner slot over the (purely advisory) "install ntfy" hint.
            when {
                outboxCount > 0 -> Text(
                    text = stringResource(R.string.outbox_pending_hint, outboxCount),
                    modifier = Modifier.Companion.align(Alignment.BottomCenter).padding(16.dp).testTag("outboxBanner"),
                )
                showInstallNtfyHint -> Text(
                    text = stringResource(R.string.push_install_ntfy_hint),
                    modifier = Modifier.Companion.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RoomsOrRoom(container: org.squaddle.app.di.AppContainer, gateway: org.squaddle.app.data.SyncGateway) {
    var openRoomId by remember { mutableStateOf<ByteArray?>(null) }
    var showDeveloperSettings by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val roomId = openRoomId
    if (showDeveloperSettings) {
        val viewModel: DeveloperSettingsViewModel = viewModel(
            factory = viewModelFactory {
                initializer { DeveloperSettingsViewModel(container.developerSettingsStore, container.effectiveRelayHost) }
            },
        )
        DeveloperSettingsScreen(viewModel = viewModel, onBack = { showDeveloperSettings = false })
    } else if (showSettings) {
        val viewModel: SettingsViewModel = viewModel(
            factory = viewModelFactory {
                initializer {
                    SettingsViewModel(
                        crypto = container.crypto,
                        userIdentity = container.session!!.userIdentity,
                        appLocalStore = container.appLocalStore,
                        backupManager = container.backupManager,
                        passphraseVault = container.backupPassphraseVault,
                        autoBackupSettings = container.autoBackupSettings,
                    )
                }
            },
        )
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
    } else if (roomId == null) {
        val viewModel: RoomsListViewModel = viewModel(
            factory = viewModelFactory {
                initializer { RoomsListViewModel(gateway, container.appLocalStore) }
            },
        )
        RoomsListScreen(
            viewModel = viewModel,
            onOpenRoom = { openRoomId = it },
            onOpenSettings = { showSettings = true },
            onOpenDeveloperSettings = { showDeveloperSettings = true },
        )
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
            is RoomSubScreen.Voucher -> {
                val voucherViewModel: org.squaddle.app.ui.voucher.VoucherRedeemViewModel = viewModel(
                    key = "voucher_${Base32.encode(roomId)}",
                    factory = viewModelFactory {
                        initializer { org.squaddle.app.ui.voucher.VoucherRedeemViewModel(roomId, gateway) }
                    },
                )
                org.squaddle.app.ui.voucher.VoucherRedeemScreen(viewModel = voucherViewModel, onBack = { subScreen = RoomSubScreen.Ideas })
            }
            is RoomSubScreen.Ideas -> {
                val viewModel: RoomViewModel = viewModel(
                    key = Base32.encode(roomId),
                    factory = viewModelFactory {
                        initializer { RoomViewModel(roomId, container.session!!.userIdentity.userKey, gateway, container.roomMuteStore) }
                    },
                )
                RoomScreen(
                    viewModel = viewModel,
                    roomName = roomName,
                    onBack = { openRoomId = null },
                    onOpenMembers = { subScreen = RoomSubScreen.Members },
                    onOpenInvite = { subScreen = RoomSubScreen.Invite },
                    onOpenVoucherRedeem = { subScreen = RoomSubScreen.Voucher },
                )
            }
        }
    }
}
