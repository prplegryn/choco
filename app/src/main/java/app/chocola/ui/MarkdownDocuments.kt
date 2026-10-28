package app.chocola.ui

/** Completed replies are reused when returning to a conversation; partial streams are not cached. */
internal object MarkdownDocuments {
    private const val MaxCharacters = 500_000
    private val documents = LinkedHashMap<String, List<MarkdownBlock>>(32, .75f, true)
    private var characters = 0

    @Synchronized fun peek(text: String): List<MarkdownBlock>? = documents[text]

    fun parse(text: String): List<MarkdownBlock> {
        peek(text)?.let { return it }
        val parsed = markdownBlocks(text)
        if (text.length > MaxCharacters) return parsed
        synchronized(this) {
            if (documents.containsKey(text)) return documents.getValue(text)
            documents[text] = parsed
            characters += text.length
            val oldest = documents.entries.iterator()
            while ((characters > MaxCharacters || documents.size > 48) && oldest.hasNext()) {
                characters -= oldest.next().key.length
                oldest.remove()
            }
        }
        return parsed
    }
}
