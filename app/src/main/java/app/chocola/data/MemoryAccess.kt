package app.chocola.data

/** A task grant can use a scoped credential without broadening that credential's global scope. */
internal object MemoryAccess {
    fun available(memory: Memory, session: Session): Boolean {
        if (!memory.enabled) return false
        if (session.scheduledTaskId.isNotBlank()) return memory.id in session.taskCredentials ||
            (memory.scope.isBlank() && memory.kind == "资料" && memory.fields.none { it.secret })
        return memory.scope.isBlank() || memory.scope == session.id
    }
    fun toolAllowed(memory: Memory, session: Session) = available(memory, session) &&
        (memory.toolsAllowed || memory.id in session.taskCredentials)
}
