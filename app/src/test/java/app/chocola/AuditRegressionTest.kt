package app.chocola

import app.chocola.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuditRegressionTest {
    @Test fun realUuidSchedulesAndCredentialChecksUseSafeIndependentSandboxIds() {
        val task = ScheduledTask(id = newId())
        val run = newId()
        val first = ScheduleTimes.claim(task, task.startAt, "running", run)
        val second = ScheduleTimes.claim(task, task.startAt, "running", newId())
        val check = SessionIds.credentialCheck()
        listOf(first.resultSessionId, second.resultSessionId, check).forEach {
            assertTrue(it.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            SessionIds.requireValid(it)
        }
        assertNotEquals(first.resultSessionId, second.resultSessionId)
        assertNotEquals(check, SessionIds.credentialCheck())
        assertEquals(first.resultSessionId, ScheduledTask.from(first.json()).resultSessionId)
        assertEquals(first.resultSessionId, ScheduleTimes.complete(first, run, "done").resultSessionId)
    }
    @Test fun oldReportsRetainTheirIdentityButUseSafeRuntimePaths() {
        val legacy = "scheduled:${newId()}:${newId()}"
        val record = Session(id = legacy, scheduledTaskId = "daily")
        assertEquals(legacy, Session.from(record.json()).id)
        SessionIds.requireValid(legacy)
        assertTrue(SessionIds.runtimeKey(legacy).matches(Regex("[A-Za-z0-9_-]{1,80}")))
        assertEquals(SessionIds.runtimeKey(legacy), SessionIds.runtimeKey(legacy))
        listOf("../chat", "scheduled:../chat:run", "scheduled:one:two:three", "/chat", "a\\b", "x:y").forEach {
            assertFalse(it, SessionIds.valid(it))
        }
    }
    @Test fun automaticMemoryCannotReplaceSameNameCredentialsOrCustomRecords() {
        val records = listOf(
            Memory(name = "服务器", kind = "服务器", toolsAllowed = true, fields = listOf(MemoryField("password", "saved-password", true))),
            Memory(name = "服务器", kind = "资料", fields = listOf(MemoryField("token", "saved-token", true))),
            Memory(name = "服务器", fields = listOf(MemoryField("地址", "example.com"), MemoryField("端口", "22"))))
        records.forEach { original ->
            val before = original.json().toString()
            assertTrue(runCatching { MemoryWrites.remember(listOf(original), "服务器", "普通备注", "") }.isFailure)
            assertEquals(before, original.json().toString())
        }
    }
    @Test fun plainFactsUpdateInTheirOwnScopeWithoutChangingStoredFlags() {
        val fact = Memory(name = "偏好", fields = listOf(MemoryField("内容", "原内容")), enabled = false, scope = "chat")
        val updated = MemoryWrites.remember(listOf(fact), "偏好", "新内容", "chat")
        assertEquals(fact.id, updated.id); assertFalse(updated.enabled); assertEquals("chat", updated.scope)
        assertEquals("新内容", updated.fields.single().value)
        assertNotEquals(fact.id, MemoryWrites.remember(listOf(fact), "偏好", "全局内容", "").id)
    }
    @Test fun redactionPreservesNumericBooleanNullAndNestedJsonStructure() {
        Redactor.register("1000"); Redactor.register("true")
        val data = JSONObject().put("elapsed", 1000).put("ok", true).put("secret", false).put("missing", JSONObject.NULL)
            .put("items", JSONArray().put(JSONObject().put("password", "1000").put("count", 1000)).put(false))
        val redacted = JSONObject(Redactor.clean(data.toString()))
        assertEquals(1000, redacted.getInt("elapsed")); assertTrue(redacted.getBoolean("ok"))
        assertFalse(redacted.getBoolean("secret")); assertTrue(redacted.isNull("missing"))
        assertEquals("[redacted]", redacted.getJSONArray("items").getJSONObject(0).getString("password"))
        assertEquals(1000, redacted.getJSONArray("items").getJSONObject(0).getInt("count"))
        assertFalse(redacted.getJSONArray("items").getBoolean(1))
    }
    @Test fun escapedMultilineSecretsAndUnknownSensitiveKeysStayValidJson() {
        val secret = "private-\"value\\folder\nline2"
        Redactor.register(secret)
        val source = JSONObject().put("output", "before $secret after").put("authorization", "opaque-value-with-spaces")
            .put("headers", JSONObject().put("x-api-key", "new-unregistered-value"))
        val result = Redactor.json(source)
        assertEquals("before [redacted] after", result.getString("output"))
        assertEquals("[redacted]", result.getString("authorization"))
        assertEquals("[redacted]", result.getJSONObject("headers").getString("x-api-key"))
        assertEquals("opaque-value-with-spaces", source.getString("authorization"))
        assertEquals(3, JSONObject(result.toString()).length())
    }
    @Test fun nestedMapsInDiagnosticsAreSanitizedWithoutDestroyingTypes() {
        val source = JSONObject().put("fields", mapOf("token" to "nested-new-secret", "pid" to 1000, "ok" to true))
        val data = JSONObject(Redactor.json(source).toString()).getJSONObject("fields")
        assertEquals("[redacted]", data.getString("token")); assertTrue(data.getBoolean("ok")); assertEquals(1000, data.getInt("pid"))
    }
    @Test fun jsonEncodedInsideToolTextSurvivesRepeatedRedaction() {
        Redactor.register("1000"); Redactor.register("true")
        val body = JSONObject().put("duration", 1000).put("ok", true).put("token", "unknown-nested-token").toString()
        val tool = JSONObject().put("body", body).put("status", 200)
        val once = Redactor.clean(tool.toString())
        val twice = Redactor.clean(once)
        val nested = JSONObject(JSONObject(twice).getString("body"))
        assertEquals(1000, nested.getInt("duration")); assertTrue(nested.getBoolean("ok"))
        assertEquals("[redacted]", nested.getString("token"))
        assertEquals(once, twice)
        val plain = JSONObject().put("output", "1000").toString()
        val masked = Redactor.clean(plain)
        assertEquals("[redacted]", JSONObject(masked).getString("output"))
        assertEquals(masked, Redactor.clean(masked))
    }
}
