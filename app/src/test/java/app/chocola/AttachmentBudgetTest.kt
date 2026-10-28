package app.chocola

import app.chocola.agent.ContextPlanner
import app.chocola.api.ProtocolCodec
import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AttachmentBudgetTest {
    private fun withFile(body: (File) -> Unit) {
        val root = Files.createTempDirectory("choco-attachment").toFile()
        try { body(File(root, "report.txt")) } finally { root.deleteRecursively() }
    }
    @Test fun textAttachmentUsesTheSameBoundedContentInAllProtocolsAndBudgeting() = withFile { file ->
        file.writeText("资料".repeat(40_000))
        val user = Message(sessionId = "s", role = "user", text = "请分析", attachments = listOf(Attachment(file.name, file.path, "text/plain", file.length())))
        val content = AttachmentInputs.text(user)
        assertTrue(content.contains("附件正文已截断"))
        assertTrue(ContextPlanner.tokens(user) > 70_000)
        Protocol.entries.forEach { protocol ->
            val request = ProtocolCodec.request(Provider(protocol = protocol), "system", listOf(user))
            val actual = when (protocol) {
                Protocol.CHAT -> request.getJSONArray("messages").getJSONObject(1).getString("content")
                Protocol.ANTHROPIC -> request.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
                Protocol.RESPONSES -> request.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
            }
            assertEquals(protocol.name, content, actual)
        }
        assertTrue(runCatching { ContextPlanner.requireFits(Provider(contextWindow = 8192, maxTokens = 2048), "system", listOf(user)) }.isFailure)
    }
    @Test fun newAttachmentCanTriggerCompactionEvenAfterProviderUsageWasRecorded() = withFile { file ->
        file.writeText("a".repeat(60_000))
        val assistant = Message(sessionId = "s", role = "assistant", inputTokens = 100, created = 20)
        val next = Message(sessionId = "s", role = "user", attachments = listOf(Attachment(file.name, file.path, "text/plain", file.length())))
        val provider = Provider(contextWindow = 16384, maxTokens = 2048)
        assertTrue(ContextPlanner.shouldCompact(provider, Session(id = "s"), listOf(assistant, next), 200))
        assertTrue(ContextPlanner.used(listOf(assistant, next)) > 20_000)
    }
    @Test fun imageInputHasAnAllowanceOnTheFirstRequest() = withFile { file ->
        file.writeBytes(byteArrayOf(1, 2, 3))
        val plain = Message(sessionId = "s", role = "user", text = "看图")
        val image = plain.copy(attachments = listOf(Attachment("photo.png", file.path, "image/png", file.length())))
        assertTrue(ContextPlanner.tokens(image) >= ContextPlanner.tokens(plain) + 4096)
    }
    @Test fun compactedAnchorKeepsFileLocationsWithoutResendingItsFullAttachment() = withFile { file ->
        file.writeText("大文件内容".repeat(1000))
        val original = Message(sessionId = "s", role = "user", text = "检查文件", attachments = listOf(Attachment(file.name, file.path, "text/plain", file.length())))
        val rendered = ContextPlanner.render(listOf(original))
        assertTrue(rendered.contains("大文件内容"))
        val anchor = AttachmentInputs.summarized(original)
        assertTrue(anchor.attachments.isEmpty()); assertTrue(anchor.text.contains("/workspace/"))
        assertTrue(anchor.text.contains("已整理进上下文摘要"))
        assertTrue(ContextPlanner.tokens(anchor) < ContextPlanner.tokens(original) / 2)
        assertEquals(1, original.attachments.size)
    }
    @Test fun editedAndMissingFilesCannotReuseAStaleExcerpt() = withFile { file ->
        file.writeText("old")
        val user = Message(sessionId = "s", role = "user", attachments = listOf(Attachment(file.name, file.path, "text/plain", 3)))
        assertTrue(AttachmentInputs.text(user).endsWith("old"))
        file.writeText("replacement")
        assertTrue(AttachmentInputs.text(user).endsWith("replacement"))
        file.delete()
        assertTrue(AttachmentInputs.text(user).contains("附件暂不可读"))
    }
    @Test fun compactedMixedAttachmentsKeepImagesThatWereNotVisuallySummarized() = withFile { file ->
        file.writeText("资料")
        val text = Attachment(file.name, file.path, "text/plain", file.length())
        val image = Attachment("photo.png", file.path, "image/png", file.length())
        val original = Message(sessionId = "s", role = "user", attachments = listOf(text, image))
        val anchor = AttachmentInputs.summarized(original)
        assertEquals(listOf(image), anchor.attachments)
        assertTrue(anchor.text.contains(file.name))
        val imageOnly = original.copy(attachments = listOf(image))
        assertSame(imageOnly, AttachmentInputs.summarized(imageOnly))
    }
}
