package dev.remotecode.ui.common

import dev.remotecode.data.ModelOption

private fun normalizeModel(s: String): String =
    s.lowercase()
        .replace(Regex("""\[.*]$"""), "")
        .removePrefix("claude-")
        .replace(Regex("""-\d{8}$"""), "")
        .replace(Regex("""^([a-z]+)-(\d+)(?:-(\d+))?$""")) { m -> m.groupValues[1] + " " + m.groupValues[2] + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { ".$it" } ?: "") }
        .trim()

fun resolveModelOption(model: String?, options: List<ModelOption>): ModelOption? {
    if (model.isNullOrBlank()) return options.firstOrNull { it.isDefault } ?: options.firstOrNull { it.id == "default" }
    options.firstOrNull { it.id == model }?.let { return it }
    val named = options.filter { !it.isDefault && it.id != "default" }
    // default 行より明示的なモデル行を優先する。
    named.firstOrNull { it.resolvedModel == model }?.let { return it }
    options.firstOrNull { it.resolvedModel == model }?.let { return it }
    val want = normalizeModel(model)
    return named.firstOrNull { normalizeModel(it.label) == want }
        ?: named.firstOrNull { normalizeModel(it.id) == want }
        ?: named.firstOrNull { it.resolvedModel?.let(::normalizeModel) == want }
}

fun matchModelOption(model: String?, options: List<ModelOption>): String? =
    resolveModelOption(model, options)?.id ?: model
