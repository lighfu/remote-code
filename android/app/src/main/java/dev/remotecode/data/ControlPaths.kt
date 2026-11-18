package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.AppStringResolver
import dev.remotecode.i18n.applicationStrings
import dev.remotecode.i18n.text
import kotlinx.serialization.Serializable

/** 実装・設定に基づく経路。疎通確認結果ではない。 */
@Serializable
data class ControlPathsSnapshot(
    val schemaVersion: Int = 1,
    val at: Long = 0,
    val providers: List<ControlProvider> = emptyList(),
)

@Serializable
data class ControlProvider(
    val id: String = "",
    val label: String = "",
    val modes: List<ControlMode> = emptyList(),
    val routes: List<ControlRoute> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class ControlMode(
    val id: String = "",
    val label: String = "",
    /** 負数は件数不明。 */
    val activeSessions: Int = -1,
    val availability: String = "supported",
    val description: String = "",
)

@Serializable
data class ControlRoute(
    val id: String = "",
    val modeId: String? = null,
    val purpose: String = "operate",
    val title: String = "",
    val summary: String = "",
    val steps: List<ControlStep> = emptyList(),
    val notes: List<String> = emptyList(),
)

@Serializable
data class ControlStep(
    val id: String = "",
    val label: String = "",
    val description: String = "",
    val kind: String = "host",
    val protocol: String? = null,
)

internal data class ControlPathsSelection(val providerId: String, val modeId: String?)

internal fun ControlPathsSnapshot.resolveSelection(providerId: String?, modeId: String?): ControlPathsSelection? {
    val provider = providers.find { it.id == providerId } ?: providers.firstOrNull() ?: return null
    val mode = provider.modes.find { it.id == modeId } ?: provider.modes.firstOrNull()
    return ControlPathsSelection(provider.id, mode?.id)
}

/** modeId が null なら全モード共通。 */
internal fun ControlProvider.routesFor(modeId: String?, purpose: String): List<ControlRoute> = routes.filter {
    it.purpose == purpose && (it.modeId == null || it.modeId == modeId)
}

internal fun controlAvailabilityLabel(availability: String, strings: AppStringResolver = applicationStrings): String = when (availability) {
    "supported" -> strings.text(R.string.core_control_supported)
    "readOnly" -> strings.text(R.string.core_control_read_only)
    "requiresSetup" -> strings.text(R.string.core_control_setup_required)
    else -> strings.text(R.string.core_control_check_configuration)
}
