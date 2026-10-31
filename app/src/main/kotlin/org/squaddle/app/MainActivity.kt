package org.squaddle.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import org.squaddle.app.data.InviteLink
import org.squaddle.app.data.RealSyncGateway
import org.squaddle.app.di.AppContainer
import org.squaddle.app.push.PushRegistrar
import org.squaddle.app.referrer.InstallReferrerChecker
import org.squaddle.app.settings.DeveloperSettingsScreen
import org.squaddle.app.settings.DeveloperSettingsViewModel
import org.squaddle.app.ui.invite.InviteScreen
import org.squaddle.app.ui.invite.InviteViewModel
import org.squaddle.app.ui.join.ClipboardInviteBanner
import org.squaddle.app.ui.join.JoinScreen
import org.squaddle.app.ui.join.JoinViewModel
import org.squaddle.app.ui.members.MembersScreen
import org.squaddle.app.ui.members.MembersViewModel
import org.squaddle.app.ui.nav.Route
import org.squaddle.app.ui.nav.parseInternalDeepLink
import org.squaddle.app.ui.onboarding.OnboardingScreen
import org.squaddle.app.ui.onboarding.OnboardingViewModel
import org.squaddle.app.ui.room.EventDetailScreen
import org.squaddle.app.ui.room.IdeaDetailScreen
import org.squaddle.app.ui.room.RoomScreen
import org.squaddle.app.ui.room.RoomViewModel
import org.squaddle.app.ui.rooms.RoomsListScreen
import org.squaddle.app.ui.rooms.RoomsListViewModel
import org.squaddle.app.ui.link.DeviceLinkScanScreen
import org.squaddle.app.ui.link.DeviceLinkViewModel
import org.squaddle.app.ui.settings.SettingsScreen
import org.squaddle.app.ui.settings.SettingsViewModel
import org.squaddle.app.ui.theme.SquaddleTheme
import org.squaddle.app.ui.voucher.VoucherRedeemScreen
import org.squaddle.app.ui.voucher.VoucherRedeemViewModel
import org.squaddle.core.encoding.Base32

/**
 * Single-activity app (android/CLAUDE.md). `singleTask` launch mode (manifest) means a relaunch
 * via the App Link while already running arrives through [onNewIntent], not a fresh [onCreate] -
 * [pendingDeepLink] is the one piece of state that has to live outside the Compose tree to
 * survive that.
 *
 * A [androidx.fragment.app.FragmentActivity] (not a plain `ComponentActivity`) only because
 * [androidx.biometric.BiometricPrompt] (the Settings screen's seed reveal) requires one - see
 * that code's own doc comment, and `gradle/libs.versions.toml`'s `fragment` entry for the real
 * crash this combination caused before `androidx.fragment` was pinned to a compatible version.
 */
