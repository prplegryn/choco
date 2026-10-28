package app.chocola.data

import org.json.JSONObject

data class AttachmentDraft(val items: List<Attachment> = emptyList(), val nextImage: Int = 0) {
    fun add(files: List<Attachment>): AttachmentDraft {
        var next = nextImage
        val added = files.map { file -> file.copy(draftId = newId(), imageLabel = if (file.mime.startsWith("image/")) ImageReferences.label(next++) else "") }
        return copy(items = items + added, nextImage = next)
    }
    fun json() = JSONObject().put("items", jsonArray(items.map { it.json() })).put("nextImage", nextImage)
    companion object { fun from(j: JSONObject) = AttachmentDraft(j.optJSONArray("items")?.objects()?.map(Attachment::from).orEmpty(), j.optInt("nextImage")) }
}

object ImageReferences {
    fun label(index: Int): String {
        var value = index.coerceAtLeast(0) + 1
        var letters = ""
        while (value > 0) { value--; letters = ('A' + value % 26) + letters; value /= 26 }
        return "图$letters"
    }
    fun append(text: String, label: String): String {
        val reference = "（$label）"
        return if (text.trimEnd().endsWith(reference, ignoreCase = true)) text else text + reference
    }
    /** Displayed text stays untouched. Only provider input receives the exact attached filename. */
    fun wireText(message: Message): String = Regex("图[a-z]+", RegexOption.IGNORE_CASE).replace(message.text) { match ->
        message.attachments.firstOrNull { it.imageLabel.equals(match.value, ignoreCase = true) }?.let { "文件「${it.name}」" } ?: match.value
    }
    fun submitted(items: List<Attachment>) = items.map { it.copy(draftId = "", originalPath = "", originalMime = "") }
}
