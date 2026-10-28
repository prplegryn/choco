package app.chocola

import app.chocola.agent.*
import app.chocola.data.*
import app.chocola.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExperienceRulesTest {
    @Test fun independentTaskKeepsItsOwnModelPermissionsAndCredentialGrants() {
        val provider = ModelCatalog.models.first { it.model == "gpt-6.1-sol" }.apply(Provider(id = "model"))
        val source = ThinkingControls.choose(Session(id = "source", providerId = provider.id, access = Access.FULL), provider, "high")
        val task = ScheduledTask(id = "task", title = "每日汇报", prompt = "检查服务器").withModel(source, provider)
            .copy(sessionId = "", allowedTools = setOf("ssh_execute"), credentialIds = setOf("server"), authorizationVersion = 1)
        assertEquals(task, ScheduledTask.from(task.json()))
        assertEquals("", task.sessionId)
        assertEquals("model", task.providerId)
        assertEquals("gpt-6.1-sol", task.modelSnapshot)
        assertEquals("high", task.reasoningLevel)
        assertEquals(Access.FULL, task.access)
        assertEquals(setOf("server"), task.credentialIds)
    }
    @Test fun eachOccurrenceGetsANewReportAndOverlapsKeepTheActiveDestination() {
        val task = ScheduledTask(id = "daily", title = "服务器日报", prompt = "检查", startAt = 1000, frequency = "interval")
        val first = ScheduleTimes.claim(task, 1000, "running", "one")
        assertEquals(SessionIds.scheduled("daily", "one"), first.resultSessionId)
        val overlap = ScheduleTimes.claim(first, first.nextAt, "busy", "ignored")
        assertEquals(first.resultSessionId, overlap.resultSessionId)
        val completed = ScheduleTimes.complete(overlap, "one", "done")
        assertEquals(first.resultSessionId, completed.lastResultSessionId)
        assertEquals("", completed.activeSessionId)
        val next = ScheduleTimes.claim(completed, completed.nextAt, "running", "two")
        assertNotEquals(first.resultSessionId, next.resultSessionId)
        assertEquals(task.title, next.title)
    }
    @Test fun credentialConsentDoesNotBroadenGlobalScopeOrUnlockOtherCredentials() {
        val memory = Memory(id = "server", kind = "服务器", scope = "source", toolsAllowed = false,
            fields = listOf(MemoryField("host", "example.com"), MemoryField("password", "secret", true)))
        val report = Session(id = "report", scheduledTaskId = "task", taskCredentials = setOf(memory.id))
        assertTrue(MemoryAccess.available(memory, report))
        assertTrue(MemoryAccess.toolAllowed(memory, report))
        assertFalse(MemoryAccess.available(memory, Session(id = "other")))
        assertFalse(MemoryAccess.toolAllowed(memory, Session(id = "source")))
        assertFalse(MemoryAccess.available(memory.copy(scope = "", toolsAllowed = true), report.copy(taskCredentials = emptySet())))
        assertFalse(MemoryAccess.available(memory.copy(enabled = false), report))
        assertEquals("source", memory.scope)
        assertFalse(memory.toolsAllowed)
    }
    @Test fun reviewedTaskGrantsRemainLocalAndCannotCreateRecursiveSchedules() {
        val session = Session(access = Access.ASK, scheduledTaskId = "task", taskTools = setOf("ssh_execute", "save_schedule", "start_process"))
        val preferences = Preferences(toolRules = mapOf("ssh_execute" to "deny", "http_request" to "ask"))
        assertTrue(PermissionPolicy.scheduled(session, ToolCall("1", "ssh_execute", "{}"), preferences))
        assertFalse(PermissionPolicy.scheduled(session, ToolCall("2", "http_request", "{}"), preferences))
        assertFalse(PermissionPolicy.scheduled(session, ToolCall("3", "save_schedule", "{}"), preferences))
        assertFalse(PermissionPolicy.scheduled(session, ToolCall("4", "start_process", "{}"), preferences))
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.FULL, "ssh_execute", rule = preferences.toolRules.getValue("ssh_execute")))
    }
    @Test fun preflightValidatesCredentialIdsToolsAndMissingServerFields() {
        val server = Memory(id = "server", name = "我的服务器", kind = "服务器", fields = listOf(MemoryField("host", "example.com")))
        val requirements = ScheduleRequirements.from("""{"tools":["ssh_execute"],"credential_ids":["server"],"issues":[]}""", listOf(server))
        assertTrue(requirements.needsCredential)
        assertEquals("服务器", requirements.credentialKind)
        assertEquals(1, ScheduleRequirements.missing(server).size)
        assertTrue(ScheduleRequirements.missing(server.copy(fields = server.fields + MemoryField("private_key", "private", true))).isEmpty())
        assertTrue(runCatching { ScheduleRequirements.from("""{"tools":["made_up"],"credential_ids":[]}""", listOf(server)) }.isFailure)
        assertTrue(runCatching { ScheduleRequirements.from("""{"tools":[],"credential_ids":["unknown"]}""", listOf(server)) }.isFailure)
        val choose = ScheduleRequirements.from("""{"tools":["ssh_execute"],"credential_ids":[],"needs_credential":true,"credential_kind":"服务器"}""", emptyList())
        assertTrue(choose.needsCredential)
        assertTrue(choose.credentials.isEmpty())
    }
    @Test fun titlesOnlyAttemptAtTwoAndFiveAndLockAfterSuccessOrManualRename() {
        assertNull(SessionTitles.attempt("auto", 1, 0))
        assertEquals(2, SessionTitles.attempt("auto", 2, 0))
        assertNull(SessionTitles.attempt("auto", 3, 2))
        assertNull(SessionTitles.attempt("auto", 4, 2))
        assertEquals(5, SessionTitles.attempt("auto", 5, 2))
        assertNull(SessionTitles.attempt("auto", 50, 5))
        assertNull(SessionTitles.attempt("auto", 5, 2, locked = true))
        assertNull(SessionTitles.attempt("manual", 5, 0))
        assertFalse(SessionTitles.usable("待定"))
        assertTrue(SessionTitles.usable("服务器带宽日报"))
        val session = Session(title = "自己的标题", titleMode = "manual", titleLocked = true, titledTurns = 2)
        assertEquals(session, Session.from(session.json()))
    }
    @Test fun automaticTaskInstructionStaysInModelHistoryButNeverBecomesABubble() {
        val trigger = Message(sessionId = "report", role = "user", text = "内部执行指令", hidden = true)
        assertEquals(trigger, Message.from(trigger.json()))
        assertEquals("user", trigger.json().getString("role"))
        val report = Message(sessionId = "report", role = "assistant", text = "服务器运行正常。")
        val view = TimelineProjector().build(listOf(trigger, report), emptySet(), emptySet())
        assertTrue(view.none { it is UserItem })
        assertEquals("服务器运行正常。", view.filterIsInstance<TextPiece>().single().block.text)
    }
    @Test fun modelWaitingAndToolStatusHaveSeparateDisplayStates() {
        val user = Message(sessionId = "s", role = "user", text = "检查服务器")
        assertTrue(ChatActivity.project(listOf(user), true).thinking)
        assertEquals("", ChatActivity.project(listOf(user), true).operation)
        val call = ToolCall("call", "ssh_execute", """{"description":"检查带宽负载"}""")
        val assistant = Message(sessionId = "s", role = "assistant", calls = listOf(call))
        val tool = Message(sessionId = "s", role = "tool", toolName = "ssh_execute", toolCallId = "call", status = "running")
        val active = ChatActivity.project(listOf(user, assistant, tool), true)
        assertEquals("检查带宽负载", active.operation)
        assertFalse(active.thinking)
        assertTrue(ChatActivity.project(listOf(user, assistant, tool.copy(status = "done")), true).thinking)
        assertFalse(ChatActivity.project(listOf(user, Message(sessionId = "s", role = "assistant", text = "正文", status = "streaming")), true).thinking)
        assertEquals(ChatActivity(), ChatActivity.project(listOf(user, assistant, tool), false))
    }
    @Test fun reasoningRestoresBothModelsAndCanSeedTheNextConversation() {
        val p = ModelCatalog.models.first { it.model == "gpt-6.1-sol" }.apply(Provider(id = "one"))
        val q = p.copy(id = "two")
        val session = ThinkingControls.choose(ThinkingControls.choose(Session(), p, "high"), q, "low")
        assertEquals("high", ThinkingControls.selected(p, Session.from(session.json())))
        assertEquals("low", ThinkingControls.selected(q, Session.from(session.json())))
        val prefs = Preferences(reasoningDefaults = session.reasoningSelections)
        val next = Session(reasoningSelections = Preferences.from(prefs.json()).reasoningDefaults)
        assertEquals("high", ThinkingControls.selected(p, next))
        assertEquals("low", ThinkingControls.selected(q, next))
    }
    @Test fun newMotionDefaultsMigrateOldDefaultsButKeepExplicitNewChoices() {
        val old = JSONObject().put("textDuration", 240).put("widthDuration", 360)
        assertEquals(600, PillDesign.from(old).textDuration)
        assertEquals(560, PillDesign.from(old).widthDuration)
        val explicit = PillDesign(textDuration = 240, widthDuration = 360, style = "cosmos")
        assertEquals(explicit, PillDesign.from(explicit.json()))
        val floating = FloatingDesign("glass", 6f, 18f, .4f)
        assertEquals(floating, FloatingDesign.from(floating.json()))
        assertEquals(floating, Preferences.from(Preferences(floating = floating).json()).floating)
    }
}
