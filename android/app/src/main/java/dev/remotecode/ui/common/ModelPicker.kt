package dev.remotecode.ui.common

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.ModelOption
import dev.remotecode.data.Providers

fun effortLabel(e: String) = e

val UltraColor = Color(0xFF9B4DFF)
private val UltraColors = listOf(Color(0xFF6A2BFF), Color(0xFFB45CE6), Color(0xFFE38BFF), Color(0xFF8A3DFF), Color(0xFF6A2BFF))

@Composable
fun rememberUltraBrush(width: Float = 240f): androidx.compose.ui.graphics.Brush {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ultra")
    val shift by t.animateFloat(
        initialValue = 0f,
        targetValue = width * 2,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(2400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "ultraShift",
    )
    return androidx.compose.ui.graphics.Brush.linearGradient(
        UltraColors,
        start = androidx.compose.ui.geometry.Offset(shift - width * 2, 0f),
        end = androidx.compose.ui.geometry.Offset(shift, width / 4),
        tileMode = androidx.compose.ui.graphics.TileMode.Repeated,
    )
}

@Composable
fun UltraText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    Text(text, style = style.copy(brush = rememberUltraBrush(), fontWeight = FontWeight.Bold), maxLines = 1, modifier = modifier)
}

/** 強さは 1〜5、不明は 0。 */
fun effortLevel(e: String?): Int = when (e?.lowercase()) {
    "none", "minimal", "low" -> 1
    "medium" -> 2
    "high" -> 3
    "xhigh" -> 4
    "max" -> 5
    else -> 0
}

fun effortColor(e: String?): Color = when (e?.lowercase()) {
    "low" -> Color(0xFF3FB68B)
    "medium" -> Color(0xFF4C9AE6)
    "high" -> Color(0xFFE0A526)
    "xhigh" -> Color(0xFFF07A3A)
    "max" -> Color(0xFFE5484D)
    else -> Color(0xFF8A9BA8)
}

fun effortDescription(e: String?): String = when (e?.lowercase()) {
    "none" -> appString(R.string.screen_model_picker_001)
    "minimal" -> appString(R.string.screen_model_picker_002)
    "low" -> appString(R.string.screen_model_picker_003)
    "medium" -> appString(R.string.screen_model_picker_004)
    "high" -> appString(R.string.screen_model_picker_005)
    "xhigh" -> appString(R.string.screen_model_picker_006)
    "max" -> appString(R.string.screen_model_picker_007)
    else -> ""
}

@Composable
fun EffortBars(effort: String?, modifier: Modifier = Modifier, height: Dp = 14.dp, color: Color = effortColor(effort)) {
    val level = effortLevel(effort)
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(0.36f + 0.16f * (i - 1))
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) color else color.copy(alpha = 0.22f)),
            )
        }
    }
}

