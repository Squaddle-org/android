package org.squaddle.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * The one illustration shape every empty state in the app reuses: two tilted "sticky notes"
 * (rounded rects, brand-tinted container colors, drawn by hand here - no bitmap/SVG asset) fanned
 * behind a plain M3 icon - the literal "group chat meets sticky-note board" brief line, rendered
 * as a shape instead of left as a metaphor. [icon] is the only thing callers vary (lightbulb for
 * no-ideas, event for no-events, people for no-rooms), so the three empty states read as one
 * family rather than three unrelated pieces of art.
 */
@Composable
internal fun StickyNoteIllustration(icon: ImageVector, modifier: Modifier = Modifier) {
    val noteBack = MaterialTheme.colorScheme.tertiaryContainer
    val noteFront = MaterialTheme.colorScheme.secondaryContainer
    val iconTint = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(140.dp)) {
            val noteSize = Size(width = size.width * 0.62f, height = size.height * 0.62f)
            rotate(degrees = -10f, pivot = center) {
                drawRoundRect(
                    color = noteBack,
                    topLeft = Offset((size.width - noteSize.width) / 2f, (size.height - noteSize.height) / 2f),
                    size = noteSize,
                    cornerRadius = CornerRadius(20f, 20f),
                )
            }
            rotate(degrees = 8f, pivot = center) {
                drawRoundRect(
                    color = noteFront,
                    topLeft = Offset((size.width - noteSize.width) / 2f, (size.height - noteSize.height) / 2f),
                    size = noteSize,
                    cornerRadius = CornerRadius(20f, 20f),
                )
            }
        }
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(44.dp))
    }
}
