package app.chocola.data

import org.json.JSONObject

internal data class ThinkingLevel(val key: String, val label: String)

/** Session overrides use only the capabilities already supported by the provider codec. */
internal object ThinkingControls {
    fun modelKey(p: Provider) = "${p.id}:${p.model}"
    fun outputMaximum(p: Provider): Int = minOf(ModelCatalog.profile(p)?.maxOutput ?: 1_000_000,
        p.contextWindow.takeIf { it > 0 }?.let { (it - 1024).coerceAtLeast(128) } ?: 1_000_000)
    fun options(p: Provider): List<ThinkingLevel> {
        val efforts = ModelCatalog.efforts(p)
        val modes = ModelCatalog.thinkingOptions(p).values
        return buildList {
            add(ThinkingLevel("", "默认"))
            if ("disabled" in modes && "none" !in efforts) add(ThinkingLevel("off", "关闭"))
            efforts.forEach { add(ThinkingLevel(it, label(p, it))) }
            if (efforts.isEmpty() && "enabled" in modes) add(ThinkingLevel("on", "开启"))
        }
    }
    fun label(key: String) = when (key) {
        "none", "off" -> "关闭"; "minimal" -> "最低"; "low" -> "较低"; "medium" -> "标准"
        "high" -> "较高"; "xhigh" -> "很高"; "max" -> "最高"; "ultra" -> "极致"; "on" -> "开启"; "" -> "默认"; else -> key
    }
    fun label(p: Provider, key: String) = if (CodexWire.isCodex(p) && key.isNotBlank()) "${label(key)}（$key）" else label(key)
    fun selected(p: Provider, s: Session?): String {
        val key = modelKey(p)
        val value = s?.reasoningSelections?.get(key) ?: s?.reasoningLevel?.takeIf { s.reasoningModel == key }.orEmpty()
        return value.takeIf { options(p).any { level -> level.key == it } }.orEmpty()
    }
    fun choose(s: Session, p: Provider, level: String): Session {
        require(options(p).any { it.key == level })
        val previous = if (s.reasoningModel.isNotBlank()) mapOf(s.reasoningModel to s.reasoningLevel) else emptyMap()
        return s.copy(reasoningLevel = level, reasoningModel = modelKey(p), reasoningSelections = previous + s.reasoningSelections + (modelKey(p) to level))
    }
    fun effective(p: Provider, s: Session?): Provider {
        val budget = if (s?.outputModel == modelKey(p) && s.outputLimit in 128..outputMaximum(p)) s.outputLimit else p.maxTokens
        val base = p.copy(maxTokens = budget)
        val key = selected(p, s)
        if (key.isBlank()) return base
        val family = ModelCatalog.family(p)
        val thinking = when {
            key == "off" -> "disabled"
            key == "on" -> "enabled"
            family in setOf("deepseek", "glm", "qwen") -> "enabled"
            family == "anthropic" && ModelCatalog.profile(p)?.thinking == "adaptive" -> "adaptive"
            else -> p.thinking
        }
        val extra = runCatching { JSONObject(p.extraBody.ifBlank { "{}" }) }.getOrNull()
        // Explicit extra parameters must not silently override the value shown by the dial.
        listOf("reasoning", "reasoning_effort", "thinking", "enable_thinking", "output_config").forEach { extra?.remove(it) }
        return base.copy(reasoningEffort = if (key in setOf("off", "on")) "" else key, thinking = thinking,
            extraBody = extra?.toString() ?: p.extraBody)
    }
    fun fraction(p: Provider, s: Session?): Float {
        val effective = effective(p, s)
        if (effective.thinking == "disabled" || effective.reasoningEffort == "none") return 0f
        if (effective.thinking == "enabled" && ModelCatalog.efforts(p).isEmpty()) return 1f
        if (CodexWire.isCodex(p)) {
            val levels = ModelCatalog.efforts(p)
            val index = levels.indexOf(effective.reasoningEffort)
            return if (index >= 0) (index + 1f) / levels.size else .5f
        }
        return when (effective.reasoningEffort.ifBlank { ModelCatalog.profile(p)?.effort.orEmpty() }) {
            "minimal" -> .12f; "low" -> .25f; "medium" -> .5f; "high" -> .72f
            "xhigh" -> .86f; "max", "ultra" -> 1f; else -> .5f
        }
    }
}