@Composable
private fun EffortChip(e: String, selected: Boolean, onClick: () -> Unit) {
    val c = effortColor(e)
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = { EffortBars(e) },
        label = { Text(effortLabel(e), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = c,
            selectedLabelColor = c,
            selectedContainerColor = c.copy(alpha = 0.2f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = c.copy(alpha = 0.4f),
            selectedBorderColor = c,
            selectedBorderWidth = 1.5.dp,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelPicker(
    provider: String,
    model: String?,
    effort: String?,
    onDismiss: () -> Unit,
    /** null なら Ultra の切替を表示しない。 */
    ultra: Boolean? = null,
    /** null は PC の設定に従い、未操作ならそのまま返す。 */
    fastMode: Boolean? = null,
    fastModeState: String? = null,
    onApply: (model: String?, effort: String?, ultra: Boolean?, fast: Boolean?) -> Unit,
) {
    var selUltra by remember { mutableStateOf(ultra) }
    var selFast by remember(provider) { mutableStateOf(fastMode) }
    var fastTouched by remember(provider) { mutableStateOf(false) }
    LaunchedEffect(fastMode) { if (!fastTouched) selFast = fastMode }
    val repo = RemoteCodeApp.instance.repository
    val cached by repo.models.collectAsState()
    val models = cached[provider]
    var error by remember { mutableStateOf<String?>(null) }
    val def = pcDefaults(provider)
    var touched by remember { mutableStateOf(false) }
    var selModel by remember { mutableStateOf(models?.let { matchModelOption(model ?: def?.model, it) } ?: model) }
    var selEffort by remember(def?.effort) { mutableStateOf(effort ?: def?.effort) }
    /** Ultra を外したときに戻す強さ。 */
    var plainEffort by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(provider) {
        runCatching { repo.refreshModels(provider) }.onFailure { if (repo.models.value[provider] == null) error = it.message }
    }
    LaunchedEffect(models, def?.model) {
        if (!touched && models != null) selModel = matchModelOption(model ?: def?.model, models)
    }
    ModalBottomSheet(modifier = dev.remotecode.ui.common.hapticArea(), onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.screen_model_picker_008, Providers.label(provider)), style = MaterialTheme.typography.headlineSmall)
            val list = models
            when {
                list == null && error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                else -> {
                    val selectedOption = resolveModelOption(selModel, list)
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        list.forEach { m ->
                            ModelRow(m.label + if (m.isDefault) appString(R.string.screen_model_picker_009) else "", listOf(m.id, m.description).filter { it.isNotBlank() }.joinToString(" · "), selectedOption?.id == m.id) {
                                touched = true
                                selModel = m.id
                                if ((provider == "claude" || provider == "codex") && m.supportsFastMode == false) {
                                    selFast = false
                                    fastTouched = true
                                }
                                if (selEffort != null && selEffort !in m.efforts) selEffort = m.efforts.firstOrNull { it == def?.effort } ?: m.efforts.lastOrNull()
                            }
                        }
                    }
                    val efforts = selectedOption?.efforts ?: list.flatMap { it.efforts }.distinct()
                    // Codex の ultra も Claude と同じ専用行で扱う。
                    val ultraEffort = efforts.firstOrNull { it.equals("ultra", ignoreCase = true) }
                    val plainEfforts = efforts.filter { it != ultraEffort }
                    if (plainEfforts.isNotEmpty()) {
                        Text(stringResource(R.string.screen_model_picker_010), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            plainEfforts.forEach { e -> EffortChip(e, selEffort == e) { selEffort = e } }
                        }
                        effortDescription(selEffort).takeIf { it.isNotEmpty() }?.let { desc ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                EffortBars(selEffort, height = 12.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    "${selEffort?.let(::effortLabel)}: $desc",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = effortColor(selEffort),
                                )
                            }
                        }
                    }
                    val ultraOn: Boolean? = selUltra ?: ultraEffort?.let { selEffort == it }
                    val setUltra: (Boolean) -> Unit = { want ->
                        if (selUltra != null) selUltra = want
                        else if (ultraEffort != null) {
                            if (want) {
                                if (selEffort != ultraEffort) plainEffort = selEffort
                                selEffort = ultraEffort
                            } else {
                                selEffort = plainEffort?.takeIf { it in plainEfforts } ?: plainEfforts.firstOrNull { it == "high" } ?: plainEfforts.lastOrNull()
                            }
                        }
                    }
                    ultraOn?.let { on ->
                        Row(
                            Modifier.fillMaxWidth().clickable { setUltra(!on) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ultraBrush = rememberUltraBrush(80f)
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Rounded.AutoAwesome,
                                null,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(ultraBrush, blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
                                    },
                            )
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                UltraText("Ultra", MaterialTheme.typography.titleSmall)
                                Text(
                                    if (selUltra != null) appString(R.string.screen_model_picker_011)
                                    else appString(R.string.screen_model_picker_012),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = on,
                                onCheckedChange = { setUltra(it) },
                                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = UltraColor, checkedThumbColor = Color.White),
                            )
                        }
                    }
                }
            }
            if (provider == "claude" || provider == "codex") {
                val fastSupported = list?.let { resolveModelOption(selModel, it) }?.supportsFastMode != false
                val fastOn = selFast == true && fastSupported && (fastModeState != "off" || fastTouched)
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = fastOn,
                        enabled = fastSupported,
                        role = Role.Switch,
                        onValueChange = { selFast = it; fastTouched = true },
                    ).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.Bolt,
                        null,
                        tint = if (fastOn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Fast mode · ${if (fastOn) "ON" else if (!fastSupported || selFast != null) "OFF" else appString(R.string.screen_model_picker_013)}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when {
                                !fastSupported -> appString(R.string.screen_model_picker_014)
                                fastOn && fastModeState == "cooldown" -> appString(R.string.screen_model_picker_015)
                                else -> appString(R.string.screen_model_picker_016)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(checked = fastOn, onCheckedChange = null, enabled = fastSupported)
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val fast = when {
                        !fastTouched || (provider != "claude" && provider != "codex") -> null
                        list?.let { resolveModelOption(selModel, it) }?.supportsFastMode == false -> false
                        else -> selFast
                    }
                    onApply(selModel, selEffort, selUltra, fast)
                },
                enabled = models != null,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(stringResource(R.string.screen_model_picker_017)) }
        }
    }
}

@Composable
private fun ModelRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

fun modelLabel(model: String?, effort: String?, defaults: dev.remotecode.data.ProviderDefaults? = null): String {
    val m = model.concreteModel() ?: defaults?.model?.let { "$it（PC）" } ?: appString(R.string.screen_model_picker_018)
    val e = effort ?: defaults?.effort
    return if (e != null) "$m · ${effortLabel(e)}" else m
}

@Composable
fun pcDefaults(provider: String?): dev.remotecode.data.ProviderDefaults? {
    val meta by RemoteCodeApp.instance.repository.meta.collectAsState()
    return provider?.let { meta?.defaults?.get(it) }
}
