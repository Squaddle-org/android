package org.squaddle.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Rounder than M3's own defaults across the board - the "sticky-note" cut of the brand (soft,
// hand-placed rectangles) without going all the way to pill/blob shapes, which would read as
// cartoonish rather than calm. Idea/room cards in particular get a visibly generous corner radius
// (large) so they read as distinct "notes" rather than flat list rows.
internal val SquaddleShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
