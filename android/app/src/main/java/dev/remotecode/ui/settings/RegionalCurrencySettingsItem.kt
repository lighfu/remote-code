package dev.remotecode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.remotecode.R
import dev.remotecode.data.Meta
import dev.remotecode.data.inferRegionalCurrency
import dev.remotecode.data.isMonetaryCurrency
import dev.remotecode.data.regionalCurrencyRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Currency
import java.util.Locale

@Composable
internal fun RegionalCurrencySettingsItem(
    selectedCode: String?,
    meta: Meta?,
    onSave: suspend (String?) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val inferredCode = remember(locale) { inferRegionalCurrency(locale) }
    val code = selectedCode ?: inferredCode
    val title = stringResource(R.string.settings_local_currency)
    val automatic = stringResource(R.string.settings_local_currency_automatic, inferredCode)
    val scope = rememberCoroutineScope()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    SegmentedListItem(
        onClick = { showPicker = true },
        shapes = ListItemDefaults.segmentedShapes(0, 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = { Icon(Icons.Rounded.Paid, null) },
        supportingContent = {
            Column {
                Text(if (selectedCode == null) automatic else currencyName(code, locale) + " · " + code)
                if (regionalCurrencyRate(code, meta) == null) {
                    Text(stringResource(R.string.settings_local_currency_rate_unavailable))
                }
            }
        },
    ) { Text(title) }

    if (showPicker) {
        val rateCodes = meta?.usdRates?.keys.orEmpty()
        val choices = remember(rateCodes, inferredCode, selectedCode, locale) {
            (countryCurrencies + rateCodes + listOfNotNull(inferredCode, selectedCode))
                .filter(::isMonetaryCurrency)
                .sorted()
                .map { CurrencyChoice(it, currencyName(it, locale)) }
        }
        var query by rememberSaveable { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var saveFailed by rememberSaveable { mutableStateOf(false) }
        val matching = remember(choices, query) {
            val term = query.trim()
            choices.filter { it.code.contains(term, ignoreCase = true) || it.name.contains(term, ignoreCase = true) }
        }

        fun select(code: String?) {
            if (saving) return
            saving = true
            saveFailed = false
            scope.launch {
                try {
                    onSave(code)
                    showPicker = false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    saveFailed = true
                } finally {
                    saving = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!saving) showPicker = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_local_currency_search)) },
                        singleLine = true,
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).selectableGroup()) {
                        item {
                            CurrencyOption(automatic, null, selectedCode == null, !saving) { select(null) }
                        }
                        items(matching, key = { it.code }) { currency ->
                            CurrencyOption(currency.name, currency.code, selectedCode == currency.code, !saving) {
                                select(currency.code)
                            }
                        }
                        if (matching.isEmpty()) item { Text(stringResource(R.string.settings_local_currency_no_match)) }
                    }
                    if (saveFailed) {
                        Text(stringResource(R.string.chat_chat_screen_038), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }, enabled = !saving) {
                    Text(stringResource(R.string.locale_cancel))
                }
            },
        )
    }
}

@Composable
private fun CurrencyOption(title: String, code: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(title)
            if (code != null) Text(code, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class CurrencyChoice(val code: String, val name: String)

private fun currencyName(code: String, locale: Locale): String = Currency.getInstance(code).getDisplayName(locale)

private val countryCurrencies by lazy {
    Locale.getISOCountries().mapNotNullTo(mutableSetOf()) { region ->
        runCatching { Currency.getInstance(Locale.Builder().setRegion(region).build())?.currencyCode }.getOrNull()
    }
}
