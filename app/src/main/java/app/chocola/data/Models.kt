package app.chocola.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun jsonArray(values: Iterable<Any>): JSONArray = JSONArray().also { a -> values.forEach { a.put(it) } }

enum class Protocol(val label: String, val endpoint: String) {
    CHAT("OpenAI 兼容", "https://api.openai.com/v1"),
    RESPONSES("OpenAI Responses", "https://api.openai.com/v1"),
    ANTHROPIC("Anthropic / Claude", "https://api.anthropic.com/v1")
}
enum class Access(val label: String) {
    CHAT("仅聊天"), ASK("手动确认"), WORKSPACE("自动编辑"), PLAN("规划模式"), FULL("跳过确认")
}
data class Provider(
    val id: String = newId(), val name: String = "", val protocol: Protocol = Protocol.CHAT,
    val baseUrl: String = Protocol.CHAT.endpoint, val apiKey: String = "", val model: String = "",
    val headers: Map<String, String> = emptyMap(), val maxTokens: Int = 16384,
    val temperature: Double? = null, val contextChars: Int = 80000, val timeoutSeconds: Int = 180,
    val tokenParameter: String = "max_tokens", val family: String = "auto", val reasoningEffort: String = "",
    val thinking: String = "default", val thinkingBudget: Int = 0, val topP: Double? = null,
    val contextWindow: Int = 0, val compactPercent: Int = 70, val recentTurns: Int = 3,
    val summaryTokens: Int = 16384, val autoCompact: Boolean = true, val extraBody: String = "{}", val vendorId: String = "", val image: ImageSettings? = null, val reasoningLevels: List<String> = emptyList()
) {
    val label: String get() = model.ifBlank { "选择模型" }
    fun json() = JSONObject().put("id", id).put("name", name).put("protocol", protocol.name)
        .put("baseUrl", baseUrl).put("apiKey", apiKey).put("model", model).put("headers", JSONObject(headers))
        .put("maxTokens", maxTokens).put("temperature", temperature ?: JSONObject.NULL)
        .put("contextChars", contextChars).put("timeoutSeconds", timeoutSeconds).put("tokenParameter", tokenParameter)
        .put("family", family).put("reasoningEffort", reasoningEffort).put("thinking", thinking).put("thinkingBudget", thinkingBudget)
        .put("topP", topP ?: JSONObject.NULL).put("contextWindow", contextWindow).put("compactPercent", compactPercent)
        .put("recentTurns", recentTurns).put("summaryTokens", summaryTokens).put("autoCompact", autoCompact).put("extraBody", extraBody).put("vendorId", vendorId).put("image", image?.json() ?: JSONObject.NULL).put("reasoningLevels", JSONArray(reasoningLevels))
    companion object {
        fun from(j: JSONObject) = Provider(j.getString("id"), j.optString("name"), Protocol.valueOf(j.optString("protocol", "CHAT")),
            j.getString("baseUrl"), j.optString("apiKey"), j.optString("model"),
            j.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap(),
            j.optInt("maxTokens", 16384), if (j.isNull("temperature")) null else j.optDouble("temperature"),
            j.optInt("contextChars", 80000), j.optInt("timeoutSeconds", 180), j.optString("tokenParameter", "max_tokens"),
            j.optString("family", "auto"), j.optString("reasoningEffort"), j.optString("thinking", "default"), j.optInt("thinkingBudget"),
            if (j.isNull("topP")) null else j.optDouble("topP"), j.optInt("contextWindow"), j.optInt("compactPercent", 70),
            j.optInt("recentTurns", 3), j.optInt("summaryTokens", 16384), j.optBoolean("autoCompact", true), j.optString("extraBody", "{}"), j.optString("vendorId"), j.optJSONObject("image")?.let(ImageSettings::from), j.optJSONArray("reasoningLevels")?.strings().orEmpty())
    }
}
@Immutable data class Attachment(val name: String, val path: String, val mime: String, val size: Long, val width: Int = 0, val height: Int = 0, val output: ImageOutput? = null,
    val imageLabel: String = "", val draftId: String = "", val originalPath: String = "", val originalMime: String = "", val revision: Long = 0) {
    fun json() = JSONObject().put("name", name).put("path", path).put("mime", mime).put("size", size).put("width", width).put("height", height).put("output", output?.json() ?: JSONObject.NULL)
        .put("imageLabel", imageLabel).put("draftId", draftId).put("originalPath", originalPath).put("originalMime", originalMime).put("revision", revision)
    companion object { fun from(j: JSONObject) = Attachment(j.getString("name"), j.getString("path"), j.optString("mime"), j.optLong("size"), j.optInt("width"), j.optInt("height"), j.optJSONObject("output")?.let(ImageOutput::from),
        j.optString("imageLabel"), j.optString("draftId"), j.optString("originalPath"), j.optString("originalMime"), j.optLong("revision")) }
}
@Immutable data class ToolCall(val id: String, val name: String, val arguments: String,
    val displayName: String = "", val remoteName: String = "", val descriptionKey: String = "") {
    fun purpose(): String = runCatching { JSONObject(arguments).optString(descriptionKey.ifBlank { "description" }) }.getOrDefault("")
    fun executionArguments(): JSONObject = JSONObject(arguments).also { if (descriptionKey.isNotBlank()) it.remove(descriptionKey) }
    fun json() = JSONObject().put("id", id).put("name", name).put("arguments", arguments)
        .put("displayName", displayName).put("remoteName", remoteName).put("descriptionKey", descriptionKey)
    companion object { fun from(j: JSONObject) = ToolCall(j.getString("id"), j.getString("name"), j.optString("arguments", "{}"), j.optString("displayName"), j.optString("remoteName"), j.optString("descriptionKey")) }
}
@Immutable data class Message(
    val id: String = newId(), val sessionId: String, val role: String, val text: String = "",
    val created: Long = System.currentTimeMillis(), val status: String = "done",
    val calls: List<ToolCall> = emptyList(), val toolCallId: String = "", val toolName: String = "",
    val attachments: List<Attachment> = emptyList(), val rawOutput: String = "", val error: String = "",
    val inputTokens: Int = 0, val outputTokens: Int = 0, val model: String = "", val hidden: Boolean = false,
    val imageBatch: ImageBatch? = null
) {
    fun json() = JSONObject().put("id", id).put("sessionId", sessionId).put("role", role).put("text", text)
        .put("created", created).put("status", status).put("calls", jsonArray(calls.map { it.json() }))
        .put("toolCallId", toolCallId).put("toolName", toolName).put("attachments", jsonArray(attachments.map { it.json() }))
        .put("rawOutput", rawOutput).put("error", error).put("inputTokens", inputTokens).put("outputTokens", outputTokens).put("model", model).put("hidden", hidden)
        .put("imageBatch", imageBatch?.json() ?: JSONObject.NULL)
    companion object {
        fun from(j: JSONObject) = Message(j.getString("id"), j.getString("sessionId"), j.getString("role"), j.optString("text"),
            j.optLong("created"), j.optString("status", "done"), j.optJSONArray("calls")?.objects()?.map(ToolCall::from) ?: emptyList(),
            j.optString("toolCallId"), j.optString("toolName"), j.optJSONArray("attachments")?.objects()?.map(Attachment::from) ?: emptyList(),
            j.optString("rawOutput"), j.optString("error"), j.optInt("inputTokens"), j.optInt("outputTokens"), j.optString("model"), j.optBoolean("hidden"),
            j.optJSONObject("imageBatch")?.let(ImageBatch::from))
    }
}
data class Session(
    val id: String = newId(), val title: String = "新会话", val providerId: String = "", val access: Access = Access.ASK,
    val updated: Long = System.currentTimeMillis(), val pinned: Boolean = false, val draft: String = "",
    val summary: String = "", val summaryThrough: String = "", val plan: String = "",
    val summaryUserId: String = "", val summaryAt: Long = 0, val titleMode: String = "auto", val titledTurns: Int = 0, val branchOf: String = "", val userTurns: Int = 0,
    val reasoningLevel: String = "", val reasoningModel: String = "", val outputLimit: Int = 0, val outputModel: String = "",
    val reasoningSelections: Map<String, String> = emptyMap(), val titleLocked: Boolean = false, val scheduledTaskId: String = "",
    val taskTools: Set<String> = emptySet(), val taskCredentials: Set<String> = emptySet()
) {
    fun json() = JSONObject().put("id", id).put("title", title).put("providerId", providerId).put("access", access.name)
        .put("updated", updated).put("pinned", pinned).put("draft", draft).put("summary", summary)
        .put("summaryThrough", summaryThrough).put("plan", plan).put("summaryUserId", summaryUserId).put("summaryAt", summaryAt)
        .put("titleMode", titleMode).put("titledTurns", titledTurns).put("branchOf", branchOf).put("userTurns", userTurns).put("reasoningLevel", reasoningLevel).put("reasoningModel", reasoningModel).put("outputLimit", outputLimit).put("outputModel", outputModel)
        .put("reasoningSelections", JSONObject(reasoningSelections)).put("titleLocked", titleLocked).put("scheduledTaskId", scheduledTaskId)
        .put("taskTools", jsonArray(taskTools.sorted())).put("taskCredentials", jsonArray(taskCredentials.sorted()))
    companion object { fun from(j: JSONObject) = Session(j.getString("id"), j.optString("title", "新会话"), j.optString("providerId"),
        Access.valueOf(j.optString("access", "ASK")), j.optLong("updated"), j.optBoolean("pinned"), j.optString("draft"),
        j.optString("summary"), j.optString("summaryThrough"), j.optString("plan"), j.optString("summaryUserId"), j.optLong("summaryAt"),
        j.optString("titleMode", if (j.optString("title", "新会话") == "新会话") "auto" else "legacy"), j.optInt("titledTurns"), j.optString("branchOf"), j.optInt("userTurns"), j.optString("reasoningLevel"), j.optString("reasoningModel"), j.optInt("outputLimit"), j.optString("outputModel"),
        j.optJSONObject("reasoningSelections")?.stringMap().orEmpty(), j.optBoolean("titleLocked", j.optInt("titledTurns") > 0), j.optString("scheduledTaskId"),
        j.optJSONArray("taskTools")?.strings()?.toSet().orEmpty(), j.optJSONArray("taskCredentials")?.strings()?.toSet().orEmpty()) }
}
internal fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
data class MemoryField(val name: String, val value: String, val secret: Boolean = false) {
    fun json() = JSONObject().put("name", name).put("value", value).put("secret", secret)
    companion object { fun from(j: JSONObject) = MemoryField(j.getString("name"), j.optString("value"), j.optBoolean("secret")) }
}
data class Memory(
    val id: String = newId(), val name: String = "", val kind: String = "资料", val fields: List<MemoryField> = emptyList(),
    val scope: String = "", val enabled: Boolean = true, val toolsAllowed: Boolean = false, val domain: String = ""
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("kind", kind).put("fields", jsonArray(fields.map { it.json() }))
        .put("scope", scope).put("enabled", enabled).put("toolsAllowed", toolsAllowed).put("domain", domain)
    companion object { fun from(j: JSONObject) = Memory(j.getString("id"), j.optString("name"), j.optString("kind", "资料"),
        j.optJSONArray("fields")?.objects()?.map(MemoryField::from) ?: emptyList(), j.optString("scope"),
        j.optBoolean("enabled", true), j.optBoolean("toolsAllowed"), j.optString("domain")) }
}
object StatusEffects {
    private val supported = setOf("sheen", "ripple", "breath", "mist", "tide", "silk", "glass", "scan", "halo", "aurora", "prism", "ember", "mercury", "interference", "cosmos", "still")
    fun normalize(value: String) = value.takeIf { it in supported } ?: "sheen"
}
object FontSizes {
    const val Default = .92f
    fun restore(value: Float, version: Int): Float = when {
        !value.isFinite() || value <= 0f -> Default
        version < 2 && value == 1f -> Default
        else -> value.coerceIn(.8f, 1.4f)
    }
}
data class Preferences(val style: String = "自然", val instructions: String = "", val autoMemory: Boolean = true,
    val defaultAccess: Access = Access.ASK, val maxSteps: Int = 100, val fontScale: Float = FontSizes.Default,
    val statusStyle: String = "sheen", val statusSize: Int = 1, val statusSpeed: Int = 1800, val statusStrength: Float = .65f,
    val statusDuration: Int = 240, val statusAutoWidth: Boolean = true, val statusWidth: Int = 156,
    val autoTitles: Boolean = true, val toolRules: Map<String, String> = emptyMap(), val statusWidthDuration: Int = 560, val statusDesign: PillDesign? = null, val chatBackground: ChatBackground = ChatBackground(),
    val reasoningDefaults: Map<String, String> = emptyMap(), val floating: FloatingDesign = FloatingDesign(),
    val reply: ReplyPreferences = ReplyPreferences(), val setupCompleted: Boolean = false, val image: ImageSettings = ImageSettings()) {
    val pill: PillDesign get() = statusDesign ?: PillDesign.preset(statusStyle).copy(height = listOf(28f, 32f, 36f)[statusSize.coerceIn(0, 2)],
        fontSize = listOf(11f, 12f, 13f)[statusSize.coerceIn(0, 2)], corner = listOf(14f, 16f, 18f)[statusSize.coerceIn(0, 2)],
        period = statusSpeed, strength = statusStrength, appearanceDuration = statusDuration, widthDuration = statusWidthDuration, autoWidth = statusAutoWidth, fixedWidth = statusWidth.toFloat())
    fun json() = JSONObject().put("style", style).put("instructions", instructions).put("autoMemory", autoMemory)
        .put("defaultAccess", defaultAccess.name).put("maxSteps", maxSteps).put("fontScale", fontScale).put("textVersion", 2)
        .put("statusStyle", statusStyle).put("statusSize", statusSize).put("statusSpeed", statusSpeed).put("statusStrength", statusStrength)
        .put("statusDuration", statusDuration).put("statusAutoWidth", statusAutoWidth).put("statusWidth", statusWidth)
        .put("statusWidthDuration", statusWidthDuration).put("pillMotionVersion", 2)
        .put("statusDesign", statusDesign?.json() ?: JSONObject.NULL)
        .put("autoTitles", autoTitles).put("toolRules", JSONObject(toolRules)).put("chatBackground", chatBackground.json())
        .put("reasoningDefaults", JSONObject(reasoningDefaults)).put("floating", floating.json()).put("reply", reply.json()).put("setupCompleted", setupCompleted).put("image", image.json())
    companion object { fun from(j: JSONObject) = Preferences(j.optString("style", "自然"), j.optString("instructions"),
        j.optBoolean("autoMemory", true), Access.valueOf(j.optString("defaultAccess", "ASK")), 100, FontSizes.restore(j.optDouble("fontScale", FontSizes.Default.toDouble()).toFloat(), j.optInt("textVersion")),
        StatusEffects.normalize(j.optString("statusStyle", "sheen")), j.optInt("statusSize", 1), j.optInt("statusSpeed", 1800), j.optDouble("statusStrength", .65).toFloat(),
        j.optInt("statusDuration", 240), j.optBoolean("statusAutoWidth", true), j.optInt("statusWidth", 156), j.optBoolean("autoTitles", true),
        j.optJSONObject("toolRules")?.let { r -> r.keys().asSequence().associateWith { r.getString(it) } } ?: emptyMap(), j.optInt("statusWidthDuration", 560).let { if (j.optInt("pillMotionVersion") < 2 && it == 360) 560 else it }, j.optJSONObject("statusDesign")?.let(PillDesign::from), j.optJSONObject("chatBackground")?.let(ChatBackground::from) ?: ChatBackground(),
        j.optJSONObject("reasoningDefaults")?.stringMap().orEmpty(), j.optJSONObject("floating")?.let(FloatingDesign::from) ?: FloatingDesign(),
        j.optJSONObject("reply")?.let(ReplyPreferences::from) ?: ReplyPreferences.legacy(j.optString("style")), j.optBoolean("setupCompleted", true),
        j.optJSONObject("image")?.let(ImageSettings::from) ?: ImageSettings()) }
}
data class Approval(val id: String = newId(), val sessionId: String, val call: ToolCall, val detail: String)
data class RuntimeState(val status: String = "未准备", val progress: Float = 0f, val error: String = "")
data class SandboxProcess(val id: String, val sessionId: String, val name: String, val status: String = "running", val exitCode: Int? = null) {
    fun json() = JSONObject().put("id", id).put("sessionId", sessionId).put("name", name).put("status", status).put("exitCode", exitCode ?: JSONObject.NULL)
    companion object { fun from(j: JSONObject) = SandboxProcess(j.getString("id"), j.getString("sessionId"), j.optString("name"), j.optString("status"), if (j.isNull("exitCode")) null else j.getInt("exitCode")) }
}
data class TaskCheckpoint(val sessionId: String, val step: Int = 0, val stage: String = "model") {
    fun json() = JSONObject().put("sessionId", sessionId).put("step", step).put("stage", stage)
    companion object { fun from(j: JSONObject) = TaskCheckpoint(j.getString("sessionId"), j.optInt("step"), j.optString("stage", "model")) }
}
data class AppState(
    val loaded: Boolean = false, val sessions: List<Session> = emptyList(), val providers: List<Provider> = emptyList(),
    val memories: List<Memory> = emptyList(), val preferences: Preferences = Preferences(), val currentId: String = "",
    val messages: Map<String, List<Message>> = emptyMap(), val tasks: Map<String, String> = emptyMap(),
    val approvals: Map<String, Approval> = emptyMap(), val processes: List<SandboxProcess> = emptyList(),
    val notice: String? = null, val runtime: RuntimeState = RuntimeState(), val filesVersion: Int = 0,
    val historyMore: Map<String, Boolean> = emptyMap(),
    val schedules: List<ScheduledTask> = emptyList(), val scheduleAlarmReady: Boolean = true,
    val scheduleAuthorizations: List<ScheduleAuthorization> = emptyList(), val scheduleChecks: Set<String> = emptySet(),
    val skills: List<Skill> = emptyList(), val mcpServers: List<McpServer> = emptyList(), val balances: Map<String, ModelBalance> = emptyMap(),
    val transfer: String = "", val vendors: List<ApiVendor> = emptyList(), val notebooksVersion: Int = 0,
    val codex: CodexState = CodexState()
) {
    val current: Session? get() = sessions.firstOrNull { it.id == currentId }
    val currentMessages: List<Message> get() = messages[currentId].orEmpty()
    val currentProvider: Provider? get() = providers.firstOrNull { it.id == current?.providerId }?.let { ThinkingControls.effective(it, current) }
    val runningId: String? get() = if (currentId in tasks) currentId else tasks.keys.firstOrNull()
    val phase: String get() = tasks[currentId].orEmpty()
    val approval: Approval? get() = approvals[currentId] ?: approvals.values.firstOrNull()
    val activeProcessCount: Int get() = processes.count { it.status == "running" }
    val hasBackgroundWork: Boolean get() = tasks.isNotEmpty() || activeProcessCount > 0
}
