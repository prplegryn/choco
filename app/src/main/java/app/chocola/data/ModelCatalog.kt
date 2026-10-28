package app.chocola.data

import org.json.JSONObject

data class ModelProfile(
    val model: String, val family: String, val name: String, val purpose: String, val note: String,
    val url: String, val protocol: Protocol, val endpoint: String, val context: Int, val output: Int,
    val effort: String = "", val thinking: String = "default", val efforts: List<String> = emptyList(),
    val canDisableThinking: Boolean = true, val vision: Boolean = false, val maxOutput: Int = 131072
) {
    fun apply(p: Provider, replaceEndpoint: Boolean = false): Provider = p.copy(
        model = model, family = family, protocol = protocol, baseUrl = if (replaceEndpoint) endpoint else p.baseUrl,
        maxTokens = output, contextWindow = context, compactPercent = 70, recentTurns = 3, summaryTokens = 16384,
        temperature = null, topP = null, reasoningEffort = effort, thinking = thinking,
        thinkingBudget = 0, tokenParameter = if (family == "openai") "max_completion_tokens" else "max_tokens")
}

/** Vendor facts are dated; workload defaults are Chocola's editable starting points. */
object ModelCatalog {
    const val checked = "2026-10-24"
    val families = linkedMapOf("deepseek" to "DeepSeek", "openai" to "OpenAI", "anthropic" to "Claude", "gemini" to "Gemini",
        "qwen" to "通义千问", "glm" to "智谱", "minimax" to "MiniMax", "kimi" to "Kimi", "custom" to "自定义")
    val recommendations = linkedMapOf(
        "日常交流" to setOf("deepseek-flash", "qwen3.8-flash", "gpt-6-luna"),
        "编码与执行" to setOf("gpt-6.1-sol", "claude-sonnet-5-5", "deepseek-v4-pro", "glm-5.3", "MiniMax-M3.1-Flash"),
        "复杂分析" to setOf("deepseek-v4-pro", "gpt-6-astra", "claude-opus-5-5", "qwen3.8-max"),
        "长资料与图片" to setOf("gemini-4-argon", "kimi-k3", "qwen3.8-max"),
        "轻量任务" to setOf("gpt-6-luna", "gemini-3.8-flash", "qwen3.8-flash", "deepseek-flash"))
    private const val ds = "https://api-docs.deepseek.com/quick_start/pricing/"
    private const val oa = "https://developers.openai.com/api/docs/guides/latest-model"
    private const val cl = "https://platform.claude.com/docs/en/models/overview"
    private const val ge = "https://ai.google.dev/gemini-api/docs/models"
    private const val qw = "https://help.aliyun.com/en/model-studio/models"
    val models = listOf(
        ModelProfile("deepseek-flash", "deepseek", "DeepSeek Flash", "日常与高频任务", "思考可开关；工具轮次保留推理内容；适合中文交流和连续执行。", ds, Protocol.CHAT, "https://api.deepseek.com", 1_000_000, 32768, "high", "enabled", listOf("low", "high", "max"), vision = true, maxOutput = 393216),
        ModelProfile("deepseek-v4-pro", "deepseek", "DeepSeek V4 Pro", "复杂分析与长任务", "偏重复杂推理；思考模式不发送无效的随机采样参数。", ds, Protocol.CHAT, "https://api.deepseek.com", 1_000_000, 49152, "high", "enabled", listOf("low", "high", "max"), maxOutput = 393216),
        ModelProfile("gpt-6.1-sol", "openai", "GPT-6.1 Sol", "编码与任务均衡", "工具调用采用 Responses；默认中等推理，不发送随机采样参数。", oa, Protocol.RESPONSES, "https://api.openai.com/v1", 1_050_000, 32768, "medium", efforts = listOf("low", "medium", "high", "xhigh", "max"), canDisableThinking = false, vision = true),
        ModelProfile("gpt-6-astra", "openai", "GPT-6 Astra", "高难度推理与编码", "适合复杂交付；优先质量，费用与等待时间通常更高。", oa, Protocol.RESPONSES, "https://api.openai.com/v1", 1_050_000, 32768, "medium", efforts = listOf("low", "medium", "high", "xhigh", "max"), canDisableThinking = false, vision = true),
        ModelProfile("gpt-6-luna", "openai", "GPT-6 Luna", "轻量与低成本任务", "适合明确的小任务和高频自动化；工具调用使用 Responses。", oa, Protocol.RESPONSES, "https://api.openai.com/v1", 1_050_000, 16384, "low", efforts = listOf("none", "low", "medium", "high", "xhigh", "max"), vision = true),
        ModelProfile("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", "编码与日常工作", "自适应思考，保留工具签名；无需前置思考时可选择仅工具间思考。", cl, Protocol.ANTHROPIC, "https://api.anthropic.com/v1", 1_000_000, 32768, "high", "adaptive", listOf("low", "medium", "high"), canDisableThinking = false, vision = true),
        ModelProfile("claude-opus-5-5", "anthropic", "Claude Opus 5.5", "复杂编码与长期任务", "自适应思考始终开启；适合需要较强规划与校验的工作。", cl, Protocol.ANTHROPIC, "https://api.anthropic.com/v1", 1_000_000, 32768, "medium", "adaptive", listOf("low", "medium", "high"), canDisableThinking = false, vision = true),
        ModelProfile("gemini-4-argon", "gemini", "Gemini 4 Argon", "超长资料与多模态", "使用官方兼容接口；工具调用保留 thought signature。", ge, Protocol.CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", 1_048_576, 32768, "medium", efforts = listOf("low", "medium", "high"), canDisableThinking = false, vision = true, maxOutput = 65536),
        ModelProfile("gemini-3.8-flash", "gemini", "Gemini 3.8 Flash", "快速多模态交流", "偏重响应速度；保留官方默认温度，支持图片输入。", ge, Protocol.CHAT, "https://generativelanguage.googleapis.com/v1beta/openai", 1_000_000, 16384, vision = true),
        ModelProfile("qwen3.8-max", "qwen", "通义千问 3.8 Max", "中文与复杂综合任务", "思考可开关，工具轮次保留 reasoning_content；可设置思考预算。", qw, Protocol.CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1", 1_000_000, 32768, thinking = "enabled", vision = true),
        ModelProfile("qwen3.8-flash", "qwen", "通义千问 3.8 Flash", "中文与高频交流", "长上下文与多模态；日常场景可关闭思考以减少等待。", qw, Protocol.CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1", 1_000_000, 16384, thinking = "enabled", vision = true),
        ModelProfile("glm-5.3", "glm", "GLM 5.3（Z.ai）", "复杂软件工程", "仅支持思考模式；使用较轻的高档推理起点，避免默认最大档的等待。", "https://docs.z.ai/guides/llm/glm-5.3", Protocol.CHAT, "https://api.z.ai/api/paas/v4", 1_000_000, 32768, "high", "enabled", listOf("low", "high", "max"), false),
        ModelProfile("MiniMax-M3.1-Flash", "minimax", "MiniMax M3.1 Flash", "连续编码与工具执行", "优先官方推荐的 Anthropic 兼容接口；推理深度可独立调节。", "https://platform.minimax.io/docs/guides/text-generation", Protocol.ANTHROPIC, "https://api.minimax.io/anthropic/v1", 1_000_000, 32768, "medium", efforts = listOf("low", "medium", "high", "xhigh", "max"), canDisableThinking = false, vision = true),
        ModelProfile("kimi-k3", "kimi", "Kimi K3（百炼）", "长任务与中文工作", "此预设对应百炼部署版本；保留供应商推理与采样默认值，可改用业务空间专属地址。", "https://help.aliyun.com/zh/model-studio/kimi-k3", Protocol.CHAT, "https://dashscope.aliyuncs.com/compatible-mode/v1", 1_048_576, 32768, canDisableThinking = false, vision = true)
    )
    fun profile(p: Provider): ModelProfile? = models.firstOrNull { it.model.equals(p.model, true) }
    fun family(p: Provider): String {
        if (p.family != "auto") return p.family
        val m = p.model.lowercase()
        return when {
            "deepseek" in m -> "deepseek"; m.startsWith("gpt-") || Regex("^o[1-9]").containsMatchIn(m) -> "openai"
            "claude" in m -> "anthropic"; "gemini" in m -> "gemini"; "qwen" in m -> "qwen"
            "glm" in m -> "glm"; "minimax" in m -> "minimax"; "kimi" in m -> "kimi"; else -> "custom"
        }
    }
    fun auxiliary(p: Provider, title: Boolean = false): Provider {
        val f = family(p)
        val canOff = profile(p)?.canDisableThinking != false
        return p.copy(maxTokens = if (title) 4096 else p.summaryTokens.coerceIn(4096, 65536), temperature = null, topP = null,
            reasoningEffort = when { CodexWire.isCodex(p) && p.reasoningLevels.isNotEmpty() -> p.reasoningLevels.first(); profile(p)?.efforts?.contains("low") == true -> "low"; f == "openai" && (p.model.startsWith("gpt-5") || p.model.startsWith("gpt-6") || Regex("^o[1-9]").containsMatchIn(p.model)) -> "low"; else -> "" },
            thinking = if (p.model == "claude-sonnet-5-5") "between_tools" else if (canOff && f in setOf("deepseek", "qwen") && (profile(p) != null || p.model.startsWith("qwen3."))) "disabled" else "default", thinkingBudget = 0)
    }
    fun applyWorkload(p: Provider, task: String): Provider {
        val f = family(p)
        val profile = profile(p)
        val supported = efforts(p)
        val off = task == "daily" && profile?.canDisableThinking != false && f in setOf("deepseek", "qwen")
        val level = when (task) { "daily" -> "low"; "deep" -> supported.lastOrNull().orEmpty(); else -> if (f in setOf("deepseek", "glm")) "high" else "medium" }
        return p.copy(maxTokens = when (task) { "daily" -> if (profile?.canDisableThinking == false) 16384 else 8192; "deep" -> 65536; else -> 32768 }.coerceAtMost(profile?.maxOutput ?: 65536),
            reasoningEffort = level.takeIf { it in supported }.orEmpty(),
            thinking = if (off) "disabled" else if (f in setOf("deepseek", "qwen", "glm")) "enabled" else "default", temperature = null, topP = null)
    }
    fun supportsTemperature(p: Provider): Boolean = !CodexWire.isCodex(p) && when (family(p)) {
        "openai" -> !(p.model.startsWith("gpt-5") || p.model.startsWith("gpt-6") || Regex("^o[1-9]").containsMatchIn(p.model))
        "deepseek" -> p.thinking == "disabled"
        "anthropic" -> !Regex("claude-.*5-[15]").containsMatchIn(p.model)
        else -> true
    }
    fun supportsTopP(p: Provider): Boolean = !CodexWire.isCodex(p) && when (family(p)) {
        "deepseek" -> if (p.model == "deepseek-flash") p.thinking != "disabled" else p.thinking == "disabled"
        else -> supportsTemperature(p)
    }
    fun topPRange(p: Provider): ClosedFloatingPointRange<Double> = if (family(p) == "deepseek" && p.model == "deepseek-flash" && p.thinking != "disabled") .95..1.0 else .01..1.0
    fun thinkingOptions(p: Provider): Map<String, String> = when {
        p.model == "claude-sonnet-5-5" -> linkedMapOf("模型默认" to "default", "自适应" to "adaptive", "仅工具间思考" to "between_tools")
        p.model == "claude-opus-5-5" -> linkedMapOf("模型默认" to "default", "自适应" to "adaptive")
        profile(p)?.canDisableThinking == false || family(p) == "minimax" -> linkedMapOf("模型默认" to "default")
        family(p) in setOf("deepseek", "qwen", "anthropic") -> linkedMapOf("模型默认" to "default", "开启" to "enabled", "关闭" to "disabled")
        else -> linkedMapOf("模型默认" to "default")
    }
    fun efforts(p: Provider): List<String> = p.reasoningLevels.takeIf { it.isNotEmpty() } ?: profile(p)?.efforts?.takeIf { it.isNotEmpty() } ?: when {
        family(p) == "openai" && (p.model.startsWith("gpt-5") || p.model.startsWith("gpt-6") || Regex("^o[1-9]").containsMatchIn(p.model)) -> listOf("low", "medium", "high")
        family(p) == "gemini" && Regex("gemini-[34]").containsMatchIn(p.model) -> listOf("low", "medium", "high")
        else -> emptyList()
    }
    fun parameters(p: Provider): JSONObject = JSONObject().apply {
        val f = family(p)
        val thinkingOn = p.thinking != "disabled"
        if (supportsTemperature(p)) p.temperature?.let { put("temperature", it) }
        if (supportsTopP(p)) p.topP?.let { require(it in topPRange(p)) { "核采样超出此模型的有效范围" }; put("top_p", it) }
        if (profile(p) != null && f in setOf("anthropic", "minimax", "glm")) require(p.thinking in thinkingOptions(p).values || (f == "glm" && p.thinking == "enabled")) { "此模型不支持所选思考模式" }
        when (f) {
            "openai" -> if (p.reasoningEffort.isNotBlank()) {
                if (p.protocol == Protocol.RESPONSES) put("reasoning", JSONObject().put("effort", p.reasoningEffort)) else put("reasoning_effort", p.reasoningEffort)
            }
            "deepseek", "glm" -> {
                if (p.protocol == Protocol.RESPONSES) {
                    val effort = if (p.thinking == "disabled") "none" else p.reasoningEffort
                    if (effort.isNotBlank()) put("reasoning", JSONObject().put("effort", effort))
                } else {
                    if (p.thinking != "default") put("thinking", JSONObject().put("type", p.thinking))
                    if (p.reasoningEffort.isNotBlank() && thinkingOn) put(if (p.protocol == Protocol.ANTHROPIC) "output_config" else "reasoning_effort",
                        if (p.protocol == Protocol.ANTHROPIC) JSONObject().put("effort", p.reasoningEffort) else p.reasoningEffort)
                }
            }
            "qwen" -> {
                if (p.thinking != "default") put("enable_thinking", thinkingOn)
                if (p.thinkingBudget > 0 && thinkingOn) put("thinking_budget", p.thinkingBudget)
            }
            "anthropic", "minimax" -> {
                if (p.thinking == "adaptive") put("thinking", JSONObject().put("type", "adaptive"))
                if (p.thinking == "between_tools") put("thinking", JSONObject().put("type", "between_tools"))
                if (p.thinking == "enabled") {
                    val budget = p.thinkingBudget.takeIf { it > 0 } ?: minOf(4096, (p.maxTokens - 1024).coerceAtLeast(1024))
                    require(budget >= 1024 && budget < p.maxTokens) { "此模型的思考预算须至少为 1024，且小于输出预算" }
                    put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
                }
                if (p.thinking == "disabled") put("thinking", JSONObject().put("type", "disabled"))
                if (p.reasoningEffort.isNotBlank()) {
                    when (p.protocol) {
                        Protocol.ANTHROPIC -> put("output_config", JSONObject().put("effort", p.reasoningEffort))
                        Protocol.CHAT -> put("reasoning_effort", p.reasoningEffort)
                        Protocol.RESPONSES -> put("reasoning", JSONObject().put("effort", p.reasoningEffort))
                    }
                }
            }
            "gemini" -> if (p.reasoningEffort.isNotBlank()) put("reasoning_effort", p.reasoningEffort)
        }
        val extra = JSONObject(p.extraBody.ifBlank { "{}" })
        val reserved = setOf("messages", "input", "tools", "model", "stream", "instructions", "system", "store")
        extra.keys().forEach { key -> require(key !in reserved) { "附加参数不能覆盖 $key" }; put(key, extra.get(key)) }
    }
}
