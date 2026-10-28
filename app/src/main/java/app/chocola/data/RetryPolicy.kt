package app.chocola.data

object RetryPolicy {
    fun target(messages: List<Message>, messageId: String): Message {
        val visible = messages.filter { it.status != "replaced" }
        val message = requireNotNull(visible.lastOrNull { it.id == messageId }) { "回复不存在" }
        check(visible.lastOrNull()?.id == messageId && message.role == "assistant" && message.calls.isEmpty()) { "较早的回复请从明确的分支入口继续" }
        return message
    }
}
