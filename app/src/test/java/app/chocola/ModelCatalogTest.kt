package app.chocola

import app.chocola.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun deepseekThinkingDoesNotPretendTemperatureCanControlIt() {
        val p = ModelCatalog.models.first { it.model == "deepseek-flash" }.apply(Provider()).copy(temperature = .2, topP = .98)
        val body = ModelCatalog.parameters(p)
        assertFalse(body.has("temperature")); assertEquals(.98, body.getDouble("top_p"), 0.0)
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("high", body.getString("reasoning_effort"))
    }
    @Test fun deepseekFlashAndProHaveDifferentValidSamplingOptions() {
        val flash = ModelCatalog.models.first { it.model == "deepseek-flash" }.apply(Provider())
        val pro = ModelCatalog.models.first { it.model == "deepseek-v4-pro" }.apply(Provider())
        assertTrue(runCatching { ModelCatalog.parameters(flash.copy(topP = .5)) }.isFailure)
        assertFalse(ModelCatalog.parameters(pro.copy(topP = .5)).has("top_p"))
        assertFalse(ModelCatalog.supportsTopP(flash.copy(thinking = "disabled")))
        assertTrue(ModelCatalog.supportsTopP(pro.copy(thinking = "disabled")))
    }
    @Test fun opusCannotAccidentallyReceiveLegacyThinkingControls() {
        val p = ModelCatalog.models.first { it.model == "claude-opus-5-5" }.apply(Provider())
        assertTrue(runCatching { ModelCatalog.parameters(p.copy(thinking = "disabled")) }.isFailure)
        assertTrue(runCatching { ModelCatalog.parameters(p.copy(thinking = "enabled", thinkingBudget = 4096)) }.isFailure)
        assertFalse(ModelCatalog.parameters(p.copy(temperature = .5)).has("temperature"))
    }
    @Test fun responsesReceivesItsOwnReasoningSchema() {
        val p = ModelCatalog.models.first { it.model == "gpt-6.1-sol" }.apply(Provider())
        val body = ModelCatalog.parameters(p)
        assertEquals(Protocol.RESPONSES, p.protocol)
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertFalse(body.has("reasoning_effort"))
    }
    @Test fun compactionUsesAnIndependentBudgetAndAvoidsUnsupportedThinkingOff() {
        val p = ModelCatalog.models.first { it.family == "glm" }.apply(Provider(maxTokens = 2048))
        val compact = ModelCatalog.auxiliary(p)
        assertTrue(compact.maxTokens > 2048)
        assertNotEquals("disabled", compact.thinking)
        assertEquals("low", ModelCatalog.parameters(compact).getString("reasoning_effort"))
    }
    @Test fun qwenUsesItsNativeThinkingFlagAndBudget() {
        val p = ModelCatalog.models.first { it.family == "qwen" }.apply(Provider()).copy(thinkingBudget = 4096)
        val body = ModelCatalog.parameters(p)
        assertTrue(body.getBoolean("enable_thinking")); assertEquals(4096, body.getInt("thinking_budget"))
    }
    @Test fun auxiliaryCallsDoNotInjectUnknownParametersIntoCustomModels() {
        val p = ModelCatalog.auxiliary(Provider(model = "my-model", family = "custom"))
        assertEquals(0, ModelCatalog.parameters(p).length())
    }
    @Test fun sonnetAuxiliaryCallsUseBetweenToolsInsteadOfAnInvalidDisabledSetting() {
        val p = ModelCatalog.auxiliary(ModelCatalog.models.first { it.model == "claude-sonnet-5-5" }.apply(Provider()))
        val body = ModelCatalog.parameters(p)
        assertEquals("between_tools", body.getJSONObject("thinking").getString("type"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
    }
    @Test fun extraParametersCannotReplaceTheAgentTranscriptOrTools() {
        assertTrue(runCatching { ModelCatalog.parameters(Provider(extraBody = """{"messages":[]}""")) }.isFailure)
        assertEquals("priority", ModelCatalog.parameters(Provider(extraBody = """{"service_tier":"priority"}""")).getString("service_tier"))
    }
    @Test fun modelSettingsAndOldPermissionIdentifiersSurviveStorageRoundTrips() {
        val p = Provider(model = "deepseek-flash", family = "deepseek", thinking = "enabled", reasoningEffort = "high", contextWindow = 1_000_000, summaryTokens = 16384, extraBody = "{\"seed\":1}")
        assertEquals(p, Provider.from(p.json()))
        assertEquals(Access.FULL, Session.from(Session(access = Access.FULL).json()).access)
        assertEquals("跳过确认", Access.FULL.label)
    }
}
