package app.chocola

import app.chocola.agent.PermissionPolicy
import app.chocola.agent.PermissionDecision
import app.chocola.agent.Tools
import app.chocola.agent.WorkspacePaths
import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class PermissionTest {
    @Test fun chatAllowsConversationToolsWithoutGrantingShellOrNetworkExecution() {
        PermissionPolicy.knownTools.forEach { assertEquals(it in PermissionPolicy.conversationTools, PermissionPolicy.allowed(Access.CHAT, it, true)) }
        assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.CHAT, "save_schedule", rule = "ask"))
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.CHAT, "save_schedule", rule = "deny"))
        PermissionPolicy.scheduleWrites.forEach { assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.PLAN, it, rule = "allow")) }
    }
    @Test fun workspaceDoesNotPretendShellOrNetworkAreContained() {
        assertTrue(PermissionPolicy.allowed(Access.WORKSPACE, "write_file"))
        assertTrue(PermissionPolicy.allowed(Access.WORKSPACE, "edit_file"))
        listOf("run_shell", "start_process", "http_request", "ssh_execute").forEach { assertFalse(PermissionPolicy.allowed(Access.WORKSPACE, it)) }
    }
    @Test fun unknownToolsCannotBypassFullAccess() { assertFalse(PermissionPolicy.allowed(Access.FULL, "invented_tool")) }
    @Test fun persistentCommandsRequireApprovalAndAreAdvertisedToTheModel() {
        assertFalse(PermissionPolicy.allowed(Access.ASK, "start_process"))
        assertTrue(PermissionPolicy.allowed(Access.FULL, "start_process"))
        assertTrue(PermissionPolicy.allowed(Access.ASK, "read_process"))
        assertTrue(PermissionPolicy.allowed(Access.WORKSPACE, "stop_process"))
        val schemas = Tools.schemas().objects().map { it.getString("name") }.toSet()
        assertEquals(PermissionPolicy.knownTools, schemas)
    }
    @Test fun autoMemorySwitchChangesRememberPermission() { assertFalse(PermissionPolicy.allowed(Access.ASK, "remember")); assertTrue(PermissionPolicy.allowed(Access.ASK, "remember", true)) }
    @Test fun explicitDenyAndAskRulesRemainEffectiveInBypassMode() {
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.FULL, "run_shell", rule = "deny"))
        assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.FULL, "read_file", rule = "ask"))
    }
    @Test fun planModeCannotBeMadeWritableByAStoredAllowRule() {
        listOf("run_shell", "start_process", "ssh_execute", "write_file", "edit_file", "remember", "stop_process").forEach {
            assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.PLAN, it, autoMemory = true, rule = "allow"))
            assertFalse(PermissionPolicy.available(Access.PLAN, it))
        }
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.PLAN, "http_request", rule = "allow", arguments = "{\"method\":\"POST\"}"))
        assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.PLAN, "http_request", arguments = "{\"method\":\"GET\"}"))
        assertEquals(PermissionDecision.ALLOW, PermissionPolicy.decision(Access.PLAN, "read_file"))
    }
    @Test fun pathsRejectTraversalAndAbsoluteHostPaths() {
        val root = Files.createTempDirectory("chocola-workspace").toFile()
        try {
            val paths = WorkspacePaths(root)
            assertEquals(root.resolve("file.txt"), paths.resolve("/workspace/file.txt"))
            for (path in listOf("../private", "/data/private", "/workspace/../../private")) assertTrue(runCatching { paths.resolve(path) }.isFailure)
        } finally { root.deleteRecursively() }
    }
    @Test fun symlinkCannotEscapeWorkspace() {
        val root = Files.createTempDirectory("chocola-links").toFile()
        val outside = Files.createTempDirectory("chocola-outside").toFile()
        try {
            Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
            assertTrue(runCatching { WorkspacePaths(root).resolve("link/secret") }.isFailure)
        } finally { root.resolve("link").delete(); root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun secretMemoryNeverIncludesPlaintextInModelContext() {
        val memory = Memory(name = "GitHub", fields = listOf(MemoryField("username", "person"), MemoryField("token", "super-secret-value", true)))
        val visible = Tools.visibleMemory(memory).toString()
        assertFalse(visible.contains("super-secret-value")); assertTrue(visible.contains("person")); assertTrue(visible.contains("stored securely"))
    }
    @Test fun shellQuotingPreservesLiteralSubstitutionCharacters() {
        assertEquals("'a'\\''b\$(id)'", Tools.shellQuote("a'b\$(id)"))
    }
    @Test fun logsAndToolResultsRedactKnownAndRecognizableCredentials() {
        Redactor.register("a-private-password-123")
        val value = Redactor.clean("Authorization: Bearer sk-abcdefgh123456 password=a-private-password-123 token=abcdefg")
        assertFalse(value.contains("sk-abcdefgh123456")); assertFalse(value.contains("a-private-password-123")); assertFalse(value.contains("abcdefg"))
    }
}
