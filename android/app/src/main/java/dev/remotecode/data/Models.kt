package dev.remotecode.data

import dev.remotecode.R
import dev.remotecode.i18n.appString
import kotlinx.serialization.Serializable

@Serializable
data class SessionInfo(
    val id: String,
    val provider: String,
    val title: String,
    val cwd: String,
    val status: String = "idle",
    val mode: String = "",
    val thinking: String = "full",
    val model: String? = null,
    val effort: String? = null,
    /** 自動要約までのトークン数。null はモデル既定。 */
    val compactWindow: Long? = null,
    val ultra: Boolean = false,
    /** null は PC 設定に従うか、状態不明。 */
    val fastMode: Boolean? = null,
    /** on / off / cooldown */
    val fastModeState: String? = null,
    val context: ContextUsage? = null,
    val stats: SessionStats? = null,
    val plan: SessionPlan? = null,
    /** PC セッションの監視のみ。 */
    val watching: Boolean = false,
    val externalId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val preview: String = "",
    val live: Boolean = false,
    val pending: Int = 0,
    val archived: Boolean = false,
    val backgroundCommandCount: Int = 0,
) {
    val isRunning get() = status == "running"
    val isWaiting get() = status == "waiting" || pending > 0
    val isBackgroundCommandOnly get() = backgroundCommandCount > 0 && !isRunning && !isWaiting
    val fastModeActive get() = fastMode == true && fastModeState != "off"
}

@Serializable
data class AppUpdateInfo(
    val available: Boolean = false,
    val sha256: String? = null,
    val size: Long = 0,
    val builtAt: Long = 0,
    val versionName: String? = null,
    val versionCode: Long? = null,
)

@Serializable
data class ApprovalOption(val id: String, val label: String, val kind: String = "other")

@Serializable
data class QuestionOption(val label: String, val description: String = "")

@Serializable
data class Question(
    val id: String,
    val header: String = "",
    val question: String,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val allowOther: Boolean = true,
)

@Serializable
data class Entry(
    val id: String,
    val ts: Long = 0,
    val kind: String,
    val text: String? = null,
    val streaming: Boolean = false,
    val tool: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val output: String? = null,
    val status: String? = null,
    val requestId: String? = null,
    val options: List<ApprovalOption>? = null,
    val choice: String? = null,
    val questions: List<Question>? = null,
    val answers: Map<String, List<String>>? = null,
    val level: String? = null,
    val ok: Boolean? = null,
    val images: Int? = null,
    val parent: String? = null,
    val progress: String? = null,
    val path: String? = null,
    val files: List<String>? = null,
    val requestFeedback: Boolean = false,
    val media: List<String>? = null,
    val videos: List<String>? = null,
    val crossSession: CrossSessionMessage? = null,
    val messageOrigin: String? = null,
    /** Widget 用 HTML / SVG。 */
    val html: String? = null,
    val clientMessageId: String? = null,
    val activityAt: Long? = null,
    val agentUsage: WorkflowAgentUsage? = null,
) {
    val isPendingAsk get() = (kind == "approval" || kind == "question") && status == "pending"
}

@Serializable
data class WorkflowAgentUsage(
    val contextTokens: Long? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val requestCount: Int = 0,
    val partial: Boolean = false,
)

@Serializable
internal data class WorkflowAgentTimeline(
    val entries: List<Entry> = emptyList(),
    val before: String? = null,
    val active: Boolean? = null,
)

/** 本会話を止めず、履歴に残さない /btw。 */
@Serializable
data class BtwItem(
    val id: String,
    val question: String,
    /** pending / done / error */
    val status: String = "pending",
    val response: String? = null,
    val error: String? = null,
    /** 回答不能を示す代替応答。 */
    val synthetic: Boolean = false,
    val at: Long = 0,
    val doneAt: Long? = null,
    val responseRevision: Long = 0,
)

@Serializable
data class ModeInfo(val id: String, val label: String, val description: String = "")

@Serializable
data class ProviderMeta(val available: Boolean = false, val modes: List<ModeInfo> = emptyList())

