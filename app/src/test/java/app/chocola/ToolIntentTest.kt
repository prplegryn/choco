package app.chocola

import app.chocola.agent.*
import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test

class ToolIntentTest {
    private fun user(text: String) = Message(sessionId = "s", role = "user", text = text)
    @Test fun standaloneGreetingsAndThanksDoNotExposeTools() {
        for (text in listOf("你好！", "您好", "Hello!", "hi", "谢谢你。", "Thanks!")) assertTrue(text, ToolIntent.socialOnly(listOf(user(text))))
    }
    @Test fun actualRequestsAndTaskConfirmationsRetainNormalToolSelection() {
        for (text in listOf("你好，帮我检查服务器", "请计算一个积分", "继续", "新建一份笔记", "好的", "hi, read my files")) assertFalse(text, ToolIntent.socialOnly(listOf(user(text))))
        assertFalse(ToolIntent.socialOnly(listOf(user("你好").copy(attachments = listOf(Attachment("image", "x", "image/png", 1))))))
    }
    @Test fun previousToolUseDoesNotTurnANewGreetingIntoAnOperation() {
        val history = listOf(user("计算积分"), Message(sessionId = "s", role = "tool", toolName = "math", text = "done"), user("你好"))
        assertTrue(ToolIntent.socialOnly(history))
    }
    @Test fun ambiguousNotebookRequestsMustBeClarifiedBeforeWriting() {
        val history = listOf(user("帮我把这些整理成笔记"))
        assertEquals(NotebookDestination.UNSPECIFIED, NotebookIntent.destination(history))
        assertTrue(runCatching { NotebookIntent.requireDestination("create_notebook", history) }.isFailure)
        assertTrue(runCatching { NotebookIntent.requireDestination("update_notebook", history) }.isFailure)
        assertEquals(NotebookDestination.UNSPECIFIED, NotebookIntent.destination(listOf(user("把这段存到笔记本"))))
        assertEquals(NotebookDestination.UNSPECIFIED, NotebookIntent.destination(listOf(user("不用新建笔记，只要给我格式"))))
    }
    @Test fun userChoiceInAFollowupGrantsOnlyTheChosenDestination() {
        val history = listOf(user("帮我做个笔记"), Message(sessionId = "s", role = "assistant", text = "新建，还是写到已有笔记？"), user("新建"))
        NotebookIntent.requireDestination("create_notebook", history)
        assertTrue(runCatching { NotebookIntent.requireDestination("update_notebook", history) }.isFailure)
        val existing = listOf(user("请更新已有的服务器笔记"))
        NotebookIntent.requireDestination("update_notebook", existing)
        assertTrue(runCatching { NotebookIntent.requireDestination("create_notebook", existing) }.isFailure)
    }
    @Test fun modelOrToolTextCannotInventTheUsersNotebookChoice() {
        val history = listOf(user("帮我做笔记"), Message(sessionId = "s", role = "assistant", text = "我会新建笔记"), Message(sessionId = "s", role = "tool", text = "创建新的笔记"))
        assertEquals(NotebookDestination.UNSPECIFIED, NotebookIntent.destination(history))
    }
    @Test fun creatingANoteInAnExistingFolderIsCreationAndALaterCorrectionWins() {
        assertEquals(NotebookDestination.NEW, NotebookIntent.destination(listOf(user("在已有的工作文件夹里新建一份笔记"))))
        assertEquals(NotebookDestination.EXISTING, NotebookIntent.destination(listOf(user("创建一份笔记"), user("还是更新已有的吧"))))
    }
    @Test fun notebookWritesObeyPlanModeAndExplicitToolRules() {
        for (name in listOf("create_notebook", "update_notebook", "organize_notebooks")) {
            assertFalse(PermissionPolicy.available(Access.PLAN, name))
            assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.FULL, name, rule = "deny"))
            assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.FULL, name, rule = "ask"))
            assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.ASK, name))
        }
    }
}
