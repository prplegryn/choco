package app.chocola

import app.chocola.agent.TurnContext
import app.chocola.agent.TurnInbox
import app.chocola.api.ProtocolCodec
import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnInboxTest {
    private fun input(id: String, created: Long = 1) = Message(id = id, sessionId = "s", role = "user", text = "补充 $id", created = created, status = "queued")

    @Test fun initialMessagePersistenceAndQueueRestorationFinishBeforeAnInsertionIsAccepted() = runTest {
        val writes = mutableListOf<String>()
        val inbox = TurnInbox(ready = false) { writes += it.id }
        val insert = async { inbox.offer(input("insert", 20)) }; runCurrent()
        assertFalse(insert.isCompleted); assertTrue(writes.isEmpty())
        writes += "initial"
        inbox.restore(listOf(input("restored", 10)))
        inbox.start()
        assertTrue(insert.await())
        assertEquals(listOf("initial", "insert"), writes)
        assertEquals(listOf("restored", "insert"), inbox.take().map { it.id })
    }

    @Test fun cancellationDuringStartupReleasesWaitingSubmissionsWithoutPersistingThem() = runTest {
        val inbox = TurnInbox(ready = false) { error("Stopped startup cannot accept input") }
        val insert = async { inbox.offer(input("insert")) }; runCurrent()
        assertFalse(insert.isCompleted)
        inbox.close(interrupted = true)
        assertFalse(insert.await()); assertTrue(inbox.interrupted)
    }

    @Test fun acceptingAnInputPreventsTheSameRunFromFinishingUntilItWasConsumed() = runTest {
        val saved = linkedMapOf<String, Message>()
        val inbox = TurnInbox { saved[it.id] = it }
        assertTrue(inbox.offer(input("one")))
        assertEquals("queued", saved["one"]?.status)
        assertFalse(inbox.finishIfEmpty())
        assertEquals(listOf("one"), inbox.take().map { it.id })
        assertEquals("done", saved["one"]?.status)
        assertTrue(inbox.take().isEmpty())
        assertTrue(inbox.finishIfEmpty())
        assertFalse(inbox.offer(input("late")))
        assertFalse(saved.containsKey("late"))
    }

    @Test fun finishingCannotOvertakeAnInputThatIsStillBeingPersisted() = runTest {
        val entered = CompletableDeferred<Unit>(); val commit = CompletableDeferred<Unit>()
        val inbox = TurnInbox { entered.complete(Unit); commit.await() }
        val offer = async { inbox.offer(input("one")) }; entered.await()
        val finish = async { inbox.finishIfEmpty() }; runCurrent()
        assertFalse(finish.isCompleted)
        commit.complete(Unit)
        assertTrue(offer.await()); assertFalse(finish.await())
    }

    @Test fun aCompletedRunRejectsNewInputsSoTheyCanBecomeTheNextTurn() = runTest {
        val inbox = TurnInbox { error("Closed inbox must not persist") }
        assertTrue(inbox.finishIfEmpty())
        assertFalse(inbox.offer(input("after")))
    }

    @Test fun acceptedInputsSurviveCallerCancellationAndCanRecoverInAnotherRun() = runTest {
        val saved = linkedMapOf<String, Message>()
        val entered = CompletableDeferred<Unit>(); val commit = CompletableDeferred<Unit>()
        val inbox = TurnInbox { entered.complete(Unit); commit.await(); saved[it.id] = it }
        val submit = launch { inbox.offer(input("durable")) }
        entered.await(); submit.cancel(); commit.complete(Unit); submit.join(); inbox.close()
        assertEquals("queued", saved["durable"]?.status)
        val recovered = TurnInbox { saved[it.id] = it }
        recovered.restore(saved.values.map { Message.from(JSONObject(it.json().toString())) })
        assertEquals(listOf("durable"), recovered.take().map { it.id })
        assertEquals("done", saved["durable"]?.status)
    }

    @Test fun olderRestoredInputsRemainBeforeNewlyArrivingInputsAndAreNeverDuplicated() = runTest {
        val inbox = TurnInbox { }
        inbox.offer(input("new", 20))
        inbox.restore(listOf(input("old", 10), input("new", 20)))
        assertEquals(listOf("old", "new"), inbox.take().map { it.id })
        assertFalse(inbox.hasPending())
    }

    @Test fun stoppingPreservesQueuedMessagesAndWithdrawalIsOnlyAllowedBeforeConsumption() = runTest {
        val saved = linkedMapOf<String, Message>(); val inbox = TurnInbox { saved[it.id] = it }
        inbox.offer(input("one")); inbox.offer(input("two"))
        assertTrue(inbox.withdraw("one")); assertEquals("withdrawn", saved["one"]?.status)
        inbox.close(); assertFalse(inbox.offer(input("three")))
        assertEquals("queued", saved["two"]?.status)
        assertEquals(listOf("two"), inbox.take().map { it.id })
        assertFalse(inbox.withdraw("two"))
    }

    @Test fun inputQueuesAreIsolatedBetweenConversations() = runTest {
        val a = TurnInbox { }; val b = TurnInbox { }
        a.offer(input("a")); b.offer(input("b").copy(sessionId = "other"))
        a.close(); assertEquals(listOf("a"), a.take().map { it.id })
        assertEquals(listOf("b"), b.take().map { it.id })
    }

    @Test fun midToolMessagesKeepChronologicalUiOrderAndValidToolPairsForEveryProtocol() {
        val user = Message(id = "user", sessionId = "s", role = "user", text = "创建文件")
        val assistant = Message(id = "assistant", sessionId = "s", role = "assistant", text = "开始处理", calls = listOf(ToolCall("a", "write_file", "{}"), ToolCall("b", "read_file", "{}")))
        val extra = input("extra").copy(status = "done")
        val result = Message(id = "result", sessionId = "s", role = "tool", toolName = "write_file", toolCallId = "a", text = "写入成功")
        val skipped = Message(id = "skipped", sessionId = "s", role = "tool", toolName = "read_file", toolCallId = "b", text = "用户补充后未执行", status = "skipped", hidden = true)
        val chronological = listOf(user, assistant, extra, result, skipped)
        val normalized = TurnContext.normalize("s", chronological)
        assertEquals(listOf("user", "assistant", "result", "skipped", "extra"), normalized.map { it.id })
        assertEquals(listOf("user", "assistant", "extra", "result", "skipped"), chronological.map { it.id })
        Protocol.entries.forEach { protocol ->
            val body = ProtocolCodec.request(Provider(model = "custom", protocol = protocol), "system", normalized, JSONArray())
            val wire = body.toString()
            assertTrue(protocol.name, wire.contains("补充 extra")); assertTrue(wire.contains("写入成功")); assertTrue(wire.contains("用户补充后未执行"))
        }
    }

    @Test fun pendingAndWithdrawnInputsStayOutOfModelAndCompactionHistory() {
        val kept = input("kept").copy(status = "done")
        val source = listOf(kept, input("pending"), input("gone").copy(status = "withdrawn"))
        assertEquals(listOf("kept"), TurnContext.normalize("s", source).map { it.id })
    }

    @Test fun interruptedToolResultsAreSynthesizedOnceWithoutGuessingThatAnOperationFailed() {
        val assistant = Message(sessionId = "s", role = "assistant", calls = listOf(ToolCall("a", "ssh_execute", "{}")))
        val normalized = TurnContext.normalize("s", listOf(assistant, input("extra").copy(status = "done")))
        assertEquals(3, normalized.size)
        assertEquals("a", normalized[1].toolCallId); assertEquals("interrupted", normalized[1].status)
        assertTrue(normalized[1].text.contains("先检查实际状态"))
    }
}