@Serializable
data class Meta(
    val name: String = "",
    val version: String = "",
    val startedAt: Long? = null,
    val instanceId: String? = null,
    val host: String = "",
    val home: String = "",
    val publicUrl: String = "",
    val providers: Map<String, ProviderMeta> = emptyMap(),
    val usdJpy: Double? = null,
    val usdRates: Map<String, Double>? = null,
    /** 「PC の設定どおり」の実効値。 */
    val defaults: Map<String, ProviderDefaults> = emptyMap(),
)

@Serializable
data class ProviderDefaults(val model: String? = null, val effort: String? = null, val mode: String? = null)


@Serializable
data class McpServerInfo(val name: String, val target: String = "", val status: String = "", val ok: Boolean = false, val disabled: Boolean = false, val connector: Boolean = false)

@Serializable
data class McpDetail(val name: String, val scope: String? = null, val text: String = "")

@Serializable
data class PluginInfo(val id: String, val version: String = "", val scope: String = "user", val enabled: Boolean = true, val description: String = "")

@Serializable
data class MarketplaceInfo(val name: String, val source: String = "")

@Serializable
data class PluginsOverview(val installed: List<PluginInfo> = emptyList(), val marketplaces: List<MarketplaceInfo> = emptyList())

@Serializable
data class PluginHit(val id: String, val name: String = "", val description: String = "", val marketplace: String = "", val installed: Boolean = false)

@Serializable
data class ConfirmCommand(val command: String, val sha256: String)

@Serializable
data class InstallResult(val ok: Boolean = false, val needsConfirm: ConfirmCommand? = null, val message: String = "")

@Serializable
data class SkillInfo(val name: String, val description: String = "", val source: String = "", val path: String, val editable: Boolean = false)

/** enabled が null なら切り替え不可。 */
@Serializable
data class AgentSkill(val name: String, val description: String = "", val source: String = "", val path: String, val editable: Boolean = false, val enabled: Boolean? = null)

@Serializable
data class AgentPlugin(val id: String, val name: String = "", val description: String = "", val version: String = "", val enabled: Boolean = true, val source: String = "", val canToggle: Boolean = false)

@Serializable
data class SettingOption(val id: String, val label: String)

@Serializable
data class SettingField(val key: String, val label: String, val description: String = "", val type: String = "text", val value: String? = null, val options: List<SettingOption> = emptyList())

@Serializable
data class SettingFile(val id: String, val label: String, val path: String = "", val description: String = "", val exists: Boolean = false)

@Serializable
data class AgentSettings(val fields: List<SettingField> = emptyList(), val files: List<SettingFile> = emptyList())

@Serializable
data class SettingFileText(val path: String = "", val text: String = "", val masked: Boolean = false)

@Serializable
data class SkillText(val path: String, val text: String = "", val editable: Boolean = false)

@Serializable
data class PlanItem(val content: String, val status: String = "pending", val activeForm: String? = null)

@Serializable
data class SessionPlan(val items: List<PlanItem> = emptyList(), val updatedAt: Long = 0)

@Serializable
data class SessionStats(
    val costUsd: Double? = null,
    val partial: Boolean = false,
    val bytes: Long = 0,
    val cache: CacheSummary? = null,
    val tokens: TokenTotals? = null,
)

@Serializable
data class TokenTotals(val input: Long = 0, val cached: Long = 0, val output: Long = 0)

@Serializable
data class CacheSummary(val lastAt: Long = 0, val ttlMs: Long = 300_000, val lastHit: Double = 0.0)

@Serializable
data class CacheRequest(
    val at: Long = 0,
    val model: String = "",
    val read: Long = 0,
    val write5m: Long = 0,
    val write1h: Long = 0,
    val uncached: Long = 0,
    val output: Long = 0,
    val gapMs: Long? = null,
)

@Serializable
data class CacheTotals(val read: Long = 0, val write5m: Long = 0, val write1h: Long = 0, val uncached: Long = 0, val requests: Int = 0, val hitRate: Double = 0.0)

@Serializable
data class CacheHeatmapCell(val read: Double = 0.0, val input: Double = 0.0, val low: Int = 0) {
    val hitRate: Double? get() = (read / input).coerceIn(0.0, 1.0).takeIf { input > 0 && it.isFinite() }
}

@Serializable
data class CacheHeatmap(val requestsPerCell: Int = 1, val cells: List<CacheHeatmapCell> = emptyList())

