package org.squaddle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Single-activity app (android/CLAUDE.md). Real navigation (onboarding -> rooms -> room ->
 * members/invite, plus the App Link join flow) is wired up incrementally in later commits as each
 * screen lands - this scaffold commit just needs something that builds and launches.
 */
public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SquaddlePlaceholder()
        }
    }
}

@Composable
private fun SquaddlePlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Squaddle")
    }
}
