package app.chocola.ui

import app.chocola.data.*
import org.json.JSONObject

internal sealed interface ChatItem { val key: String }
internal data class UserItem(val message: Message) : ChatItem { override val key = message.id }
internal data class ImageItem(val message: Message) : ChatItem { override val key = "${message.id}:images" }
internal data class TextPiece(val message: Message, val prepared: PreparedBlock, val previous: MarkdownBlock?, val index: Int) : ChatItem { val block get() = prepared.block; override val key = "${message.id}:text:$index" }
internal data class CodePiece(val message: Message, val blockIndex: Int, val code: String, val language: String, val chunk: Int, val last: Boolean, val full: String, val columns: Int) : ChatItem { override val key = "${message.id}:code:$blockIndex:$chunk"; val scrollKey get() = "${message.id}:code:$blockIndex" }
internal data class ReplyEnd(val message: Message, val latest: Boolean) : ChatItem { override val key = "${message.id}:end" }
internal data class OperationStep(val message: Message, val call: ToolCall?) {
    val arguments: JSONObject = runCatching { JSONObject(call?.arguments ?: "{}") }.getOrDefault(JSONObject())
}
internal data class OperationGroup(val id: String, val steps: List<OperationStep>)
internal data class OperationsHeader(val group: OperationGroup, val expanded: Boolean, val bodyVisible: Boolean) : ChatItem { override val key = "ops:${group.id}" }
internal data class OperationRow(val group: String, val step: OperationStep, val last: Boolean, val expanded: Boolean, val groupExpanded: Boolean) : ChatItem { override val key = "step:${step.message.id}" }
internal data class OperationExpansion(val groups: List<String> = emptyList(), val steps: List<String> = emptyList()) {
    fun group(id: String, children: Set<String>) = copy(groups = if (id in groups) groups - id else groups + id, steps = steps.filterNot { it in children })
    fun step(id: String) = copy(steps = if (id in steps) steps - id else steps + id)
}

/** Runs off the UI thread. Stable messages keep the same parsed pieces across stream updates. */
internal class TimelineProjector {
    private val parsed = LinkedHashMap<String, Pair<Message, List<ChatItem>>>()
    private val streams = HashMap<String, StreamingMarkdown>()
    private fun displayBlocks(blocks: List<MarkdownBlock>): List<MarkdownBlock> = blocks.flatMap { block ->
        when {
            block.type in setOf(MarkdownType.TEXT, MarkdownType.QUOTE) && block.text.length > 2048 -> buildList {
                var start = 0
                val math = MathSyntax.inlineRanges(block.text, pending = true)
                while (start < block.text.length) {
                    var end = MathSyntax.chunkEnd(start, minOf(start + 2048, block.text.length), math)
                    if (end < block.text.length && block.text[end - 1].isHighSurrogate()) end--
                    add(block.copy(text = block.text.substring(start, end), meta = if (start == 0) block.meta else "continuation"))
                    start = end
                }
            }
            block.type == MarkdownType.TABLE && (block.table?.rows?.size ?: 0) > 25 -> {
                val table = requireNotNull(block.table)
                table.rows.drop(1).chunked(24).map { rows -> block.copy(table = table.copy(rows = listOf(table.rows.first()) + rows)) }
            }
            else -> listOf(block)
        }
    }
    @Synchronized fun build(messages: List<Message>, openGroups: Set<String>, openSteps: Set<String>, visibleGroups: Set<String> = openGroups): List<ChatItem> {
        val source = messages.filter { it.status != "replaced" && !it.hidden }
        val calls = source.flatMap { it.calls }.associateBy { it.id }
        val latest = source.lastOrNull()?.takeIf { it.role == "assistant" && it.calls.isEmpty() }?.id
        val out = mutableListOf<ChatItem>()
        val steps = mutableListOf<OperationStep>()
        var groupId = ""
        fun flush() {
            if (steps.isNotEmpty()) {
                val group = OperationGroup(groupId.ifBlank { steps.first().message.id }, steps.toList())
                val expanded = group.id in openGroups
                val visible = group.id in visibleGroups
                out += OperationsHeader(group, expanded, visible)
                if (visible) group.steps.forEachIndexed { index, step ->
                    out += OperationRow(group.id, step, index == group.steps.lastIndex, expanded && step.message.id in openSteps, expanded)
                }
            }
            steps.clear(); groupId = ""
        }
        fun appendText(message: Message) {
            val previous = parsed[message.id]
            val pieces = if (previous?.first === message) previous.second else {
                val blocks = displayBlocks(streams.getOrPut(message.id) { StreamingMarkdown() }.update(message.text, message.status == "streaming"))
                val previousPieces = previous?.second?.filterIsInstance<TextPiece>()?.associateBy { it.index }.orEmpty()
                buildList<ChatItem> {
                    blocks.forEachIndexed { index, candidate ->
                        val prior = previousPieces[index]
                        val block = if (prior != null && prior.block == candidate) prior.block else candidate
                        if (block.type == MarkdownType.CODE) {
                            val lines = displayLines(block.text)
                            val columns = lines.maxOfOrNull { line -> line.fold(0) { n, c -> n + if (c.code > 0x2E7F) 2 else 1 } } ?: 1
                            val chunks = lines.chunked(24)
                            chunks.forEachIndexed { chunk, part -> add(CodePiece(message, index, part.joinToString("\n"), block.meta, chunk, chunk == chunks.lastIndex, block.text, columns)) }
                        } else {
                            val prepared = if (prior != null && prior.block === block && previous?.first?.status == message.status) prior.prepared else RichDocuments.prepare(block, message.status == "streaming")
                            add(TextPiece(message, prepared, blocks.getOrNull(index - 1), index))
                        }
                    }
                }.also { parsed[message.id] = message to it }
            }
            out += pieces
        }
        for (message in source) {
            when (message.role) {
                "user" -> { flush(); out += UserItem(message) }
                "tool" -> {
                    if (groupId.isBlank()) groupId = message.id
                    steps += OperationStep(message, calls[message.toolCallId])
                    if (message.imageBatch != null || message.attachments.any { it.mime.startsWith("image/") }) { flush(); out += ImageItem(message) }
                }
                "assistant" -> {
                    // Text is a durable stage boundary, including when tool calls arrive later.
                    if (message.text.isNotBlank()) { flush(); appendText(message) }
                    if (message.imageBatch != null || message.attachments.any { it.mime.startsWith("image/") }) { flush(); out += ImageItem(message) }
                    if (message.calls.isNotEmpty()) {
                        if (groupId.isBlank()) groupId = message.id
                    } else if (message.status != "streaming") {
                        flush()
                        out += ReplyEnd(message, message.id == latest)
                    }
                }
            }
        }
        flush()
        val visibleIds = source.mapTo(HashSet()) { it.id }
        parsed.keys.retainAll(visibleIds); streams.keys.retainAll(visibleIds)
        return out
    }
}

/** Bounded visual lines preserve Unicode pairs; full copy always uses the original text. */
internal fun displayLines(text: String, limit: Int = 1024): List<String> = buildList {
    text.lineSequence().forEach { line ->
        if (line.isEmpty()) add("")
        var start = 0
        while (start < line.length) {
            var end = minOf(start + limit.coerceAtLeast(2), line.length)
            if (end < line.length && line[end - 1].isHighSurrogate()) end--
            add(line.substring(start, end)); start = end
        }
    }
}