@Serializable
data class CacheDetail(
    val summary: CacheSummary? = null,
    val totals: CacheTotals = CacheTotals(),
    val savedUsd: Double? = null,
    val savedKnown: Boolean? = null,
    val savedPartial: Boolean = false,
    val expiredCount: Int = 0,
    val recent: List<CacheRequest> = emptyList(),
    val heatmap: CacheHeatmap? = null,
)

@Serializable
data class RewindResult(val text: String = "", val filesChanged: List<String>? = null, val filesError: String? = null)

@Serializable
data class SessionDetail(val session: SessionInfo, val entries: List<Entry>, val total: Int = 0, val timelineRevision: Long? = null)

@Serializable
data class HistoryItem(
    val externalId: String,
    val title: String,
    val cwd: String = "",
    val updatedAt: Long = 0,
    val preview: String = "",
    val sessionId: String? = null,
    /** PC で読み込み済み・途中参加可能。 */
    val live: Boolean = false,
    val running: Boolean = false,
    @kotlinx.serialization.Transient val provider: String = "",
) {
    val listKey get() = "$provider:$externalId"
}

@Serializable
data class FsDir(val name: String, val path: String, val git: Boolean = false)

@Serializable
data class ContextUsage(val used: Long = 0, val max: Long = 0, val percent: Int = 0)

@Serializable
data class ContextDetail(
    val model: String = "",
    val totalTokens: Long = 0,
    val maxTokens: Long = 0,
    val percentage: Double = 0.0,
    val autoCompactThreshold: Long? = null,
    val isAutoCompactEnabled: Boolean = false,
    /** 会話記録のサイズから推定した内訳。 */
    val estimated: Boolean = false,
    val categories: List<ContextCategory> = emptyList(),
    val mcpServers: List<NamedTokens> = emptyList(),
    val memoryFiles: List<MemoryFileTokens> = emptyList(),
    val skills: List<NamedTokens> = emptyList(),
    val agents: List<NamedTokens> = emptyList(),
    val messages: MessageTokens? = null,
    val cachedAt: Long? = null,
)

@Serializable
data class ContextCategory(val id: String? = null, val name: String, val tokens: Long = 0, val kind: String = "used", val color: String = "")

@Serializable
data class NamedTokens(val name: String, val tokens: Long = 0, val source: String = "", val tools: Int = 0)

@Serializable
data class MemoryFileTokens(val path: String, val type: String = "", val tokens: Long = 0)

@Serializable
data class MessageTokens(
    val toolCalls: Long = 0,
    val toolResults: Long = 0,
    val attachments: Long = 0,
    val assistant: Long = 0,
    val user: Long = 0,
    val byTool: List<NamedTokens> = emptyList(),
)

@Serializable
data class UsageWindow(
    val label: String,
    val usedPercent: Int = 0,
    val resetsAt: Long? = null,
    val resetsText: String? = null,
    val labelKey: String? = null,
    val labelCount: Int? = null,
    val scope: String? = null,
    val scopeKey: String? = null,
)

@Serializable
data class Credits(val hasCredits: Boolean = false, val unlimited: Boolean = false, val balance: String? = null)

@Serializable
data class Ticket(val title: String = "", val expiresAt: Long? = null, val titleKey: String? = null, val scope: String? = null)

@Serializable
data class Tickets(val available: Int = 0, val items: List<Ticket> = emptyList())

@Serializable
data class ProviderUsage(
    val windows: List<UsageWindow> = emptyList(),
    val credits: Credits? = null,
    val tickets: Tickets? = null,
    val plan: String? = null,
    val note: String? = null,
    val error: String? = null,
    val at: Long = 0,
    val noteKey: String? = null,
)

@Serializable
data class UsageInfo(val claude: ProviderUsage? = null, val codex: ProviderUsage? = null, val antigravity: ProviderUsage? = null) {
    fun of(provider: String): ProviderUsage? = when (provider) {
        "codex" -> codex
        "antigravity" -> antigravity
        else -> claude
    }
}

@Serializable
data class ModelOption(
    val id: String,
    val label: String,
    val description: String = "",
    val efforts: List<String> = emptyList(),
    val isDefault: Boolean = false,
    /** null は対応状況不明。 */
    val supportsFastMode: Boolean? = null,
    /** PC が別名を解決したモデル ID。 */
    val resolvedModel: String? = null,
)