public class MainActivity : FragmentActivity() {
    private val pendingDeepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Status/nav bars transparent with auto light/dark icon contrast; Theme.Squaddle's old
        // android:statusBarColor (a solid brand-coral bar, pre-edge-to-edge style) is gone from
        // themes.xml - Material3's own TopAppBar/Scaffold already draw their surface color up
        // behind the transparent bars and inset their content below them, API 26..34 included
        // (API 35+ enforces this regardless, per Theme.Squaddle's own doc comment).
        enableEdgeToEdge()
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

private const val NTFY_PACKAGE_ID = "io.heckel.ntfy"

/**
 * The install-ntfy banner's own button. `play` opens the Play Store listing (`market://` so it
 * opens in the Play Store app itself rather than a browser tab, falling back to the plain
 * `https://play.google.com/store/...` web URL if nothing handles `market://` - an emulator/AOSP
 * build with no Play Store app installed, say); `fdroid`/`github` open ntfy's F-Droid listing
 * instead (ntfy itself is still F-Droid-distributed regardless of which flavor of this app is
 * asking) - the same flavor-aware posture `InstallReferrerChecker` already uses, just as a plain
 * URI rather than a whole separate flavor source set (there's no API difference to abstract here,
 * only the link).
 */
private fun openNtfyInstallPage(context: Context) {
    val uri = if (BuildConfig.FLAVOR == "play") {
        "market://details?id=$NTFY_PACKAGE_ID"
    } else {
        "https://f-droid.org/packages/$NTFY_PACKAGE_ID/"
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$NTFY_PACKAGE_ID")))
    }
}

private sealed interface StartupState {
    data object Loading : StartupState
    data object NeedsOnboarding : StartupState
    data object SignedIn : StartupState
}

/** Pushes [link] as a [Route.Join] destination unless one's already on top (an App Link re-fire,
 * the clipboard check, and the install-referrer check can all race to suggest the same or a
 * different link in quick succession - this just keeps the back stack from accumulating more
 * than one). */
private fun NavController.navigateToJoin(link: InviteLink) {
    if (currentBackStackEntry?.destination?.route?.startsWith("org.squaddle.app.ui.nav.Route.Join") == true) return
    navigate(Route.Join(link.toUriString()))
}

/** Builds the proper synthetic back stack for a `squaddle://room/...` deep link (`Route.kt`'s own
 * doc comment on why this URI scheme exists) - always pushes [Route.Room] directly beneath
 * [route] (unless [route] already IS the room - then there's nothing more to push), explicitly,
 * rather than relying on [route]'s own `navigate()` call to construct it: this is what makes
 * `composable<Route.Idea>`/`composable<Route.Event>`'s `getBackStackEntry(Route.Room(...))`
 * lookup always succeed, and what makes back from the idea/event/members screen land on the room
 * (its real "parent") instead of skipping past it, the same deliberate stack-building
 * [navigateToJoin] above already relies on for [Route.RoomsList]. */
private fun NavController.navigateToRoomDeepLink(route: Route) {
    val roomIdB32 = when (route) {
        is Route.Room -> route.roomIdB32
        is Route.Idea -> route.roomIdB32
        is Route.Event -> route.roomIdB32
        is Route.Members -> route.roomIdB32
        else -> return
    }
    navigate(Route.Room(roomIdB32))
    if (route !is Route.Room) navigate(route)
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
                            displayNameStore = container.displayNameStore,
                            backupManager = container.backupManager,
                            appLocalStore = container.appLocalStore,
                            openSession = { identity, device, cert ->
                                val session = container.openSession(identity, device, cert)
                                RealSyncGateway(session, container.appLocalStore, container.effectiveRelayHost, container.displayNameStore)
                            },
                        )
                    }
                },
            )
            // Reaching StartupState.SignedIn below discards this whole composable subtree (and
            // with it, OnboardingViewModel's own state) on the next recomposition - onboarding
            // was never a nav back-stack entry to begin with, so "back on rooms list must not
            // return to onboarding" holds with no popUpTo/cleanup step needed here at all.
            OnboardingScreen(viewModel = viewModel, onFinished = { startupState = StartupState.SignedIn })
        }
        is StartupState.SignedIn -> SignedInApp(container, pendingDeepLink)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SignedInApp(container: AppContainer, pendingDeepLink: MutableState<String?>) {
    val gateway = remember { container.syncGateway() }
    val context = LocalContext.current
    val navController = rememberNavController()
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
            delay(5000)
        }
    }

    // POST_NOTIFICATIONS is a runtime permission only on API 33+ (below that it doesn't exist
    // and notifications just work); SyncAllRoomsWorker already checks the grant before calling
    // notify() regardless, so a denial here just means "no notifications shown," never a crash.
    //
    // Asked at most once, ever (NotificationPermissionGate), the first time a room actually
    // exists - not blindly on every cold start, which would mean asking before the user has even
    // joined/created anything to be notified about. Polls appLocalStore (same lightweight
    // local-only posture as the outboxCount poll above) rather than needing a callback threaded
    // through every room-creation/join/restore call site - the moment a first room appears,
    // however it got there, this notices on its own.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        if (container.notificationPermissionGate.hasPrompted()) return@LaunchedEffect
        while (true) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                return@LaunchedEffect
            }
            if (container.appLocalStore.allKnownRooms().isNotEmpty()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                container.notificationPermissionGate.setPrompted()
                return@LaunchedEffect
            }
            delay(2000)
        }
    }

    // App Link / internal deep link: consume a pending one once, whenever it arrives (first
    // launch, or a relaunch via onNewIntent while already signed in). An invite link is pushed as
    // a real Route.Join destination on top of whatever's currently showing - RoomsList is always
    // the graph's start destination underneath everything, so back from here always lands on it
    // ("entered via App Link: back goes to the room's parent" - RoomsList is that parent, with no
    // synthetic stack-building needed since it was never popped off in the first place). A
    // squaddle://room/... link (Route.kt's own doc comment - built by RoomSyncNotifier for a
    // notification tap, or constructed directly for testing) DOES need an explicit synthetic
    // stack (navigateToRoomDeepLink), since its own "parent" (Route.Room) isn't already sitting
    // there the way RoomsList is for Join.
    LaunchedEffect(pendingDeepLink.value) {
        val text = pendingDeepLink.value
        if (text != null) {
            val internalRoute = parseInternalDeepLink(text)
            if (internalRoute != null) {
                navController.navigateToRoomDeepLink(internalRoute)
            } else {
                gateway.parseInviteLink(text)?.let { navController.navigateToJoin(it) }
            }
            pendingDeepLink.value = null
        }
    }

    // Play flavor's deferred deep link (android/CLAUDE.md M4b item 9) - a second source feeding
    // the exact same join-flow entry point as the clipboard/App-Link checks, not new join logic.
    // `InstallReferrerChecker` is the always-null `fdroid`/`github` version unless this is a
    // `play` build (see that class's own three flavor-specific source files).
    LaunchedEffect(Unit) {
        val referrer = InstallReferrerChecker().referrerUrl(context) ?: return@LaunchedEffect
        val link = gateway.parseInviteLink(referrer) ?: extractLinkParam(referrer)?.let { gateway.parseInviteLink(it) }
        link?.let { navController.navigateToJoin(it) }
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
                if (text != null && text != dismissedClipboardText) {
                    gateway.parseInviteLink(text)?.let { clipboardSuggestion = it }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Design-pass spec's "container transform list -> detail": the idea title text morphs
        // position/size/font between RoomScreen's list row and IdeaDetailScreen's header, instead
        // of the list simply cross-fading out while the detail screen fades in underneath. The
        // whole NavHost has to sit inside one SharedTransitionLayout (the shared element is laid
        // out/measured by a single parent that straddles both destinations across the
        // transition); each composable<T> { } lambda below is itself an AnimatedContentScope
        // (navigation-compose 2.8+), threaded into RoomScreen/IdeaDetailScreen as a nullable param
        // so Roborazzi/unit tests that render those screens with no NavHost around them (no
        // shared-element behavior there) keep compiling unchanged.
        SharedTransitionLayout {
        NavHost(navController = navController, startDestination = Route.RoomsList) {
            composable<Route.RoomsList> {
                val viewModel: RoomsListViewModel = viewModel(
                    factory = viewModelFactory { initializer { RoomsListViewModel(gateway, container.appLocalStore) } },
                )
                RoomsListScreen(
                    viewModel = viewModel,
                    onOpenRoom = { navController.navigate(Route.Room(Base32.encode(it))) },
                    onOpenSettings = { navController.navigate(Route.Settings) },
                    onOpenDeveloperSettings = { navController.navigate(Route.DeveloperSettings) },
                )
            }
            composable<Route.Settings> {
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
                                selfUpdateChecker = container.selfUpdateChecker,
                            )
                        }
                    },
                )
                SettingsScreen(
                    viewModel = viewModel,
                    selfUpdateChecker = container.selfUpdateChecker,
                    onBack = { navController.popBackStack() },
                    onLinkNewDevice = { navController.navigate(Route.LinkDevice) },
                )
            }
            composable<Route.LinkDevice> {
                val viewModel: DeviceLinkViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            DeviceLinkViewModel(
                                crypto = container.crypto,
                                userIdentity = container.session!!.userIdentity,
                                appLocalStore = container.appLocalStore,
                            )
                        }
                    },
                )
                DeviceLinkScanScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Route.DeveloperSettings> {
                val viewModel: DeveloperSettingsViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { DeveloperSettingsViewModel(container.developerSettingsStore, container.effectiveRelayHost) }
                    },
                )
                DeveloperSettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Route.Room> { backStackEntry ->
                val route: Route.Room = backStackEntry.toRoute()
                val roomId = remember(route.roomIdB32) { Base32.decode(route.roomIdB32) ?: ByteArray(0) }
                val roomName = container.appLocalStore.knownRoom(roomId)?.display_name ?: stringResource(R.string.default_room_name)
                val viewModel: RoomViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { RoomViewModel(roomId, container.session!!.userIdentity.userKey, gateway, container.roomMuteStore) }
                    },
                )
                RoomScreen(
                    viewModel = viewModel,
                    roomName = roomName,
                    onBack = { navController.popBackStack() },
                    onOpenMembers = { navController.navigate(Route.Members(route.roomIdB32)) },
                    onOpenInvite = { navController.navigate(Route.Invite(route.roomIdB32)) },
                    onOpenVoucherRedeem = { navController.navigate(Route.VoucherRedeem(route.roomIdB32)) },
                    onOpenIdea = { ideaId -> navController.navigate(Route.Idea(route.roomIdB32, Base32.encode(ideaId))) },
                    onOpenEvent = { eventId -> navController.navigate(Route.Event(route.roomIdB32, Base32.encode(eventId))) },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                )
            }
            composable<Route.Idea> { backStackEntry ->
                val route: Route.Idea = backStackEntry.toRoute()
                val ideaId = remember(route.ideaIdB32) { Base32.decode(route.ideaIdB32) ?: ByteArray(0) }
                // Shares Route.Room's own RoomViewModel instance (scoped to that entry, which is
                // guaranteed to be on the back stack directly beneath this one - see this
                // composable()'s only two entry points below: the in-app tap in RoomScreen always
                // starts from an already-open Route.Room, and navigateToRoomDeepLink explicitly
                // pushes Route.Room first for a deep link) rather than a fresh one, so a vote/
                // comment made here is visible immediately on the ideas list behind it.
                val roomEntry = remember(backStackEntry) { navController.getBackStackEntry(Route.Room(route.roomIdB32)) }
                val viewModel: RoomViewModel = viewModel(
                    viewModelStoreOwner = roomEntry,
                    factory = viewModelFactory {
                        initializer { RoomViewModel(Base32.decode(route.roomIdB32) ?: ByteArray(0), container.session!!.userIdentity.userKey, gateway, container.roomMuteStore) }
                    },
                )
                IdeaDetailScreen(
                    viewModel = viewModel,
                    ideaId = ideaId,
                    onBack = { navController.popBackStack() },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                )
            }
            composable<Route.Event> { backStackEntry ->
                val route: Route.Event = backStackEntry.toRoute()
                val eventId = remember(route.eventIdB32) { Base32.decode(route.eventIdB32) ?: ByteArray(0) }
                // Same shared-instance posture as Route.Idea above.
                val roomEntry = remember(backStackEntry) { navController.getBackStackEntry(Route.Room(route.roomIdB32)) }
                val viewModel: RoomViewModel = viewModel(
                    viewModelStoreOwner = roomEntry,
                    factory = viewModelFactory {
                        initializer { RoomViewModel(Base32.decode(route.roomIdB32) ?: ByteArray(0), container.session!!.userIdentity.userKey, gateway, container.roomMuteStore) }
                    },
                )
                EventDetailScreen(viewModel = viewModel, eventId = eventId, onBack = { navController.popBackStack() })
            }
            composable<Route.Members> { backStackEntry ->
                val route: Route.Members = backStackEntry.toRoute()
                val roomId = remember(route.roomIdB32) { Base32.decode(route.roomIdB32) ?: ByteArray(0) }
                val viewModel: MembersViewModel = viewModel(
                    factory = viewModelFactory { initializer { MembersViewModel(roomId, container.session!!.userIdentity.userKey, gateway) } },
                )
                MembersScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Route.Invite> { backStackEntry ->
                val route: Route.Invite = backStackEntry.toRoute()
                val roomId = remember(route.roomIdB32) { Base32.decode(route.roomIdB32) ?: ByteArray(0) }
                val viewModel: InviteViewModel = viewModel(factory = viewModelFactory { initializer { InviteViewModel(roomId, gateway) } })
                InviteScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Route.VoucherRedeem> { backStackEntry ->
                val route: Route.VoucherRedeem = backStackEntry.toRoute()
                val roomId = remember(route.roomIdB32) { Base32.decode(route.roomIdB32) ?: ByteArray(0) }
                val viewModel: VoucherRedeemViewModel = viewModel(factory = viewModelFactory { initializer { VoucherRedeemViewModel(roomId, gateway) } })
                VoucherRedeemScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable<Route.Join> { backStackEntry ->
                val route: Route.Join = backStackEntry.toRoute()
                val link = remember(route.linkUri) { gateway.parseInviteLink(route.linkUri) }
                if (link == null) {
                    // Malformed/stale link somehow reached this route - nothing to show, and
                    // staying here would strand the user on a blank screen with only a back
                    // gesture to escape; pop immediately back to whatever's underneath (RoomsList).
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    val joinViewModel: JoinViewModel = viewModel(factory = viewModelFactory { initializer { JoinViewModel(gateway) } })
                    LaunchedEffect(link) { joinViewModel.openWithLink(link) }
                    JoinScreen(
                        viewModel = joinViewModel,
                        onJoined = { navController.popBackStack() },
                        onCancelled = { navController.popBackStack() },
                    )
                }
            }
        }
        }

        clipboardSuggestion?.let { suggestion ->
            ClipboardInviteBanner(
                onOpen = { navController.navigateToJoin(suggestion); clipboardSuggestion = null },
                onDismiss = {
                    dismissedClipboardText = clipboardManager.getText()?.text
                    clipboardSuggestion = null
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // Mutually exclusive: a stuck outbox is the more urgent, currently-active problem,
        // so it wins this one shared banner slot over the (purely advisory) "install ntfy" hint.
        // bottom = 88.dp (not 16.dp) clears a bottom-end FAB's own footprint - RoomsListScreen/
        // RoomScreen both place one there, and this Box has no visibility into that Scaffold's
        // own layout to avoid it automatically (found on a real device: the plain 16.dp padding
        // let the banner's second line render directly under the FAB). A real Surface (not bare
        // Text floating over the list) is what stops this from reading as illegible overlapping
        // glyphs at large font scale - found on the emulator at font scale 2.0, where the bare-
        // Text version let its own wrapped second line draw directly on top of a room row behind
        // it; a solid, elevated, shaped card reads as a single legible thing regardless of how
        // tall its own text grows, same as a Snackbar would.
        val bannerModifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
        when {
            outboxCount > 0 -> androidx.compose.material3.Surface(
                modifier = bannerModifier.testTag("outboxBanner"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
            ) {
                Text(
                    text = stringResource(R.string.outbox_pending_hint, outboxCount),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            showInstallNtfyHint -> {
                val context = LocalContext.current
                androidx.compose.material3.Surface(
                    modifier = bannerModifier,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    tonalElevation = 6.dp,
                ) {
                    Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = stringResource(R.string.push_install_ntfy_hint), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                        TextButton(
                            onClick = { openNtfyInstallPage(context) },
                            modifier = Modifier.testTag("installNtfyButton"),
                        ) { Text(stringResource(R.string.push_install_ntfy_action)) }
                        // In-session only - re-checked (and shown again if still true) on the next
                        // cold start, same as the hint's own LaunchedEffect above; this is "not
                        // now", not "never ask again".
                        IconButton(
                            onClick = { showInstallNtfyHint = false },
                            modifier = Modifier.testTag("dismissNtfyHintButton"),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_dismiss))
                        }
                    }
                }
            }
        }
    }
}
