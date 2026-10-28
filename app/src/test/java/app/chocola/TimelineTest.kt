package app.chocola

import app.chocola.data.*
import app.chocola.ui.*
import org.junit.Assert.*
import org.junit.Test

class TimelineTest {
    @Test fun fiftyConsecutiveOperationsBecomeOneCollapsedGroup() {
        val messages = listOf(Message(sessionId = "s", role = "user", text = "检查部署")) + (0..49).flatMap { n ->
            listOf(Message(sessionId = "s", role = "assistant", calls = listOf(ToolCall("c$n", "ssh_execute", "{\"command\":\"ls\",\"description\":\"检查目录\"}"))),
                Message(sessionId = "s", role = "tool", toolName = "ssh_execute", toolCallId = "c$n", text = "ok"))
        } + Message(sessionId = "s", role = "assistant", text = "部署检查完成")
        val timeline = TimelineProjector().build(messages, emptySet(), emptySet())
        val group = timeline.filterIsInstance<OperationsHeader>().single().group
        assertEquals(50, group.steps.size)
        assertEquals(4, timeline.size)
        assertEquals("部署检查完成", timeline.filterIsInstance<TextPiece>().single().block.text)
    }
    @Test fun expandedOperationsRemainIndependentLazyRows() {
        val a = Message(id = "group", sessionId = "s", role = "assistant", calls = listOf(ToolCall("one", "read_file", "{}")))
        val t = Message(id = "tool", sessionId = "s", role = "tool", toolCallId = "one")
        val items = TimelineProjector().build(listOf(a, t), setOf("group"), setOf("tool"))
        assertEquals(1, items.filterIsInstance<OperationRow>().size)
        assertTrue(items.filterIsInstance<OperationRow>().single().expanded)
    }
    @Test fun streamedStageTextNeverDisappearsWhenItsToolCallsArrive() {
        val p = TimelineProjector()
        val stream = Message(id = "stage", sessionId = "s", role = "assistant", text = "先检查网卡吞吐，再确认带宽峰值。", status = "streaming")
        val before = p.build(listOf(stream), emptySet(), emptySet()).filterIsInstance<TextPiece>().single()
        val completed = stream.copy(status = "done", calls = listOf(ToolCall("first", "ssh_execute", "{}")))
        val output = Message(sessionId = "s", role = "tool", toolCallId = "first", toolName = "ssh_execute")
        val after = p.build(listOf(completed, output), emptySet(), emptySet())
        assertEquals(before.key, after.filterIsInstance<TextPiece>().single().key)
        assertEquals(stream.text, after.filterIsInstance<TextPiece>().single().prepared.content.text)
        assertTrue(after.first() is TextPiece); assertTrue(after.last() is OperationsHeader)
        assertTrue(after.none { it is ReplyEnd })
    }
    @Test fun stageNarrationSeparatesOperationGroupsInReadingOrder() {
        fun stage(id: String, text: String) = Message(id = id, sessionId = "s", role = "assistant", text = text, calls = listOf(ToolCall(id, "ssh_execute", "{}")))
        fun output(id: String) = Message(sessionId = "s", role = "tool", toolCallId = id, toolName = "ssh_execute")
        val source = listOf(stage("a", "先测量实时负载"), output("a"), stage("b", "再检查历史峰值"), output("b"), Message(sessionId = "s", role = "assistant", text = "当前负载正常"))
        val items = TimelineProjector().build(source, emptySet(), emptySet())
        assertEquals(listOf("先测量实时负载", "再检查历史峰值", "当前负载正常"), items.filterIsInstance<TextPiece>().map { it.block.text })
        assertEquals(2, items.filterIsInstance<OperationsHeader>().size)
        assertEquals(listOf("TextPiece", "OperationsHeader", "TextPiece", "OperationsHeader", "TextPiece", "ReplyEnd"), items.map { it.javaClass.simpleName })
    }
    @Test fun closingAndReopeningAGroupAlwaysClearsItsChildExpansions() {
        var state = OperationExpansion().group("a", setOf("one", "two")).step("one").step("two")
        state = state.group("a", setOf("one", "two"))
        assertTrue(state.groups.isEmpty()); assertTrue(state.steps.isEmpty())
        state = state.group("a", setOf("one", "two"))
        assertEquals(listOf("a"), state.groups); assertTrue(state.steps.isEmpty())
        val a = Message(id = "a", sessionId = "s", role = "assistant", calls = listOf(ToolCall("call", "read_file", "{}")))
        val t = Message(id = "one", sessionId = "s", role = "tool", toolCallId = "call")
        val items = TimelineProjector().build(listOf(a, t), emptySet(), setOf("one"))
        assertTrue(items.none { it is OperationRow })
    }
    @Test fun largeCodeBlocksAreVirtualizedWithoutChangingCopiedCode() {
        val code = (1..120).joinToString("\n") { "print($it)" }
        val m = Message(sessionId = "s", role = "assistant", text = "```python\n$code\n```", status = "done")
        val pieces = TimelineProjector().build(listOf(m), emptySet(), emptySet()).filterIsInstance<CodePiece>()
        assertEquals(5, pieces.size)
        assertTrue(pieces.all { it.full == code })
        assertEquals(code, pieces.joinToString("\n") { it.code })
    }
    @Test fun aGrowingCodeBlockKeepsItsFirstRenderNode() {
        val projector = TimelineProjector()
        val message = Message(id = "reply", sessionId = "s", role = "assistant", text = "```sh\necho start", status = "streaming")
        val first = projector.build(listOf(message), emptySet(), emptySet()).filterIsInstance<CodePiece>().first()
        val longer = message.copy(text = message.text + "\necho next".repeat(100))
        val grown = projector.build(listOf(longer), emptySet(), emptySet()).filterIsInstance<CodePiece>()
        assertEquals(first.key, grown.first().key)
        assertEquals(first.scrollKey, grown.first().scrollKey)
        assertTrue(grown.size > 1)
    }
    @Test fun archivedRetryVersionsDoNotAppearAsNewConversationsOrDuplicateReplies() {
        val old = Message(sessionId = "same", role = "assistant", text = "旧回复", status = "replaced")
        val current = Message(sessionId = "same", role = "assistant", text = "新回复")
        val items = TimelineProjector().build(listOf(old, current), emptySet(), emptySet())
        assertEquals(listOf(current.id), items.filterIsInstance<ReplyEnd>().map { it.message.id })
        assertEquals("新回复", items.filterIsInstance<TextPiece>().single().block.text)
    }
    @Test fun veryLongParagraphsAndTablesDoNotBecomeSingleUnboundedLayouts() {
        val text = "内容 🐈 ".repeat(2000)
        val paragraph = Message(sessionId = "s", role = "assistant", text = text)
        val pieces = TimelineProjector().build(listOf(paragraph), emptySet(), emptySet()).filterIsInstance<TextPiece>()
        assertTrue(pieces.size > 2); assertEquals(text, pieces.joinToString("") { it.block.text })
        assertTrue(pieces.all { it.block.text.length <= 2048 && !it.block.text.last().isHighSurrogate() })
        val table = "| name | value |\n| --- | --- |\n" + (1..100).joinToString("\n") { "| key$it | $it |" }
        val tables = TimelineProjector().build(listOf(paragraph.copy(text = table)), emptySet(), emptySet()).filterIsInstance<TextPiece>()
        assertEquals(100, tables.sumOf { requireNotNull(it.block.table).rows.size - 1 })
        assertTrue(tables.all { requireNotNull(it.block.table).rows.size <= 25 })
    }
    @Test fun retryKeepsTheSameSessionAndDoesNotRewindCompletedTools() {
        val user = Message(sessionId = "same", role = "user")
        val tool = Message(sessionId = "same", role = "tool", text = "已执行的结果")
        val error = Message(sessionId = "same", role = "assistant", status = "error")
        assertEquals("same", RetryPolicy.target(listOf(user, tool, error), error.id).sessionId)
        assertEquals("已执行的结果", tool.text)
        assertTrue(runCatching { RetryPolicy.target(listOf(user, error, tool), error.id) }.isFailure)
    }
}
