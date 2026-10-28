package app.chocola

import app.chocola.api.*
import app.chocola.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpClientTest {
    private fun response(id: Int, result: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    @Test fun modernRequestsCarryMetadataAndAcceptMultilineSseReplies() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(response(1, """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""))
            server.enqueue(response(2, """{"tools":[{"name":"weather","inputSchema":{"type":"object","properties":{"city":{"type":"string","x-mcp-header":"City"}},"required":["city"]}}]}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                : keepalive

                data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

                data: {"jsonrpc":"2.0","id":3,
                data: "result":{"resultType":"complete","content":[{"type":"text","text":"done"}]}}


            """.trimIndent() + "\n\n"))
            val config = McpServer(name = "test", url = server.url("/mcp").toString(), token = "test-token")
            McpConnection(McpHttpTransport(config)).use { connection ->
                connection.initialize(); val tools = connection.tools()
                val output = connection.call(tools.single(), JSONObject().put("city", "北京"))
                assertEquals("done", output.getJSONArray("content").getJSONObject(0).getString("text"))
            }
            val discovery = server.takeRequest()
            assertEquals("2026-07-28", discovery.getHeader("MCP-Protocol-Version"))
            assertEquals("server/discover", discovery.getHeader("Mcp-Method"))
            assertEquals("Bearer test-token", discovery.getHeader("Authorization"))
            val meta = JSONObject(discovery.body.readUtf8()).getJSONObject("params").getJSONObject("_meta")
            assertEquals("2026-07-28", meta.getString("io.modelcontextprotocol/protocolVersion"))
            server.takeRequest()
            val call = server.takeRequest()
            assertEquals("tools/call", call.getHeader("Mcp-Method"))
            assertEquals("weather", call.getHeader("Mcp-Name"))
            assertTrue(call.getHeader("Mcp-Param-City").orEmpty().startsWith("=?base64?"))
        }
    }
    @Test fun legacyHandshakeRetainsSessionWithoutReplayingFailedCalls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":-32601,"message":"initialize first"}}"""))
            server.enqueue(response(2, """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}}}""").setHeader("Mcp-Session-Id", "session-one"))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(response(3, """{"tools":[{"name":"write","inputSchema":{"type":"object","properties":{}}}]}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setResponseCode(204))
            val config = McpServer(name = "test", url = server.url("/mcp").toString())
            McpConnection(McpHttpTransport(config)).use { connection ->
                connection.initialize()
                assertEquals("2025-11-25", connection.version)
                val tool = connection.tools().single()
                assertTrue(runCatching { connection.call(tool, JSONObject()) }.isFailure)
                assertEquals(5, server.requestCount)
            }
            server.takeRequest()
            assertEquals("initialize", JSONObject(server.takeRequest().body.readUtf8()).getString("method"))
            assertEquals("session-one", server.takeRequest().getHeader("Mcp-Session-Id"))
            assertEquals("session-one", server.takeRequest().getHeader("Mcp-Session-Id"))
        }
    }
    @Test fun headerAnnotationsAreValidatedOutsideAndInsideProperties() {
        val schema = JSONObject("""{"type":"object","properties":{"nested":{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}}}""")
        assertEquals("cn", McpProtocol.parameterHeaders(schema, JSONObject("""{"nested":{"region":"cn"}}"""))["Mcp-Param-Region"])
        val invalid = JSONObject("""{"type":"object","oneOf":[{"type":"string","x-mcp-header":"Bad"}]}""")
        assertTrue(runCatching { McpProtocol.parameterHeaders(invalid, JSONObject()) }.isFailure)
        val injected = JSONObject("""{"type":"object","properties":{"name":{"type":"string","x-mcp-header":"Bad\r\nHost"}}}""")
        assertTrue(runCatching { McpProtocol.parameterHeaders(injected, JSONObject()) }.isFailure)
    }
}
