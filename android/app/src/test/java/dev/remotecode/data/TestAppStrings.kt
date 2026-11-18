package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver

internal val testAppStrings: AppStringResolver = { id, args ->
    when (id) {
        R.string.core_quote_source -> "\n\nsource-marker: ${args[0]}"
        R.string.core_quote_lines -> "\nline-marker: ${args[0]}"
        R.string.core_quote_unsaved_note -> "\ndraft-marker"
        else -> "message-$id:${args.joinToString()}"
    }
}
