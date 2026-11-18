package dev.remotecode.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class ModeVisual(val icon: ImageVector, val color: Color)

fun modeVisual(id: String?): ModeVisual = when (id) {
    "default", "untrusted" -> ModeVisual(Icons.Rounded.Shield, Color(0xFF4F8EF7))
    "read-only" -> ModeVisual(Icons.Rounded.Visibility, Color(0xFF7E8CA8))
    "acceptEdits" -> ModeVisual(Icons.Rounded.EditNote, Color(0xFF2EB67D))
    "auto" -> ModeVisual(Icons.Rounded.AutoMode, Color(0xFF14A6B8))
    "plan" -> ModeVisual(Icons.Rounded.Map, Color(0xFFE0A526))
    "bypassPermissions", "full" -> ModeVisual(Icons.Rounded.LockOpen, Color(0xFFE5484D))
    else -> ModeVisual(Icons.Rounded.Computer, Color(0xFF9AA0A6))
}
