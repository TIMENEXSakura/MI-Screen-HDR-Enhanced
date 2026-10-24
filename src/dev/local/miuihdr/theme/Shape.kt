// Adapted from SukiSU-Ultra, commit df84920b20a925fd1135530f6e403361ad494d7b. GPL-3.0.
package dev.local.miuihdr.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Rounder than the Material defaults to give cards and dialogs a softer, distinct silhouette.
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
