package app.chocola

import app.chocola.data.*
import app.chocola.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun image(name: String) = Attachment(name, "/private/imports/$name", "image/png", 100, 1200, 800)
    private fun mark(id: String) = AnnotationMark(id = id, tool = "pen", points = listOf(AnnotationPoint(.1f, .2f), AnnotationPoint(.3f, .4f)))

    @Test fun referenceButtonsUseUppercaseBracketedNamesAndOnlySuppressTheCurrentTail() {
        assertEquals("图A", ImageReferences.label(0)); assertEquals("图Z", ImageReferences.label(25)); assertEquals("图AA", ImageReferences.label(26))
        val text = ImageReferences.append("参考", "图A")
        assertEquals("参考（图A）", text)
        assertEquals(text, ImageReferences.append(text, "图A"))
        assertEquals(text + "\n", ImageReferences.append(text + "\n", "图A"))
        assertEquals("参考（图A）文字（图A）", ImageReferences.append(text + "文字", "图A"))
        assertEquals("参考（图A）（图B）", ImageReferences.append(text, "图B"))
    }
    @Test fun removingAndEditingAnAttachmentNeverRenumbersOrReusesItsReference() {
        val first = AttachmentDraft().add(listOf(image("one.png"), image("two.png")))
        val remaining = first.copy(items = listOf(first.items[1])).add(listOf(image("three.png")))
        val restored = AttachmentDraft.from(JSONObject(remaining.json().toString()))
        assertEquals(listOf("图B", "图C"), restored.items.map { it.imageLabel })
        assertEquals(2, restored.items.map { it.draftId }.distinct().size)
        val outgoing = ImageReferences.submitted(restored.items)
        assertEquals(listOf("图B", "图C"), outgoing.map { it.imageLabel })
        assertTrue(outgoing.all { it.draftId.isEmpty() && it.originalPath.isEmpty() })
    }
    @Test fun providerTextUsesActualImageNamesWhileStoredUserTextKeepsReferences() {
        val a = image("a (original).png").copy(imageLabel = "图A")
        val b = image("b-final.png").copy(imageLabel = "图B")
        val message = Message(sessionId = "s", role = "user", text = "用（图A）修改（图B），图AB是另外的。", attachments = listOf(a, b))
        val wire = AttachmentInputs.text(message)
        assertTrue(wire.contains("用（文件「a (original).png」）修改（文件「b-final.png」）"))
        assertTrue(wire.contains("图AB是另外的")); assertTrue(wire.contains("/workspace/imports/b-final.png"))
        assertEquals(message.text, Message.from(message.json()).text)
    }
    @Test fun imageSlotsPersistAndKeepOneTimelineIdentityFromWaitingThroughPartialFailure() {
        val batch = ImageBatch.forSettings(ImageSettings(size = "2160x3840", count = 5))
        assertEquals(.5625f, batch.aspectRatio, .0001f)
        val pending = Message(sessionId = "s", role = "tool", toolName = "generate_image", status = "running", imageBatch = batch)
        val partial = Message.from(pending.copy(attachments = listOf(image("done.png")), status = "error").json())
        assertEquals(5, partial.imageBatch?.count)
        val projector = TimelineProjector()
        val before = projector.build(listOf(pending), emptySet(), emptySet()).filterIsInstance<ImageItem>().single()
        val after = projector.build(listOf(partial), emptySet(), emptySet()).filterIsInstance<ImageItem>().single()
        assertEquals(before.key, after.key); assertEquals(1, after.message.attachments.size)
    }
    @Test fun directImageRequestsRecognizeExplicitBatchCountsWithoutCountingSceneObjects() {
        assertEquals(5, ImageBatch.requestedCount("帮我画五张图，鹈鹕主题"))
        assertEquals(2, ImageBatch.requestedCount("生成两幅海报"))
        assertEquals(3, ImageBatch.requestedCount("画3张，采用不同色彩"))
        assertEquals(4, ImageBatch.requestedCount("Draw 4张图片"))
        assertEquals(2, ImageBatch.requestedCount("画五张图\n改为两张图"))
        assertNull(ImageBatch.requestedCount("图中有5张桌子"))
        assertNull(ImageBatch.requestedCount("画一只鹈鹕"))
        assertTrue(runCatching { ImageBatch.requestedCount("画十一张图") }.isFailure)
        assertTrue(runCatching { ImageBatch.requestedCount("画12张图") }.isFailure)
    }
    @Test fun annotationUndoRedoAndClearSurviveClosingAndReopeningTheEditor() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val root = temporary.newFolder()
            val store = AnnotationStore(root, scope)
            val editor = store.open("draft-one")
            editor.append(AnnotationAction("add", mark("a"))); editor.append(AnnotationAction("add", mark("b")))
            editor.append(AnnotationAction("remove", targets = listOf("a")))
            assertEquals(listOf("b"), editor.state.value.marks().map { it.id })
            editor.undo(); assertEquals(listOf("a", "b"), editor.state.value.marks().map { it.id })
            editor.flush(); store.reload()
            val reopened = store.open("draft-one")
            assertTrue(reopened.state.value.canRedo)
            reopened.redo(); assertEquals(listOf("b"), reopened.state.value.marks().map { it.id })
            reopened.append(AnnotationAction("clear")); assertTrue(reopened.state.value.marks().isEmpty())
            reopened.undo(); assertEquals(listOf("b"), reopened.state.value.marks().map { it.id })
            reopened.flush(); store.reload()
        } finally { scope.cancel() }
    }
    @Test fun discardedAnnotationHistoryCannotBeRecreatedByQueuedWrites() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val root = temporary.newFolder(); val store = AnnotationStore(root, scope)
            val editor = store.open("draft-two")
            repeat(12) { editor.append(AnnotationAction("add", mark("mark-$it"))) }
            store.discard("draft-two")
            scope.coroutineContext[Job]?.children?.toList()?.joinAll()
            assertFalse(File(root, "draft-two.json").exists())
            assertTrue(store.open("draft-two").state.value.actions.isEmpty())
            store.discard("draft-two")
        } finally { scope.cancel() }
    }
    @Test fun nativeImageModelsAreStablePrivateProvidersAndDoNotRequireTextReasoning() {
        val catalog = CodexImageModels.include(listOf(CodexModel("gpt-6.1-sol")))
        assertEquals(catalog.size, CodexImageModels.include(catalog).size)
        val images = catalog.filter { ImageModels.isImage(it.slug) }
        assertEquals(CodexImageModels.names, images.map { it.slug })
        images.forEach { model ->
            val provider = model.provider()
            assertTrue(ImageModels.isImage(provider)); assertTrue(CodexWire.isCodex(provider))
            assertEquals(provider.id, model.provider().id); assertTrue(provider.apiKey.isBlank()); assertTrue(provider.reasoningLevels.isEmpty())
        }
        assertTrue(CodexImageModels.include(emptyList()).isEmpty())
    }
}
