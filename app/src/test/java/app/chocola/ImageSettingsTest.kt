package app.chocola

import app.chocola.api.ImageRequest
import app.chocola.agent.PermissionDecision
import app.chocola.agent.PermissionPolicy
import app.chocola.data.*
import app.chocola.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageSettingsTest {
    private val image = Provider(model = "gpt-image-2.5-sunburst", protocol = Protocol.RESPONSES)
    @Test fun imageEndpointsReplaceExistingTextAndImageEndpointSuffixes() {
        listOf("", "/responses/", "/chat/completions", "/images/generations", "/images/edits").forEach { suffix ->
            val provider = image.copy(baseUrl = "https://proxy.example/v1$suffix")
            assertEquals("https://proxy.example/v1/images/generations", ImageRequest.endpoint(provider, false))
            assertEquals("https://proxy.example/v1/images/edits", ImageRequest.endpoint(provider, true))
        }
    }
    @Test fun gpt25KeepsItsActualModelAndUsesImageParametersWithoutTextOrLegacyFields() {
        val body = ImageRequest.body(image, ImageSettings(quality = "max", size = "3840x2160", format = "jpeg", compression = 78, inputFidelity = "high"), "画一只鹈鹕")
        assertEquals(image.model, body.getString("model")); assertEquals("max", body.getString("quality")); assertEquals(78, body.getInt("output_compression"))
        listOf("max_tokens", "max_output_tokens", "temperature", "reasoning", "reasoning_effort", "input_fidelity", "response_format", "stream").forEach { assertFalse(it, body.has(it)) }
    }
    @Test fun parametersRespectFormatAndImageModelCapabilities() {
        val old = image.copy(model = "gpt-image-1.5")
        val png = ImageRequest.body(old, ImageSettings(inputFidelity = "high"), "edit", edit = true)
        assertEquals("high", png.getString("input_fidelity")); assertFalse(png.has("output_compression"))
        val modern = ImageRequest.body(image, ImageSettings(inputFidelity = "high"), "edit", edit = true)
        assertFalse(modern.has("input_fidelity"))
        val dalle = ImageRequest.body(image.copy(model = "dall-e-3"), ImageSettings(quality = "hd", size = "1792x1024"), "draw")
        assertEquals("b64_json", dalle.getString("response_format")); assertFalse(dalle.has("output_format")); assertFalse(dalle.has("background"))
    }
    @Test fun invalidSizesAndUnsupportedParameterCombinationsFailBeforeTheNetwork() {
        listOf("1023x1024", "4096x2048", "1024x256", "16x16", "8192x8192").forEach {
            assertTrue(it, runCatching { ImageModels.validate(ImageSettings(model = image.model, size = it)) }.isFailure)
        }
        assertTrue(runCatching { ImageModels.validate(ImageSettings(model = image.model, background = "transparent", format = "jpeg")) }.isFailure)
        assertTrue(runCatching { ImageModels.validate(ImageSettings(model = "gpt-image-2", quality = "max")) }.isFailure)
        assertTrue(runCatching { ImageModels.validate(ImageSettings(model = "dall-e-3", count = 2)) }.isSuccess)
        assertTrue(runCatching { ImageModels.validate(ImageSettings(extraBody = "{\"model\":\"different\"}")) }.isFailure)
    }
    @Test fun responsesTransportSeparatesTheRoutingModelFromTheImageModel() {
        val body = ImageRequest.responseBody(image, ImageSettings(quality = "xhigh", count = 3), "绘图", "gpt-6.1-sol", false)
        assertEquals("gpt-6.1-sol", body.getString("model")); val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals(image.model, tool.getString("model")); assertEquals("generate", tool.getString("action")); assertFalse(tool.has("n"))
        assertTrue(runCatching { ImageRequest.responseBody(image, ImageSettings(), "p", image.model, false) }.isFailure)
    }
    @Test fun defaultsProfilesAndImageDimensionsRoundTripAndOldDataStillLoads() {
        val config = ImageSettings(model = image.model, quality = "max", format = "webp", compression = 85, count = 2, size = "1536x864")
        assertEquals(config, Preferences.from(Preferences(image = config).json()).image)
        assertEquals(config, Provider.from(image.copy(image = config).json()).image)
        assertNull(Provider.from(image.json().apply { remove("image") }).image)
        assertEquals(ImageSettings(), Preferences.from(JSONObject()).image)
        val attachment = Attachment("a.png", "/workspace/a.png", "image/png", 16, 1024, 1536)
        assertEquals(attachment, Attachment.from(attachment.json()))
        assertEquals(0, Attachment.from(attachment.json().apply { remove("width"); remove("height") }).width)
    }
    @Test fun independentVendorSettingsDoNotLeakAcrossAccountsAndDirectSelectionWins() {
        val chatVendor = ApiVendor(id = "chat", baseUrl = "https://chat.example/v1", apiKey = "chat-key")
        val imageVendor = ApiVendor(id = "images", baseUrl = "https://images.example/v1", apiKey = "image-key")
        val chat = chatVendor.model("gpt-6.1-sol")
        val drawing = imageVendor.model(image.model).copy(image = ImageSettings(quality = "max"))
        val state = AppState(providers = listOf(chat, drawing), vendors = listOf(chatVendor, imageVendor), preferences = Preferences(image = ImageSettings(vendorId = imageVendor.id, model = drawing.model)))
        val (resolved, parameters) = ImageModels.resolve(state, chat)
        assertEquals("image-key", resolved.apiKey); assertEquals(drawing.model, resolved.model); assertEquals("max", parameters.quality)
        val direct = chat.copy(model = "gpt-image-1.5")
        assertEquals("chat-key", ImageModels.resolve(state, direct, direct = true).first.apiKey)
        assertEquals("auto", ImageModels.resolve(state, direct, direct = true).second.quality)
        assertFalse(ImageModels.sameAccount(chat.copy(vendorId = ""), drawing.copy(vendorId = "")))
    }
    @Test fun actualImagesAreVisibleOutsideCollapsedOperationsAndHaveStableTimelineKeys() {
        val image = Attachment("a.png", "/workspace/generated/a.png", "image/png", 64)
        val assistant = Message(id = "reply", sessionId = "s", role = "assistant", attachments = listOf(image))
        assertEquals(listOf("reply:images", "reply:end"), TimelineProjector().build(listOf(assistant), emptySet(), emptySet()).map { it.key })
        val call = Message(id = "call", sessionId = "s", role = "assistant", calls = listOf(ToolCall("draw", "generate_image", "{}")))
        val tool = Message(id = "tool", sessionId = "s", role = "tool", toolCallId = "draw", attachments = listOf(image))
        val collapsed = TimelineProjector().build(listOf(call, tool), emptySet(), emptySet())
        assertTrue(collapsed.first() is OperationsHeader); assertEquals("tool:images", collapsed.last().key)
    }
    @Test fun imagePermissionObeysExplicitRulesAndPlanningRestrictions() {
        assertEquals(PermissionDecision.ALLOW, PermissionPolicy.decision(Access.CHAT, "generate_image"))
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.PLAN, "generate_image", rule = "allow"))
        assertEquals(PermissionDecision.ASK, PermissionPolicy.decision(Access.CHAT, "generate_image", rule = "ask"))
        assertEquals(PermissionDecision.DENY, PermissionPolicy.decision(Access.FULL, "generate_image", rule = "deny"))
    }
}
