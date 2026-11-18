package dev.remotecode.ui.common

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

private fun draftPrefs(context: Context): SharedPreferences = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)

private fun saveDraft(prefs: SharedPreferences, key: String, value: String) {
    val cur = prefs.getString(key, "").orEmpty()
    if (cur == value) return
    prefs.edit().apply { if (value.isBlank()) remove(key) else putString(key, value) }.apply()
}

/** 下書きは遅延保存し、画面離脱時に即時保存する。 */
@Composable
fun rememberDraft(key: String): MutableState<String> {
    val context = LocalContext.current
    val prefs = remember { draftPrefs(context.applicationContext) }
    val state = rememberSaveable(key) { mutableStateOf(prefs.getString(key, "").orEmpty()) }
    LaunchedEffect(key, state.value) {
        delay(400)
        saveDraft(prefs, key, state.value)
    }
    DisposableEffect(key) {
        onDispose { saveDraft(prefs, key, state.value) }
    }
    return state
}
