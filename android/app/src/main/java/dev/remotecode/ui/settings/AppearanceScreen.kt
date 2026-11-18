package dev.remotecode.ui.settings

import dev.remotecode.R

import dev.remotecode.i18n.appString
import androidx.compose.ui.res.stringResource

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.remotecode.RemoteCodeApp
import dev.remotecode.data.Appearance
import dev.remotecode.ui.theme.Accents
import dev.remotecode.ui.theme.BodyFonts
import dev.remotecode.ui.theme.CodeFonts
import dev.remotecode.ui.theme.googleFontFamily
import kotlinx.coroutines.launch

private enum class FontTarget { UI, BODY, CODE }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val repo = RemoteCodeApp.instance.repository
    val settings by repo.settings.collectAsState()
    val a = settings?.appearance ?: Appearance()
    val scope = rememberCoroutineScope()
    fun save(next: Appearance) = scope.launch { repo.settingsStore.setAppearance(next) }
    // ドラッグ中は表示だけ変え、離したときに保存する。
    var textScale by remember(a.textScale) { mutableFloatStateOf(a.textScale) }
    var codeScale by remember(a.codeScale) { mutableFloatStateOf(a.codeScale) }
    var fontTarget by rememberSaveable { mutableStateOf<FontTarget?>(null) }
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    fun stepped(old: Float, new: Float, step: Float) { if (Math.round(old / step) != Math.round(new / step)) haptics.fineTick() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_appearance_001)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.screen_appearance_002)) } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section(appString(R.string.screen_appearance_003))
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                val modes = listOf(Triple("system", appString(R.string.screen_appearance_004), Icons.Rounded.PhoneAndroid), Triple("light", appString(R.string.screen_appearance_005), Icons.Rounded.LightMode), Triple("dark", appString(R.string.screen_appearance_006), Icons.Rounded.DarkMode))
                modes.forEachIndexed { i, (id, label, icon) ->
                    ToggleButton(
                        checked = a.themeMode == id,
                        onCheckedChange = { haptics.tick(); save(a.copy(themeMode = id)) },
                        shapes = when (i) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            SwitchRow(appString(R.string.screen_appearance_007), appString(R.string.screen_appearance_008), a.amoled) { save(a.copy(amoled = it)) }

            Section(appString(R.string.screen_appearance_009))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(appString(R.string.screen_appearance_010), appString(R.string.screen_appearance_011), a.dynamicColor) { save(a.copy(dynamicColor = it)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Accents.forEach { acc ->
                    val selected = !a.dynamicColor && a.accent == acc.id
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { haptics.tick(); save(a.copy(accent = acc.id, dynamicColor = false)) }) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(MaterialShapes.Cookie9Sided.toShape())
                                .background(Color(acc.light[0]))
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, MaterialShapes.Cookie9Sided.toShape()) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(22.dp).clip(MaterialShapes.Circle.toShape()).background(Color(acc.light[2])))
                            if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White)
                        }
                        Text(acc.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Section(appString(R.string.screen_appearance_012))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.screen_appearance_013), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${(textScale * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = textScale, onValueChange = { stepped(textScale, it, 0.05f); textScale = it }, onValueChangeFinished = { haptics.edge(); save(a.copy(textScale = textScale)) }, valueRange = 0.8f..1.4f, steps = 11)
                    Text(stringResource(R.string.screen_appearance_014), fontSize = 16.sp * textScale, fontFamily = if (a.bodyFont.isBlank()) FontFamily.Default else googleFontFamily(a.bodyFont, FontFamily.Default))
                    Spacer(Modifier.size(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.screen_appearance_015), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${(codeScale * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(value = codeScale, onValueChange = { stepped(codeScale, it, 0.05f); codeScale = it }, onValueChangeFinished = { haptics.edge(); save(a.copy(codeScale = codeScale)) }, valueRange = 0.7f..1.5f, steps = 15)
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp)) {
                        Text(stringResource(R.string.screen_appearance_016), fontSize = 12.sp * codeScale, fontFamily = googleFontFamily(a.codeFont, FontFamily.Monospace))
                    }
                    TextButton(onClick = { textScale = 1f; codeScale = 1f; save(a.copy(textScale = 1f, codeScale = 1f)) }) { Text(stringResource(R.string.screen_appearance_017)) }
                }
            }

            Section(stringResource(R.string.screen_appearance_fonts))
            FontRow(stringResource(R.string.screen_appearance_ui_font), BodyFonts, a.uiFont) { fontTarget = FontTarget.UI }
            FontRow(stringResource(R.string.screen_appearance_018), BodyFonts, a.bodyFont) { fontTarget = FontTarget.BODY }
            FontRow(stringResource(R.string.screen_appearance_021), CodeFonts, a.codeFont) { fontTarget = FontTarget.CODE }
        }
    }
    fontTarget?.let { target ->
        val code = target == FontTarget.CODE
        FontPickerDialog(
            title = stringResource(when (target) {
                FontTarget.UI -> R.string.screen_appearance_ui_font
                FontTarget.BODY -> R.string.screen_appearance_018
                FontTarget.CODE -> R.string.screen_appearance_021
            }),
            fonts = if (code) CodeFonts else BodyFonts,
            selected = when (target) { FontTarget.UI -> a.uiFont; FontTarget.BODY -> a.bodyFont; FontTarget.CODE -> a.codeFont },
            sample = stringResource(when (target) {
                FontTarget.UI -> R.string.screen_appearance_ui_sample
                FontTarget.BODY -> R.string.screen_appearance_020
                FontTarget.CODE -> R.string.screen_appearance_022
            }),
            fallback = if (code) FontFamily.Monospace else FontFamily.Default,
            onDismiss = { fontTarget = null },
            onPick = { font ->
                save(when (target) {
                    FontTarget.UI -> a.copy(uiFont = font)
                    FontTarget.BODY -> a.copy(bodyFont = font)
                    FontTarget.CODE -> a.copy(codeFont = font)
                })
                fontTarget = null
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    ListItem(
        onClick = { haptics.toggle(!checked); onChange(!checked) },
        supportingContent = { Text(sub) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shapes = ListItemDefaults.shapes(shape = RoundedCornerShape(20.dp)),
    ) { Text(title) }
}

@Composable
private fun FontRow(title: String, fonts: List<Pair<String, String>>, selected: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = { Text(fonts.firstOrNull { it.first == selected }?.second ?: selected) },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shapes = ListItemDefaults.shapes(shape = RoundedCornerShape(20.dp)),
    ) { Text(title) }
}

@Composable
private fun FontPickerDialog(title: String, fonts: List<Pair<String, String>>, selected: String, sample: String, fallback: FontFamily, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val haptics = dev.remotecode.ui.common.rememberHaptics()
    var draft by rememberSaveable(selected) { mutableStateOf(selected) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = fonts.indexOfFirst { it.first == selected }.coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.screen_appearance_019), style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    state = state,
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                ) {
                    itemsIndexed(fonts, key = { _, font -> font.first }) { i, (id, label) ->
                        val family = remember(id, fallback) { googleFontFamily(id, fallback) }
                        androidx.compose.material3.SegmentedListItem(
                            onClick = { haptics.tick(); draft = id },
                            shapes = ListItemDefaults.segmentedShapes(i, fonts.size),
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = if (id == draft) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            leadingContent = { RadioButton(selected = id == draft, onClick = null) },
                            supportingContent = { Text(sample, fontFamily = family, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        ) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(draft) }) { Text(stringResource(R.string.screen_appearance_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screen_appearance_cancel)) } },
    )
}
