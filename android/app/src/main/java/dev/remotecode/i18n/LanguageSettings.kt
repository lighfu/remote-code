package dev.remotecode.i18n

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import java.util.Locale

private fun languageName(tag: String): String = Locale.forLanguageTag(if (tag == "zh-CN") "zh-Hans" else tag).let { it.getDisplayName(it) }

@Composable
fun LanguageSettingsItem() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var expanded by remember { mutableStateOf(false) }
    val manager = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java) else null
    val current = if (Build.VERSION.SDK_INT >= 33) manager?.applicationLocales?.toLanguageTags().orEmpty() else ""
    val currentTag = current.substringBefore(',').trim()
    val selected = resolveAppLanguageTag(current)
    val title = stringResource(R.string.locale_title)
    val automatic = stringResource(R.string.locale_automatic)
    val systemName = configuration.locales[0]?.let { it.getDisplayName(it) }.orEmpty()

    SegmentedListItem(
        onClick = {
            if (Build.VERSION.SDK_INT >= 33) expanded = true
            else runCatching { context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }
                .onFailure { Toast.makeText(context, appString(R.string.locale_open_failed), Toast.LENGTH_SHORT).show() }
        },
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(Icons.Rounded.Language, null) },
        supportingContent = {
            Text(if (Build.VERSION.SDK_INT >= 33) if (currentTag.isEmpty()) automatic else languageName(selected.ifEmpty { currentTag })
            else stringResource(R.string.locale_system_hint) + " · " + systemName)
        },
    ) { Text(title) }

    if (expanded && Build.VERSION.SDK_INT >= 33) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            icon = { Icon(Icons.Rounded.Language, null) },
            title = { Text(title) },
            text = {
                Column {
                    for (tag in listOf("") + supportedLanguages) {
                        fun select() {
                            expanded = false
                            manager?.applicationLocales = LocaleList.forLanguageTags(tag)
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = ::select), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = if (tag.isEmpty()) currentTag.isEmpty() else selected == tag, onClick = ::select)
                            Text(if (tag.isEmpty()) automatic else languageName(tag))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.locale_cancel)) } },
        )
    }
}
