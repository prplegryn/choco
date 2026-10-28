package app.chocola

import app.chocola.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NotebookStoreTest {
    private fun store(test: (NotebookStore, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("choco-notes").toFile()
        try { test(NotebookStore(root), root) } finally { root.deleteRecursively() }
    }
    @Test fun unicodeMarkdownSurvivesCreateReadAndExport() = store { notes, root ->
        notes.create("", "理工科", true)
        val text = "# 测试 📝\n\n- [ ] Task\n\n$$\n\\begin{bmatrix}a&b\\\\c&d\\end{bmatrix}\n$$\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val file = notes.create("理工科", "notebook", false, text)
        assertEquals("理工科/notebook.md", file.path)
        assertEquals(text, notes.read(file.path).text)
        assertEquals(text, notes.export(file.path, root.resolve("export")).readText())
        assertEquals(listOf("理工科"), notes.list().map { it.name })
    }
    @Test fun writesRequireTheCurrentRevisionAndPreserveHistoricalContent() = store { notes, _ ->
        val path = notes.create("", "Work", false, "first").path
        val first = notes.read(path)
        val next = notes.save(path, "second", first.revision)
        assertNotEquals(first.revision, next.revision)
        assertEquals("first", notes.revision(path, notes.revisions(path).single().id))
        assertTrue(runCatching { notes.save(path, "overwrite", first.revision) }.exceptionOrNull() is NotebookConflict)
        assertEquals("second", notes.read(path).text)
    }
    @Test fun anAndroidStyleAliasInAParentDirectoryKeepsPortableRelativePaths() {
        val actual = Files.createTempDirectory("note-real").toFile()
        val holder = Files.createTempDirectory("note-parent").toFile()
        val alias = holder.resolve("alias")
        try {
            Files.createSymbolicLink(alias.toPath(), actual.toPath())
            val notes = NotebookStore(alias.resolve("notebooks"))
            assertEquals("One.md", notes.create("", "One", false, "keep").path)
            assertEquals("One.md", notes.list().single().path)
            assertEquals("keep", notes.read("One.md").text)
        } finally { alias.delete(); holder.deleteRecursively(); actual.deleteRecursively() }
    }
    @Test fun nestedMovesCarryDraftsAndHistoryWithoutDuplicatingTheNote() = store { notes, _ ->
        notes.create("", "A", true); notes.create("", "B", true)
        notes.create("A", "Draft", false, "before")
        val first = notes.read("A/Draft.md"); val next = notes.save(first.path, "after", first.revision)
        notes.saveDraft(first.path, "unsaved", next.revision)
        notes.move("A", "B", "Moved")
        val path = "B/Moved/Draft.md"
        assertEquals("after", notes.read(path).text)
        assertEquals("unsaved", notes.draft(path)?.text)
        assertEquals("before", notes.revision(path, notes.revisions(path).single().id))
        assertNull(notes.draft(first.path))
    }
    @Test fun trashIsRecoverableAndCannotOverwriteANewerFile() = store { notes, _ ->
        notes.create("", "Keep", false, "original")
        val trash = notes.trash("Keep.md")
        assertTrue(notes.list().isEmpty())
        notes.create("", "Keep", false, "new")
        assertTrue(runCatching { notes.restoreTrash(trash.id) }.isFailure)
        assertEquals("new", notes.read("Keep.md").text)
        notes.move("Keep.md", "", "New")
        notes.restoreTrash(trash.id)
        assertEquals("original", notes.read("Keep.md").text)
        assertTrue(notes.trashList().isEmpty())
    }
    @Test fun searchIncludesDescendantContentsAndFolderNames() = store { notes, _ ->
        notes.create("", "Research", true); notes.create("Research", "server", false, "Bandwidth report")
        assertEquals("Research/server.md", notes.list(query = "bandwidth").single().path)
        assertEquals("Research", notes.list(query = "research").single().path)
    }
    @Test fun traversalHiddenNamesAndSymlinksCannotEscapeTheNotebookRoot() = store { notes, root ->
        for (path in listOf("../secret.md", "/absolute.md", "x/../../escape.md", ".private/data.md", "x\\y.md")) assertTrue(path, runCatching { notes.read(path) }.isFailure)
        for (name in listOf("../x", ".hidden", "a/b", "a\u0000b")) assertTrue(name, runCatching { notes.create("", name, false) }.isFailure)
        val outside = Files.createTempDirectory("outside-notes").toFile()
        try {
            outside.resolve("keep.md").writeText("secret")
            val link = root.resolve("documents/link"); Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertTrue(runCatching { notes.read("link/keep.md") }.isFailure)
            assertFalse(notes.list().any { it.name == "link" })
            link.delete()
        } finally { outside.deleteRecursively() }
    }
    @Test fun duplicateNamesAndRecursiveMovesFailWithoutLosingContent() = store { notes, _ ->
        notes.create("", "Folder", true); notes.create("Folder", "Child", true); notes.create("Folder", "note", false, "keep")
        assertTrue(runCatching { notes.create("Folder", "note.md", false, "overwrite") }.isFailure)
        assertTrue(runCatching { notes.move("Folder", "Folder/Child", "Again") }.isFailure)
        assertEquals("keep", notes.read("Folder/note.md").text)
    }
    @Test fun editsCommitOnLeavingAndConflictsKeepTheDraft() = store { notes, _ -> runBlocking {
        notes.create("", "Work", false, "initial")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = NotebookEditing(notes, scope, notes.read("Work.md"))
            editor.changed("saved on leave"); assertTrue(editor.flush())
            assertEquals("saved on leave", notes.read("Work.md").text); assertNull(notes.draft("Work.md"))
            val version = notes.read("Work.md")
            notes.save("Work.md", "AI changes", version.revision)
            editor.changed("manual changes"); assertFalse(editor.flush())
            assertEquals("AI changes", notes.read("Work.md").text)
            assertEquals("manual changes", notes.draft("Work.md")?.text)
        } finally { scope.cancel() }
    } }
    @Test fun merelyReadingANoteDoesNotEraseAnUnopenedDraft() = store { notes, _ -> runBlocking {
        notes.create("", "Work", false, "saved")
        val document = notes.read("Work.md"); notes.saveDraft(document.path, "pending", document.revision)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { assertTrue(NotebookEditing(notes, scope, document).flush()); assertEquals("pending", notes.draft(document.path)?.text) } finally { scope.cancel() }
    } }
    @Test fun revisionRetentionIsBoundedAndNoTemporaryFilesRemain() = store { notes, root ->
        notes.create("", "History", false)
        repeat(25) { val old = notes.read("History.md"); notes.save(old.path, "Version $it", old.revision) }
        assertEquals(20, notes.revisions("History.md").size)
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }
    @Test fun markdownTransformsRespectSelectionAndUnicode() {
        val bold = MarkdownEditing.wrap("你好世界", 2, 4, "**")
        assertEquals("你好**世界**", bold.text)
        assertEquals("世界", bold.text.substring(bold.start, bold.end))
        assertEquals("你好世界", MarkdownEditing.wrap(bold.text, bold.start, bold.end, "**").text)
        val list = MarkdownEditing.line("one\ntwo", 0, 7, "- ")
        assertEquals("- one\n- two", list.text)
        assertEquals("one\ntwo", MarkdownEditing.line(list.text, list.start, list.end, "- ").text)
        assertEquals("甲X乙", MarkdownEditing.insert("甲乙", 1, 1, "X").text)
        assertEquals("### Heading", MarkdownEditing.heading("# Heading", 0, 9, 3).text)
        assertEquals("Heading", MarkdownEditing.heading("## Heading", 0, 10, 0).text)
    }
}
