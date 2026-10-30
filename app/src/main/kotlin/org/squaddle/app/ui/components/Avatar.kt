package org.squaddle.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * A member's avatar: always a color deterministically derived from their user key (so the same
 * person looks the same everywhere, with no network round-trip and no key material ever leaving
 * the device to fetch one), filled with their initials when a display name is known or a small
 * geometric mark (not a blank circle) when it isn't - a pending joiner, or a member who joined
 * from an older client that never set one. Matches the spec's "deterministic color + initials or
 * geometric pattern" line directly: this is both, picked per-member by what's actually available.
 */
@Composable
internal fun Avatar(userKey: ByteArray, displayName: String?, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val color = avatarColor(userKey)
    val onColor = if (color.luminance() > 0.5f) Color(0xFF201A17) else Color.White
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        val initials = initialsOf(displayName)
        if (initials != null) {
            Text(initials, color = onColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        } else {
            GeometricMark(userKey, tint = onColor, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** A stable hue per key (HSL, fixed saturation/lightness tuned to sit well against both the
 * primary-container-tinted UI and plain surfaces) - a simple sum-of-bytes hash is enough entropy
 * for "looks different across a room's handful of members," not a security property. */
private fun avatarColor(userKey: ByteArray): Color {
    val hash = userKey.fold(0) { acc, b -> acc * 31 + b.toInt() }
    val hue = (hash.absoluteValue % 360).toFloat()
    return Color.hsl(hue = hue, saturation = 0.55f, lightness = 0.62f)
}

private fun initialsOf(displayName: String?): String? {
    val name = displayName?.trim().orEmpty()
    if (name.isEmpty()) return null
    val parts = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        else -> parts[0].take(1).uppercase()
    }
}

/** No display name yet (pending approval, or an old client that never called profile.set) - a
 * deterministic 3x3 dot grid (mirrored left/right, like a minimal identicon) beats a blank circle
 * or a generic "?" for "this is a real, specific person, we just don't know their name." */
@Composable
private fun GeometricMark(userKey: ByteArray, tint: Color, modifier: Modifier = Modifier) {
    val bits = userKey.fold(0) { acc, b -> (acc shl 1) xor (b.toInt() and 1) }
    Canvas(modifier = modifier) {
        val cell = size.width / 3f
        for (row in 0 until 3) {
            for (col in 0 until 2) {
                val bitIndex = row * 2 + col
                val on = ((bits shr bitIndex) and 1) == 1
                if (!on) continue
                val cx = (col + 0.5f) * cell
                val cy = (row + 0.5f) * cell
                drawCircle(color = tint, radius = cell * 0.32f, center = Offset(cx, cy))
                drawCircle(color = tint, radius = cell * 0.32f, center = Offset(size.width - cx, cy))
            }
        }
    }
}
