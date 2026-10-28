package app.chocola.agent

import app.chocola.data.*

data class CompactionPlan(val prefix: List<Message>, val through: String, val anchorUser: String, val chunks: List<List<Message>>)

object ContextPlanner {
    fun tokens(text: String): Int {
        var units = 0L
        text.forEach { units += if (it.code > 0x2E7F) 4 else 1 }
        return (units / 3 + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun tokens(message: Message): Int = (maxOf(tokens(if (message.attachments.isEmpty()) message.text else AttachmentInputs.text(message)).toLong() +
        message.calls.sumOf { tokens(it.arguments).toLong() + 12 }, tokens(message.rawOutput).toLong()) +
        message.attachments.filter { it.mime.startsWith("image/") }.sumOf { AttachmentInputs.imageTokens(it).toLong() } + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun window(p: Provider) = (p.contextWindow.takeIf { it > 0 } ?: ModelCatalog.profile(p)?.context ?: (p.contextChars * 2 / 3)).coerceAtLeast(8192)
    fun used(history: List<Message>, summaryAt: Long = 0, overhead: Int = 0): Int {
        val index = history.indexOfLast { it.role == "assistant" && it.inputTokens > 0 && it.created > summaryAt }
        val estimated = history.sumOf { tokens(it).toLong() } + overhead
        val measured = if (index >= 0) history[index].inputTokens.toLong() + history.drop(index).sumOf { tokens(it).toLong() } else 0L
        return maxOf(estimated, measured).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    fun requireFits(p: Provider, system: String, history: List<Message>, tools: String = "") {
        val input = history.sumOf { tokens(it).toLong() } + tokens(system) + tokens(tools) + 512
        require(input + p.maxTokens <= window(p)) {
            "本次内容与附件超出模型上下文，请减少附件、调整单次输出长度，或选择上下文更大的模型后重试"
        }
    }
    fun threshold(p: Provider): Int = minOf(window(p) * p.compactPercent.coerceIn(35, 90) / 100, window(p) - p.maxTokens - 4096).coerceAtLeast(window(p) / 3)
    fun shouldCompact(p: Provider, session: Session, history: List<Message>, overhead: Int) = p.autoCompact && used(history, session.summaryAt, overhead) >= threshold(p)

    /** Boundaries never separate an assistant's parallel tool calls from their results. */
    fun boundaries(history: List<Message>): List<Int> = buildList {
        var i = 0
        while (i < history.size) {
            val m = history[i++]
            if (m.role == "assistant" && m.calls.isNotEmpty()) while (i < history.size && history[i].role == "tool") i++
            add(i)
        }
    }
    fun plan(history: List<Message>, p: Provider): CompactionPlan? {
        if (history.size < 2) return null
        val cuts = boundaries(history)
        val suffix = IntArray(history.size + 1)
        for (i in history.indices.reversed()) suffix[i] = (suffix[i + 1].toLong() + tokens(history[i])).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val users = history.indices.filter { history[it].role == "user" }
        val tailBudget = (window(p) * .30).toInt().coerceIn(4000, 128000)
        var boundary = if (users.size > p.recentTurns.coerceIn(1, 8)) users[users.size - p.recentTurns.coerceIn(1, 8)] else 0
        if (boundary == 0 || suffix[boundary] > tailBudget) {
            boundary = cuts.dropLast(1).firstOrNull { suffix[it] <= tailBudget } ?: 0
            if (boundary == 0 && history.last().role != "user") boundary = history.size
            else if (boundary == 0) boundary = history.lastIndex
        }
        if (boundary <= 1 || boundary > history.size) return null
        val prefix = history.take(boundary)
        if (prefix.none { it.role == "assistant" }) return null
        val anchor = history.lastOrNull { it.role == "user" }?.takeIf { user -> prefix.any { it.id == user.id } }?.id.orEmpty()
        val budget = minOf(24000, window(p) / 3).coerceAtLeast(2000)
        val chunks = mutableListOf<List<Message>>()
        var start = 0
        var running = 0
        var previous = 0
        for (cut in boundaries(prefix)) {
            val amount = prefix.subList(previous, cut).sumOf(::tokens)
            if (running > 0 && running + amount > budget) {
                chunks += prefix.subList(start, previous).toList()
                start = previous
                running = 0
            }
            running += amount
            previous = cut
        }
        if (start < prefix.size) chunks += prefix.drop(start)
        return CompactionPlan(prefix, prefix.last().id, anchor, chunks)
    }
    fun render(messages: List<Message>): String = messages.joinToString("\n\n") { m ->
        buildString {
            append(m.role); if (m.toolName.isNotBlank()) append(" ${m.toolName}"); append(":\n")
            val limit = if (m.role == "tool") 10000 else 18000
            val text = if (m.attachments.isEmpty()) m.text else AttachmentInputs.text(m)
            append(if (m.attachments.isNotEmpty() || text.length <= limit) text else text.take(limit / 2) + "\n[中段省略]\n" + text.takeLast(limit / 2))
            m.calls.forEach { append("\n${it.name}: ${it.arguments.take(6000)}") }
            m.attachments.forEach { append("\n附件：${it.name}") }
        }
    }
    fun textChunks(text: String, budget: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        var units = 0
        text.forEachIndexed { index, c ->
            units += if (c.code > 0x2E7F) 4 else 1
            if (units >= budget.coerceAtLeast(256) * 3 && !c.isHighSurrogate()) {
                result += text.substring(start, index + 1)
                start = index + 1; units = 0
            }
        }
        if (start < text.length) result += text.substring(start)
        return result
    }
    fun toolContext(message: Message): Message {
        if (message.text.length <= 12000) return message
        return message.copy(text = message.text.take(5000) + "\n[中段省略；完整结果：/workspace/.chocola/outputs/${message.id}.txt]\n" + message.text.takeLast(5000))
    }
}
