package app.chocola.data

/** The agent can revise a plain fact, but never replace a credential or a custom record. */
object MemoryWrites {
    fun remember(memories: List<Memory>, name: String, content: String, scope: String): Memory {
        require(name.isNotBlank() && content.isNotBlank()) { "记忆名称和内容不能为空" }
        require(!Redactor.containsSecret(content) && !content.contains("PRIVATE KEY", ignoreCase = true)) { "请在记忆页面手动保存敏感凭据" }
        val matches = memories.filter { it.name == name && it.scope == scope }
        require(matches.all { it.kind == "资料" && !it.toolsAllowed && it.domain.isBlank() &&
            it.fields.size == 1 && it.fields.single().name == "内容" && !it.fields.single().secret }) {
            "同名资料包含凭据或自定义字段，请为这条记忆使用其他名称"
        }
        val previous = matches.firstOrNull()
        return (previous ?: Memory(name = name, scope = scope)).copy(fields = listOf(MemoryField("内容", content)))
    }
}
