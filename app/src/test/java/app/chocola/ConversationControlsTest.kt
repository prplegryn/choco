package app.chocola

import app.chocola.data.*
import app.chocola.ui.*
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ConversationControlsTest {
    private fun provider(model: String) = ModelCatalog.models.first { it.model == model }.apply(Provider(id = "connection"))
    @Test fun reasoningIsStoredPerConversationAndDoesNotMutateTheSharedConnection() {
        val p = provider("gpt-6.1-sol")
        val session = Session(providerId = p.id, reasoningLevel = "high", reasoningModel = ThinkingControls.modelKey(p))
        val restored = Session.from(session.json())
        assertEquals(session, restored)
        val adjusted = ThinkingControls.effective(p, restored)
        assertEquals("high", ModelCatalog.parameters(adjusted).getJSONObject("reasoning").getString("effort"))
        assertEquals("medium", p.reasoningEffort)
        assertEquals(p, ThinkingControls.effective(p, Session(providerId = p.id)))
        assertEquals(adjusted, AppState(currentId = restored.id, sessions = listOf(restored), providers = listOf(p)).currentProvider)
    }
    @Test fun switchingModelOrConnectionNeverReusesAnIncompatibleDialSetting() {
        val p = provider("gpt-6.1-sol")
        val s = Session(reasoningLevel = "max", reasoningModel = ThinkingControls.modelKey(p))
        assertEquals("max", ThinkingControls.effective(p, s).reasoningEffort)
        val changed = p.copy(model = "other-model")
        assertEquals(changed, ThinkingControls.effective(changed, s))
        assertEquals(p.copy(id = "other"), ThinkingControls.effective(p.copy(id = "other"), s))
        assertEquals(p, ThinkingControls.effective(p, s.copy(reasoningLevel = "invented")))
    }
    @Test fun dialOnlyOffersSupportedChoicesAndUsesTheNativeThinkingParameter() {
        val claude = provider("claude-opus-5-5")
        assertFalse(ThinkingControls.options(claude).any { it.key in setOf("off", "none") })
        val qwen = provider("qwen3.8-max")
        assertEquals(listOf("", "off", "on"), ThinkingControls.options(qwen).map { it.key })
        val session = Session(reasoningLevel = "off", reasoningModel = ThinkingControls.modelKey(qwen))
        assertFalse(ModelCatalog.parameters(ThinkingControls.effective(qwen, session)).getBoolean("enable_thinking"))
        assertEquals(listOf(""), ThinkingControls.options(Provider(model = "custom", family = "custom")).map { it.key })
    }
    @Test fun explicitDialCannotBeUndoneByConflictingExtraParameters() {
        val p = provider("gpt-6.1-sol").copy(extraBody = """{"reasoning":{"effort":"low"},"service_tier":"priority"}""")
        val s = Session(reasoningLevel = "high", reasoningModel = ThinkingControls.modelKey(p))
        val body = ModelCatalog.parameters(ThinkingControls.effective(p, s))
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("priority", body.getString("service_tier"))
    }
    @Test fun taskPanelFixesItsTopAndRightWhileThinkingPanelStaysCentered() {
        val viewport = Rect(0f, 24f, 400f, 800f)
        val chip = Rect(270f, 100f, 386f, 140f)
        val tasks = PanelBounds.target(chip, viewport, 318f, 342f, 12f, PanelGrowth.TASKS, 5f)
        assertEquals(chip.top, tasks.top, 0f); assertEquals(chip.right, tasks.right, 0f)
        assertTrue(tasks.left < chip.left); assertTrue(tasks.bottom > chip.bottom)
        val button = Rect(180f, 700f, 220f, 740f)
        val thinking = PanelBounds.target(button, viewport, 304f, 294f, 12f, PanelGrowth.CENTERED, 5f)
        assertEquals(button.center.x, thinking.center.x, .01f)
        assertEquals(button.bottom + 5f, thinking.bottom, 0f)
        assertTrue(thinking.top < button.top)
        val menu = PanelBounds.target(Rect(330f, 40f, 378f, 88f), viewport, 288f, 350f, 12f, PanelGrowth.MENU, 5f)
        assertEquals(383f, menu.right, 0f); assertEquals(35f, menu.top, 0f)
    }
    @Test fun historySeparatesExecutingConversationsFromSurvivingBackgroundPrograms() {
        val today = LocalDate.of(2026, 10, 24); val zone = ZoneId.of("Asia/Shanghai")
        val yesterday = Session(id = "one", updated = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals("昨天", SessionHistory.section(yesterday, today, zone))
        assertEquals("已置顶", SessionHistory.section(yesterday.copy(pinned = true), today, zone))
        val state = AppState(tasks = mapOf("other" to "回复中"), processes = listOf(
            SandboxProcess("a", "one", "server"), SandboxProcess("b", "one", "done", "exited"), SandboxProcess("c", "other", "other")))
        assertEquals(listOf("a"), SessionHistory.programs(state, "one").map { it.id })
        assertFalse(state.tasks.containsKey("one"))
    }
}
