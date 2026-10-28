package app.chocola

import app.chocola.data.*
import app.chocola.agent.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CapabilitiesTest {
    @Test fun standardYamlMetadataAndOptionalFieldsSurviveEditing() {
        val document = "---\nname: server-report\ndescription: >-\n  Check a server\n  and report its load\nlicense: MIT\nmetadata:\n  author: somebody\n---\n\nRead the metrics."
        val skill = SkillFormat.parse(document)
        assertEquals("server-report", skill.name)
        assertEquals("Check a server and report its load", skill.description)
        val edited = SkillFormat.update(document, "New purpose", "New instructions")
        assertTrue(edited.contains("MIT")); assertTrue(edited.contains("somebody"))
        assertEquals("New purpose", SkillFormat.parse(edited).description)
    }
    @Test fun skillYamlCannotConstructJavaObjectsOrSilentlyReplaceDuplicateKeys() {
        listOf(
            "---\nname: example\ndescription: !!java.net.URL [https://example.com]\n---\nText",
            "---\nname: example\nname: duplicate\ndescription: Work\n---\nText",
            "---\nname: ../escape\ndescription: Work\n---\nText"
        ).forEach { assertTrue(runCatching { SkillFormat.parse(it) }.isFailure) }
    }
    @Test fun packageImportRejectsTraversalAndDuplicatePathsBeforeExecution() {
        val root = Files.createTempDirectory("skill-archive").toFile()
        try {
            listOf("../outside", "/absolute", "C:\\outside", "a/../../outside").forEach { name ->
                val data = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip -> zip.putNextEntry(ZipEntry(name)); zip.write("data".toByteArray()); zip.closeEntry() } }.toByteArray()
                assertTrue(runCatching { ArchivePaths.unpack(ByteArrayInputStream(data), root) }.isFailure)
            }
        } finally { RestoreFiles.deleteTree(root) }
    }
    @Test fun accountsUseVendorCredentialsAndModelsKeepTheirRealNames() {
        val first = Provider(baseUrl = "https://api.deepseek.com/v1", apiKey = "one", model = "deepseek-chat", name = "old alias")
        val second = first.copy(id = newId(), model = "deepseek-reasoner")
        val third = first.copy(id = newId(), apiKey = "two")
        val accounts = ModelAccounts.group(listOf(first, second, third))
        assertEquals(2, accounts.size); assertTrue(accounts.any { it.models.size == 2 })
        assertEquals("DeepSeek", accounts.first().brand)
        assertEquals("deepseek-chat", first.label)
        assertFalse(accounts.any { it.id.contains("one") })
    }
    @Test fun accountBalanceAndKeyBudgetAreLabelledSeparately() {
        val balance = BalanceFormats.deepseek(JSONObject("""{"balance_infos":[{"currency":"CNY","total_balance":"110.02"}]}"""))
        assertEquals("¥110.02", balance.text); assertEquals("账户余额", balance.detail)
        val budget = BalanceFormats.openrouter(JSONObject("""{"data":{"limit_remaining":17.5}}"""))
        assertEquals("$17.50", budget.text); assertEquals("密钥可用额度", budget.detail)
        assertEquals("未设额度", BalanceFormats.openrouter(JSONObject("""{"data":{"limit_remaining":null}}""")).text)
    }
    @Test fun mcpToolGrantsAreScopedToExactServiceAndSchema() {
        val server = McpServer(name = "server", url = "https://example.com/mcp", token = "credential")
        val tool = JSONObject("""{"name":"weather","inputSchema":{"type":"object","properties":{}}}""")
        val alias = McpNames.alias(server, tool)
        assertTrue(McpNames.isTool(alias))
        assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.ASK, alias))
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.PLAN, alias, rule = "allow"))
        assertEquals(PermissionDecision.ALLOW, PermissionPolicy.decision(Access.FULL, alias))
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.FULL, alias, rule = "deny"))
        assertNotEquals(alias, McpNames.alias(server.copy(token = "different"), tool))
        assertNotEquals(alias, McpNames.alias(server, JSONObject(tool.toString()).put("description", "changed")))
        val reordered = JSONObject("""{"inputSchema":{"properties":{},"type":"object"},"name":"weather"}""")
        assertEquals(alias, McpNames.alias(server, reordered))
        val task = Session(access = Access.ASK, scheduledTaskId = "task", taskTools = setOf(alias))
        assertTrue(PermissionPolicy.scheduled(task, ToolCall("c", alias, "{}"), Preferences()))
        assertFalse(PermissionPolicy.scheduled(task, ToolCall("c", McpNames.alias(server.copy(token = "different"), tool), "{}"), Preferences()))
    }
    @Test fun mcpPurposeIsClientMetadataAndHumanNamesSurviveHistoryReload() {
        val call = ToolCall("call", "mcp_" + "a".repeat(40), """{"path":"/docs","_chocola_step_description":"读取文档"}""",
            displayName = "文档服务 / 读取", remoteName = "read", descriptionKey = "_chocola_step_description")
        val saved = ToolCall.from(call.json())
        assertEquals("读取文档", saved.purpose())
        assertEquals("文档服务 / 读取", saved.displayName)
        assertEquals("/docs", saved.executionArguments().getString("path"))
        assertFalse(saved.executionArguments().has("_chocola_step_description"))
    }
    @Test fun defaultsAndPreferencesHaveIndependentMigration() {
        assertEquals("glass", FloatingDesign().style); assertEquals(.8f, FloatingDesign().white, 0f)
        assertEquals("glass", FloatingDesign.from(JSONObject("""{"style":"white","white":0.38}""")).style)
        assertEquals("white", FloatingDesign.from(FloatingDesign(style = "white").json()).style)
        assertFalse(Preferences().setupCompleted)
        assertTrue(Preferences.from(JSONObject()).setupCompleted)
        val reply = ReplyPreferences(tone = "professional", warmth = 2, enthusiasm = 0, detail = 2, structure = 0, emoji = 0)
        assertEquals(reply, Preferences.from(Preferences(reply = reply).json()).reply)
        assertTrue(reply.prompt().contains("不使用表情")); assertTrue(reply.prompt().contains("专业"))
        assertEquals(100, Preferences.from(JSONObject().put("maxSteps", 24)).maxSteps)
    }
}
