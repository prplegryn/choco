package app.chocola.agent

import app.chocola.data.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A session's durable input boundary. Finishing and accepting input share the same lock. */
internal class TurnInbox(ready: Boolean = true, private val persist: suspend (Message) -> Unit) {
    private val started = CompletableDeferred<Unit>().apply { if (ready) complete(Unit) }
    private val lock = Mutex()
    private val pending = linkedMapOf<String, Message>()
    @Volatile private var accepting = true
    @Volatile var interrupted = false
        private set

    suspend fun restore(messages: List<Message>) = lock.withLock {
        val combined = (messages.filter { it.role == "user" && it.status == "queued" } + pending.values).distinctBy { it.id }.sortedBy { it.created }
        pending.clear(); combined.forEach { pending[it.id] = it }
    }

    fun start() { started.complete(Unit) }

    suspend fun offer(message: Message): Boolean {
        started.await()
        return lock.withLock {
            if (!accepting) return@withLock false
            require(message.role == "user" && message.status == "queued")
            // Once accepted, navigation or caller cancellation must not discard a persisted input.
            withContext(NonCancellable) { persist(message); pending[message.id] = message }
            true
        }
    }

    suspend fun hasPending(): Boolean = lock.withLock { pending.isNotEmpty() }

    suspend fun take(): List<Message> = lock.withLock {
        withContext(NonCancellable) {
            pending.values.map { it.copy(status = "done") }.also { messages ->
                messages.forEach { message -> persist(message); pending.remove(message.id) }
            }
        }
    }

    suspend fun withdraw(id: String): Boolean {
        started.await()
        return lock.withLock {
            val message = pending[id] ?: return@withLock false
            withContext(NonCancellable) { persist(message.copy(status = "withdrawn")); pending.remove(id) }
            true
        }
    }

    suspend fun finishIfEmpty(): Boolean = lock.withLock {
        if (pending.isNotEmpty()) false else { accepting = false; started.complete(Unit); true }
    }

    fun close(interrupted: Boolean = false) { if (interrupted) this.interrupted = true; accepting = false; started.complete(Unit) }
}

/** UI order stays chronological; model history keeps every tool call beside its matching result. */
internal object TurnContext {
    fun normalize(session: String, source: List<Message>): List<Message> {
        val visible = source.filter { it.status !in setOf("replaced", "queued", "withdrawn") }
        val results = visible.filter { it.role == "tool" }.associateBy { it.toolCallId }
        return buildList {
            visible.forEach { message ->
                if (message.role == "tool") return@forEach
                if (message.role == "assistant" && message.text.isBlank() && message.calls.isEmpty() && message.attachments.isEmpty()) return@forEach
                add(message)
                if (message.role == "assistant") message.calls.forEach { call ->
                    add(ContextPlanner.toolContext(results[call.id] ?: Message(sessionId = session, role = "tool", toolName = call.name,
                        toolCallId = call.id, text = "操作已中断，未获得完成结果。先检查实际状态，不得盲目重放。", status = "interrupted")))
                }
            }
        }
    }
}
