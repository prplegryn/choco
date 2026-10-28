package app.chocola

import app.chocola.agent.*
import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test

class ContextPlannerTest {
    private fun cycle(n: Int, text: String) = listOf(
        Message(id = "a$n", sessionId = "s", role = "assistant", calls = listOf(ToolCall("c$n", "run_shell", "{}"))),
        Message(id = "t$n", sessionId = "s", role = "tool", toolCallId = "c$n", toolName = "run_shell", text = text))
    @Test fun aSingleLongAgentTurnCanBeCompactedWithoutWaitingForMoreUserMessages() {
        val user = Message(id = "goal", sessionId = "s", role = "user", text = "部署并检查服务")
        val history = listOf(user) + (0..11).flatMap { cycle(it, "x".repeat(12000)) }
        val plan = requireNotNull(ContextPlanner.plan(history, Provider(contextWindow = 32768)))
        assertEquals("goal", plan.anchorUser)
        assertTrue(plan.prefix.size < history.size)
        assertTrue(plan.prefix.last().role == "tool")
        assertTrue(plan.chunks.isNotEmpty())
    }
    @Test fun parallelToolResultsNeverGetSeparatedFromTheirAssistantCall() {
        val a = Message(id = "a", sessionId = "s", role = "assistant", calls = listOf(ToolCall("one", "read_file", "{}"), ToolCall("two", "read_file", "{}")))
        val h = listOf(Message(sessionId = "s", role = "user"), a, Message(sessionId = "s", role = "tool", toolCallId = "one"), Message(sessionId = "s", role = "tool", toolCallId = "two"), Message(sessionId = "s", role = "assistant", text = "完成"))
        assertEquals(listOf(1, 4, 5), ContextPlanner.boundaries(h))
    }
    @Test fun oneVeryLongReplyCanBeCompactedBeforeTheNextUserQuestion() {
        val h = listOf(Message(id = "first", sessionId = "s", role = "user", text = "分析"),
            Message(id = "reply", sessionId = "s", role = "assistant", text = "资料".repeat(20000)),
            Message(id = "next", sessionId = "s", role = "user", text = "继续"))
        val plan = requireNotNull(ContextPlanner.plan(h, Provider(contextWindow = 16384)))
        assertEquals("reply", plan.through); assertEquals("", plan.anchorUser)
    }
    @Test fun anOversizedLatestToolGroupCanBeSummarizedWithItsGoalRetained() {
        val h = listOf(Message(id = "goal", sessionId = "s", role = "user")) + (0..7).flatMap { cycle(it, "测".repeat(20000)) }
        val plan = requireNotNull(ContextPlanner.plan(h, Provider(contextWindow = 8192)))
        assertEquals("goal", plan.anchorUser)
        assertEquals(h.last().id, plan.through)
    }
    @Test fun textChunkingPreservesAllDataAndDoesNotBreakSurrogatePairs() {
        val text = ("内容 abc 🐈\n").repeat(3000)
        val chunks = ContextPlanner.textChunks(text, 1000)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { !it.last().isHighSurrogate() && ContextPlanner.tokens(it) <= 1004 })
    }
    @Test fun preCompactionUsageDoesNotInflateTheNewCompactedWindow() {
        val history = listOf(Message(sessionId = "s", role = "assistant", text = "摘要后保留", inputTokens = 90000, created = 1))
        assertTrue(ContextPlanner.used(history, summaryAt = 2) < 100)
        assertTrue(ContextPlanner.used(history, summaryAt = 0) >= 90000)
    }
    @Test fun longToolResultsKeepBothEndsAndTheArtifactReference() {
        val m = Message(id = "result", sessionId = "s", role = "tool", text = "START" + "x".repeat(20000) + "END")
        val context = ContextPlanner.toolContext(m)
        assertTrue(context.text.startsWith("START")); assertTrue(context.text.endsWith("END"))
        assertTrue(context.text.contains("/workspace/.chocola/outputs/result.txt"))
        assertEquals(20008, m.text.length)
    }
}
