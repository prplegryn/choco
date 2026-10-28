package app.chocola.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotebookSaveState(val saving: Boolean = false, val conflict: Boolean = false, val error: String = "")

/** A composition can leave without cancelling a commit or dropping the latest draft. */
class NotebookEditing(private val store: NotebookStore, private val scope: CoroutineScope, document: NotebookDocument) {
    val path = document.path
    private val lock = Mutex()
    @Volatile private var revision = document.revision
    @Volatile private var text = document.text
    @Volatile private var saved = document.text
    private var pending: Job? = null
    @Volatile private var touched = false
    val state = MutableStateFlow(NotebookSaveState())
    fun changed(value: String) {
        touched = true
        text = value
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) { delay(300); commit(draftOnly = true); delay(450); commit() }
    }
    private suspend fun commit(draftOnly: Boolean = false): Boolean = lock.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            if (!touched) return@withContext true
            val value = text
            try {
                if (value == saved) { store.clearDraft(path); return@withContext true }
                store.saveDraft(path, value, revision)
                if (draftOnly || state.value.conflict) return@withContext !state.value.conflict
                state.value = NotebookSaveState(saving = true)
                val next = store.save(path, value, revision)
                revision = next.revision; saved = value
                if (text == value) store.clearDraft(path)
                state.value = NotebookSaveState()
                true
            } catch (e: Exception) {
                state.value = NotebookSaveState(conflict = e is NotebookConflict || state.value.conflict, error = e.message ?: "保存失败，草稿已保留")
                false
            }
        }
    }
    suspend fun flush(): Boolean { pending?.cancelAndJoin(); return commit() }
    fun persist() { scope.launch(Dispatchers.IO) { flush() } }
    fun adopt(document: NotebookDocument) { revision = document.revision; saved = document.text; text = document.text; state.value = NotebookSaveState(); touched = false }
}
