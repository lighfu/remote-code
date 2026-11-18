package dev.remotecode.ui.common

import dev.remotecode.R
import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun providerIcon(provider: String): ImageVector = if (provider == "codex") Icons.Rounded.Terminal else Icons.Rounded.AutoAwesome

private val CodexBlue = androidx.compose.ui.graphics.Color(0xFF3143FF)
private val CodexBlueLight = androidx.compose.ui.graphics.Color(0xFF7D88FF)
private val ClaudeOrange = androidx.compose.ui.graphics.Color(0xFFD97757)

private val AntigravityViolet = androidx.compose.ui.graphics.Color(0xFF6F6BF2)
private val AntigravityGradient = listOf(androidx.compose.ui.graphics.Color(0xFF9A6BFF), androidx.compose.ui.graphics.Color(0xFF3D8BFF))

@Composable
fun brandColor(provider: String): androidx.compose.ui.graphics.Color = when (provider) {
    "codex" -> if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) CodexBlueLight else CodexBlue
    "antigravity" -> AntigravityViolet
    else -> ClaudeOrange
}

@Composable
fun BrandIcon(provider: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        when (provider) {
            "antigravity" -> androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(dev.remotecode.R.drawable.brand_antigravity),
                contentDescription = "Antigravity",
                modifier = Modifier
                    .size(size * 0.9f)
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            androidx.compose.ui.graphics.Brush.linearGradient(AntigravityGradient),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn,
                        )
                    },
            )
            else -> {
                val codex = provider == "codex"
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(if (codex) dev.remotecode.R.drawable.brand_openai_white else dev.remotecode.R.drawable.brand_claude_mark),
                    contentDescription = if (codex) "Codex" else "Claude",
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(brandColor(provider)),
                    modifier = Modifier.size(size * if (codex) 0.84f else 0.94f),
                )
            }
        }
    }
}

@Composable
fun ProviderBadge(provider: String, running: Boolean, waiting: Boolean, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (running) {
            androidx.compose.material3.CircularProgressIndicator(
                color = brandColor(provider),
                strokeWidth = size * 0.06f,
                modifier = Modifier.size(size),
            )
            BrandIcon(provider, size * 0.72f)
        } else {
            BrandIcon(provider, size * 0.86f)
        }
        if (waiting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(size * 0.28f)
                    .clip(MaterialShapes.Circle.toShape())
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

fun shortPath(path: String, keep: Int = 2): String {
    val parts = path.replace('\\', '/').trimEnd('/').split('/').filter { it.isNotEmpty() }
    if (parts.size <= keep) return path
    return "…/" + parts.takeLast(keep).joinToString("/")
}

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val d = (now - ms) / 1000
    return when {
        d < 45 -> appString(R.string.screen_common_001)
        d < 3600 -> appString(R.string.screen_common_002, d / 60)
        d < 86400 -> appString(R.string.screen_common_003, d / 3600)
        d < 86400 * 7 -> appString(R.string.screen_common_004, d / 86400)
        else -> SimpleDateFormat("M/d", Locale.JAPAN).format(Date(ms))
    }
}

fun clockTime(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(ms))

fun statusLabel(status: String, pending: Int): String = when {
    pending > 0 || status == "waiting" -> appString(R.string.screen_common_005)
    status == "running" -> appString(R.string.screen_common_006)
    status == "error" -> appString(R.string.screen_common_007)
    else -> appString(R.string.screen_common_008)
}