@Serializable
data class CommandInfo(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val kind: String = "slash",
)

@Serializable
data class FavoriteDir(val path: String, val name: String, val git: Boolean = false)

@Serializable
data class RecentDir(
    val path: String,
    val name: String,
    val lastUsed: Long = 0,
    val count: Int = 0,
    val providers: List<String> = emptyList(),
    val git: Boolean = false,
)

@Serializable
data class DirsInfo(
    val favorites: List<FavoriteDir> = emptyList(),
    val recent: List<RecentDir> = emptyList(),
    val roots: List<FsDir> = emptyList(),
    val home: String = "",
)

object ThinkingModes {
    const val FULL = "full"
    const val SUMMARY = "summary"
    const val OFF = "off"
    val all = listOf(FULL, SUMMARY, OFF)
    fun label(m: String) = when (m) {
        SUMMARY -> appString(R.string.core_thinking_summary)
        OFF -> appString(R.string.core_thinking_off)
        else -> appString(R.string.core_thinking_full)
    }
    fun description(m: String) = when (m) {
        SUMMARY -> appString(R.string.core_thinking_summary_description)
        OFF -> appString(R.string.core_thinking_off_description)
        else -> appString(R.string.core_thinking_full_description)
    }
}

@Serializable
data class LinkFileInfo(
    val path: String,
    val name: String,
    val exists: Boolean = false,
    /** image / video / text / binary / dir / missing */
    val kind: String = "missing",
    val size: Long = 0,
    val mtime: Double = 0.0,
    val mime: String = "application/octet-stream",
    val line: Int? = null,
    val mediaId: String? = null,
    val text: String? = null,
    val truncated: Boolean = false,
)

data class WebPreview(val title: String?, val description: String?, val site: String?, val finalUrl: String)

@Serializable
data class FsListing(val path: String, val parent: String? = null, val dirs: List<FsDir> = emptyList())

@Serializable
data class ImagePayload(val mime: String, val data: String)

@Serializable
data class ServerConfig(
    val url: String,
    val token: String,
    val name: String,
    /** 未使用なら空。 */
    val accessId: String = "",
    val accessSecret: String = "",
    /** 主接続に失敗した場合の公開 URL。 */
    val publicUrl: String = "",
) {
    val hasAccess get() = accessId.isNotBlank() && accessSecret.isNotBlank()

    val candidates: List<String> get() = listOf(url, publicUrl).filter { it.isNotBlank() }.map { it.trimEnd('/') }.distinct()
}

fun isPublicUrl(url: String) = url.startsWith("https://")

/** 非 localhost の HTTP を拒否。network_security_config.xml と一致させる。 */
fun cleartextBlocked(url: String): Boolean {
    val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
    if (!u.scheme.equals("http", ignoreCase = true)) return false
    val host = u.host?.lowercase() ?: return false
    return host != "127.0.0.1" && host != "localhost"
}

val CLEARTEXT_BLOCKED_MESSAGE get() = appString(R.string.core_cleartext_blocked)

fun routeLabel(url: String?): String = when {
    url == null -> ""
    url.contains("127.0.0.1") || url.contains("localhost") -> "USB"
    url.contains("trycloudflare.com") -> appString(R.string.core_route_internet)
    url.startsWith("https://") -> appString(R.string.core_route_internet)
    else -> "LAN"
}

object Providers {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    const val ANTIGRAVITY = "antigravity"
    val all = listOf(CLAUDE, CODEX, ANTIGRAVITY)
    fun label(p: String) = when (p) {
        CLAUDE -> "Claude"
        CODEX -> "Codex"
        ANTIGRAVITY -> "Antigravity"
        else -> p
    }
}

@Serializable
data class TerminalInfo(
    val id: String,
    val title: String = appString(R.string.core_terminal),
    val cwd: String = "",
    val shell: String = "",
    val alive: Boolean = true,
    val exitCode: Int? = null,
    val createdAt: Long = 0,
    val lastOutputAt: Long = 0,
    val lastLine: String = "",
    /** own / external */
    val kind: String = "own",
    val source: String = "Remote Code",
    val running: String = "",
    /** このターミナル内でサーバーが動作中。 */
    val self: Boolean = false,
    val cols: Int = 0,
    val rows: Int = 0,
)
