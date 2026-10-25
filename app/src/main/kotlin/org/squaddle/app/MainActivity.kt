package org.squaddle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.squaddle.app.data.RealSyncGateway
import org.squaddle.app.ui.onboarding.OnboardingScreen
import org.squaddle.app.ui.onboarding.OnboardingViewModel
import org.squaddle.app.ui.theme.SquaddleTheme

/**
 * Single-activity app (android/CLAUDE.md). Real navigation (rooms -> room -> members/invite,
 * plus the App Link join flow) is wired up incrementally in later commits as each screen lands.
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SquaddleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SquaddleApp()
                }
            }
        }
    }
}

private sealed interface StartupState {
    data object Loading : StartupState
    data object NeedsOnboarding : StartupState
    data object SignedIn : StartupState
}

@Composable
private fun SquaddleApp() {
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
                                RealSyncGateway(session, container.appLocalStore)
                            },
                        )
                    }
                },
            )
            OnboardingScreen(viewModel = viewModel, onFinished = { startupState = StartupState.SignedIn })
        }
        is StartupState.SignedIn -> SignedInPlaceholder() // real rooms list lands in the next commit
    }
}

@Composable
private fun SignedInPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("You're in. Rooms list coming next.")
    }
}
