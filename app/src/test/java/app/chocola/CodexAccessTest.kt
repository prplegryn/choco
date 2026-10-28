package app.chocola

import app.chocola.api.*
import app.chocola.data.*
import app.chocola.data.Protocol
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CodexAccessTest {
    private fun jwt(value: JSONObject): String = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().toByteArray()) + ".signature"
    private fun tokens(account: String = "account-a", refresh: String = "refresh-test"): JSONObject {
        val claims = JSONObject().put("email", "person@example.test").put("exp", 2_000_000_000).put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", account).put("chatgpt_plan_type", "plus"))
        return JSONObject().put("access_token", jwt(claims)).put("id_token", jwt(claims)).put("refresh_token", refresh).put("expires_in", 3600)
    }
    private fun account(id: String = "a") = CodexCredential(id, "account-$id", "$id@example.test", "plus", "test-access-$id", "test-refresh-$id", "test-id-$id", Long.MAX_VALUE,
        models = listOf(CodexModel("gpt-6-astra", 272000, listOf("low", "high"), "high")))

    @Test fun deviceLoginExchangesPkceOnlyAfterAuthorizationAndDoesNotNeedALocalServer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"device_auth_id":"device-private","usercode":"ABCD-EFGH","interval":"5"}"""))
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"authorization_code":"authorization-private","code_verifier":"pkce-private","code_challenge":"challenge"}"""))
            server.enqueue(MockResponse().setBody(tokens().toString()))
            val client = CodexClient(OkHttpClient(), server.url("").toString().trimEnd('/'), server.url("/backend-api").toString())
            val pending = client.begin()
            assertEquals("ABCD-EFGH", pending.code); assertEquals(5000L, pending.interval)
            assertNull(client.poll(pending))
            val credential = CodexTokens.credential(requireNotNull(client.poll(pending)), now = 1000)
            assertEquals("person@example.test", credential.email); assertEquals("plus", credential.plan)
            assertEquals(3_601_000L, credential.expiresAt)
            val start = server.takeRequest(); assertEquals("/api/accounts/deviceauth/usercode", start.path)
            assertEquals(CodexWire.CLIENT_ID, JSONObject(start.body.readUtf8()).getString("client_id"))
            assertEquals("/api/accounts/deviceauth/token", server.takeRequest().path)
            server.takeRequest()
            val exchange = server.takeRequest()
            assertEquals("/oauth/token", exchange.path)
            val form = exchange.body.readUtf8()
            assertTrue(form.contains("grant_type=authorization_code")); assertTrue(form.contains("code_verifier=pkce-private"))
            assertFalse(credential.toString().contains("test-refresh"))
        }
    }
    @Test fun refreshRotationPreservesDisabledStateQuotaModelsAndIdentity() {
        val old = account().copy(enabled = false, quota = CodexQuota(resetCredits = 2))
        val next = CodexTokens.credential(tokens("account-a", "rotated-refresh"), old, now = 5000)
        assertFalse(next.enabled); assertEquals(old.id, next.id); assertEquals(old.models, next.models)
        assertEquals(2, next.quota.resetCredits); assertEquals("rotated-refresh", next.refreshToken)
        assertEquals(next, CodexCredential.from(next.json()))
    }
    @Test fun deviceErrorsNeverEchoRawResponsesContainingCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"secret-key-in-raw-body","code":"bad_auth"}}"""))
            val client = CodexClient(OkHttpClient(), server.url("").toString().trimEnd('/'))
            val error = runCatching { client.begin() }.exceptionOrNull()
            assertTrue(error is CodexHttpFailure)
            assertFalse(error?.message.orEmpty().contains("secret-key-in-raw-body"))
        }
    }
    @Test fun quotaUsesRealWindowDurationsAndDistinguishesUnknownFromZeroResetCards() {
        val raw = JSONObject("""{"rate_limit":{"allowed":true,"primary_window":{"used_percent":12.5,"limit_window_seconds":18000,"reset_at":12345},"secondary_window":{"used_percent":73,"limit_window_seconds":604800,"reset_at":99999}},"rate_limit_reset_credits":{"available_count":0}}""")
        val quota = CodexQuota.usage(raw, 1000)
        assertEquals(87, quota.fiveHours?.remaining); assertEquals(27, quota.weekly?.remaining)
        assertEquals(12_345_000L, quota.fiveHours?.resetsAt); assertEquals(0, quota.resetCredits)
        raw.remove("rate_limit_reset_credits"); assertNull(CodexQuota.usage(raw).resetCredits)
        assertEquals(quota, CodexQuota.from(quota.json()))
        val swapped = CodexQuota.usage(JSONObject("""{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":604800},"secondary_window":{"used_percent":30,"limit_window_seconds":18000}}}"""))
        assertEquals(70, swapped.fiveHours?.remaining); assertEquals(80, swapped.weekly?.remaining)
    }
    @Test fun disabledExpiredGrantCooledOrExhaustedAccountsAreExcludedFromThePool() {
        val p = account().models.single().provider().copy(reasoningEffort = "high")
        val now = 10_000L
        val accounts = listOf(account("a"), account("b").copy(enabled = false), account("c").copy(needsLogin = true),
            account("d").copy(quota = CodexQuota(windows = listOf(CodexWindow(100.0, 18000, now + 1000)))), account("e"))
        assertEquals(listOf("a"), CodexPool.eligible(accounts, p, now) { it == "e" }.map { it.id })
        assertEquals(listOf("a", "d"), CodexPool.eligible(accounts, p, now + 2000) { it == "e" }.map { it.id })
        assertTrue(CodexPool.eligible(accounts, p.copy(reasoningEffort = "ultra"), now) { false }.isEmpty())
    }
    @Test fun fetchedModelsHaveStableIdsAndNativeReasoningOptionsWithoutCredentials() {
        val raw = JSONObject("""{"models":[{"slug":"gpt-6-astra","context_window":272000,"supported_reasoning_levels":[{"effort":"low"},{"effort":"ultra"}],"default_reasoning_level":"low"},{"slug":"hidden-review","visibility":"hide"},{"slug":"workspace-only","allow_only_chatgpt_workspace_executor":true}]}""")
        val model = CodexModel.catalog(raw).single()
        val p = model.provider()
        assertEquals("gpt-6-astra", p.model); assertEquals(CodexWire.VENDOR, p.vendorId)
        assertEquals(p.id, model.provider().id); assertTrue(p.apiKey.isEmpty()); assertTrue(p.headers.isEmpty())
        assertEquals(listOf("low", "ultra"), ModelCatalog.efforts(p)); assertFalse(ModelCatalog.supportsTemperature(p))
        assertEquals(272000, p.contextWindow)
        assertEquals(p, Provider.from(p.json()))
    }
    @Test fun discoveryUsesTheCurrentClientVersionAndKeepsAllVisibleAccountModels() = runBlocking {
        MockWebServer().use { server ->
            val visible = listOf("gpt-6.1-sol", "gpt-6-astra", "gpt-6.1-terra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna")
            val entries = (visible + listOf("gpt-reserve", "codex-auto-review")).map { slug -> JSONObject().put("slug", slug)
                .put("visibility", if (slug in visible) "list" else "hide")
                .put("supported_reasoning_levels", JSONArray(listOf(JSONObject().put("effort", "low"), JSONObject().put("effort", "medium")))) }
            server.enqueue(MockResponse().setBody(JSONObject().put("models", JSONArray(entries)).toString()))
            val models = CodexClient(OkHttpClient(), backend = server.url("/backend-api").toString()).models(account())
            assertEquals(visible, models.map { it.slug })
            val request = server.takeRequest()
            assertEquals("/backend-api/codex/models?client_version=0.162.1", request.path)
            assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("codex-tui/0.162.1 "))
            assertEquals("codex-tui", request.getHeader("Originator"))
        }
    }
    @Test fun codexDialUsesEveryNativeDepthWithDistinctTicksAndMatchingWireParameters() {
        val levels = listOf("low", "medium", "high", "xhigh", "max", "ultra")
        val p = CodexModel("gpt-6.1-sol", 500000, levels, "low").provider()
        assertEquals(listOf("") + levels, ThinkingControls.options(p).map { it.key })
        val fractions = levels.map { level ->
            val session = Session.from(ThinkingControls.choose(Session(providerId = p.id), p, level).json())
            val effective = ThinkingControls.effective(p, session)
            val body = CodexWire.normalize(ProtocolCodec.request(effective, "instructions", listOf(Message(sessionId = session.id, role = "user", text = "task")), JSONArray()))
            assertEquals(level, body.getJSONObject("reasoning").getString("effort"))
            assertTrue(ThinkingControls.options(p).single { it.key == level }.label.contains(level))
            ThinkingControls.fraction(p, session)
        }
        assertTrue(fractions.zipWithNext().all { (a, b) -> a < b })
        assertEquals(1f, fractions.last(), .0001f)
        assertNotEquals(ThinkingControls.label("max"), ThinkingControls.label("ultra"))
        assertEquals("ultra", ModelCatalog.applyWorkload(p, "deep").reasoningEffort)
    }
    @Test fun codexDepthChoicesStayWithinEachModelsCapabilitiesAndKeepTheirOwnSelections() {
        val first = CodexModel("gpt-6.1-sol", efforts = listOf("low", "medium", "high", "xhigh", "max", "ultra"), defaultEffort = "low").provider()
        val second = CodexModel("gpt-6-luna", efforts = listOf("low", "medium", "high", "xhigh", "max"), defaultEffort = "high").provider()
        val session = Session.from(ThinkingControls.choose(ThinkingControls.choose(Session(), first, "ultra"), second, "medium").json())
        assertEquals("ultra", ThinkingControls.selected(first, session))
        assertEquals("medium", ThinkingControls.selected(second, session))
        assertTrue(runCatching { ThinkingControls.choose(session, second, "ultra") }.isFailure)
        assertEquals("max", ModelCatalog.applyWorkload(second, "deep").reasoningEffort)
        assertEquals("low", ModelCatalog.auxiliary(first).reasoningEffort)
        assertEquals(first, ThinkingControls.effective(first, ThinkingControls.choose(session, first, "")))
    }
    @Test fun codexImagesUseTheSamePrivateAccountAndKeepExplicitExternalImageSettings() {
        val current = account().models.single().provider()
        val state = AppState(providers = listOf(current), vendors = listOf(CodexWire.vendor()))
        val native = ImageModels.resolve(state, current)
        assertTrue(CodexWire.isCodex(native.first)); assertTrue(native.first.apiKey.isBlank())
        assertEquals("gpt-image-2", native.first.model); assertEquals("images", native.second.transport)
        val vendor = ApiVendor(brand = "Images", apiKey = "image-test", baseUrl = "https://images.example.test/v1")
        val configured = state.copy(vendors = state.vendors + vendor, preferences = Preferences(image = ImageSettings(vendorId = vendor.id, model = "gpt-image-2")))
        assertEquals(vendor.id, ImageModels.resolve(configured, current).first.vendorId)
    }
    @Test fun requestAdaptationRemovesUnsupportedParametersWithoutInjectingHostedTools() {
        val p = account().models.single().provider()
        val raw = ProtocolCodec.request(p.copy(temperature = 1.0, topP = .9), "instructions", listOf(Message(sessionId = "s", role = "user", text = "hello")), JSONArray())
            .put("max_output_tokens", 65536).put("temperature", 1).put("top_p", .9).put("truncation", "auto")
        val normalized = CodexWire.normalize(raw)
        for (name in listOf("max_output_tokens", "temperature", "top_p", "truncation")) assertFalse(normalized.has(name))
        assertTrue(normalized.getBoolean("stream")); assertFalse(normalized.getBoolean("store"))
        assertEquals("none", normalized.getString("tool_choice"))
        assertFalse(normalized.toString().contains("image_generation"))
        assertTrue(raw.has("max_output_tokens"))
    }
    @Test fun switchingAccountDropsOpaqueReasoningButKeepsToolHistoryAndCallIds() {
        val source = JSONObject("""{"input":[{"type":"reasoning","id":"rs_old","encrypted_content":"opaque"},{"type":"function_call","id":"fc_old","call_id":"call_1","name":"read_file","arguments":""},{"type":"function_call_output","call_id":"call_1","output":"data"}]}""")
        val body = CodexWire.normalize(source, dropReasoning = true)
        val items = body.getJSONArray("input").objects()
        assertEquals(2, items.size); assertFalse(items.first().has("id"))
        assertEquals("call_1", items.first().getString("call_id")); assertEquals("{}", items.first().getString("arguments"))
        assertEquals("data", items.last().getString("output"))
        assertEquals(3, source.getJSONArray("input").length())
    }
    @Test fun codexDoneEventPreservesFinalTextAndUsage() {
        val accumulator = StreamAccumulator(Protocol.RESPONSES)
        accumulator.accept("response.done", """{"type":"response.done","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Finished"}]}],"usage":{"input_tokens":10,"output_tokens":5}}}""")
        assertEquals("Finished", accumulator.result().text); assertEquals(5, accumulator.result().outputTokens)
    }
    private fun completed() = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"r","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}""")
    @Test fun codexStreamsRemainReadableWhenTheContentTypeIsMissingMislabeledOrMixedCase() = runBlocking {
        MockWebServer().use { server ->
            val final = JSONObject().put("type", "response.done").put("response", JSONObject("""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"读取正常"}]}],"usage":{"input_tokens":10,"output_tokens":3}}"""))
            val body = "\uFEFF: keepalive\r\n\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"读取正常\"}\r\n\r\ndata: $final\r\n\r\n"
            val access = object : PrivateModelAccess {
                override suspend fun request(provider: Provider, session: String, body: JSONObject): Request = Request.Builder().url(server.url("/responses"))
                    .post(CodexWire.normalize(body).toString().toRequestBody("application/json".toMediaType())).build()
                override suspend fun response(request: Request, response: Response) = Unit
                override suspend fun models() = emptyList<String>()
            }
            for (type in listOf(null, "application/json", "Text/Event-Stream; charset=utf-8")) {
                server.enqueue(MockResponse().setBody(body).apply { if (type != null) setHeader("Content-Type", type) })
                val seen = mutableListOf<String>()
                val result = ApiClient(privateModels = access).stream(account().models.single().provider(), "s", listOf(Message(sessionId = "s", role = "user", text = "task"))) { seen += it }
                assertEquals("读取正常", result.text); assertEquals(3, result.outputTokens)
                assertTrue(seen.contains("读取正常"))
            }
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun jsonWithoutAContentTypeIsStillReadAsJson() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(completed().removeHeader("Content-Type"))
            val provider = Provider(model = "gpt-6.1-sol", protocol = Protocol.RESPONSES, baseUrl = server.url("/").toString())
            assertEquals("OK", ApiClient().stream(provider, "s", listOf(Message(sessionId = "s", role = "user", text = "task"))) {}.text)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun definitivePreInferenceRejectionCanSelectAnotherAccount() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"usage limit"}}""")); server.enqueue(completed())
            var requests = 0
            val access = object : PrivateModelAccess {
                override suspend fun request(provider: Provider, session: String, body: JSONObject): Request { requests++; return Request.Builder().url(server.url("/responses")).post(CodexWire.normalize(body).toString().toRequestBody("application/json".toMediaType())).build() }
                override suspend fun response(request: Request, response: Response) = Unit
                override suspend fun models() = listOf("gpt-6-astra")
            }
            val api = ApiClient(privateModels = access)
            val result = api.stream(account().models.single().provider(), "s", listOf(Message(sessionId = "s", role = "user", text = "hello"))) {}
            assertEquals("OK", result.text); assertEquals(2, requests); assertEquals(2, server.requestCount)
        }
    }
    @Test fun anAcceptedIncompleteResponseIsNeverReplayedAcrossThePool() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"part\"}\n\n"))
            var requests = 0
            val access = object : PrivateModelAccess {
                override suspend fun request(provider: Provider, session: String, body: JSONObject): Request { requests++; return Request.Builder().url(server.url("/responses")).post(body.toString().toRequestBody("application/json".toMediaType())).build() }
                override suspend fun response(request: Request, response: Response) = Unit
                override suspend fun models() = emptyList<String>()
            }
            assertTrue(runCatching { ApiClient(privateModels = access).stream(account().models.single().provider(), "s", listOf(Message(sessionId = "s", role = "user", text = "work"))) {} }.isFailure)
            assertEquals(1, requests); assertEquals(1, server.requestCount)
        }
    }
}
