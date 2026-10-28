package app.chocola

import app.chocola.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class DiagnosticsJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(sequence: Long = 1, fields: Map<String, Any?> = emptyMap()) = TraceRecord(sequence, "run", 29, 123,
        TraceStamp(1_000, 500, 400, 42, "source-thread"), "test.event", "chat", false, fields)
    private fun JournalSlice.read(): String = use { slice -> ByteArrayOutputStream().also { slice.copyTo(it) }.toString("UTF-8") }

    @Test fun timestampsBelongToTheProducerAndReservedFieldsCannotBeOverridden() {
        val json = record(fields = mapOf("time" to "fake", "seq" to -1, "foreground" to true)).json(TraceStamp(900_000, 1500, 600, 99, "writer"))
        assertEquals("1970-01-01T00:00:01Z", json.getString("time"))
        assertEquals(1L, json.getLong("seq"))
        assertEquals(1000L, json.getLong("writeLagMs"))
        assertEquals(200L, json.getLong("writeAwakeLagMs"))
        assertEquals("source-thread", json.getString("threadName"))
        assertEquals(42, json.getInt("tid"))
        assertFalse(json.getBoolean("foreground"))
    }
    @Test fun backwardsClockValuesNeverProduceNegativeQueueLag() {
        val json = record().json(TraceStamp(0, 0, 0, 0, "writer"))
        assertEquals(0L, json.getLong("writeLagMs"))
        assertEquals(0L, json.getLong("writeAwakeLagMs"))
    }
    @Test fun nonfiniteNumbersCyclesAndOversizedFieldsStaySerializableAndBounded() {
        val cycle = mutableMapOf<String, Any?>()
        cycle["self"] = cycle
        val data = record(fields = mapOf("bad" to Double.NaN, "infinite" to Float.POSITIVE_INFINITY,
            "cycle" to cycle, "large" to "汉\u0000".repeat(100_000), "many" to List(10_000) { it })).json(record().stamp)
        val encoded = data.toString()
        assertTrue(data.isNull("bad")); assertTrue(data.isNull("infinite"))
        assertTrue(encoded.toByteArray().size < 262_144)
        assertEquals(2, JSONObject(encoded).getInt("schema"))
        assertTrue(encoded.contains("limit"))
    }
    @Test fun boundedJsonStillRedactsNestedCredentialsWithoutCorruptingNumbers() {
        val json = record(fields = mapOf("nested" to mapOf("password" to "not-a-real-secret", "count" to 123),
            "escaped" to "line\n\"quote\"", "valid" to 12.25)).json(record().stamp)
        val clean = JSONObject(Redactor.json(json).toString())
        assertEquals("[redacted]", clean.getJSONObject("nested").getString("password"))
        assertEquals(123, clean.getJSONObject("nested").getInt("count"))
        assertEquals(12.25, clean.getDouble("valid"), 0.0)
        assertEquals("line\n\"quote\"", clean.getString("escaped"))
    }
    @Test fun overflowDropsOldestAndReportsTheGap() {
        val queue = TraceBuffer(3)
        (1L..5L).forEach { queue.offer(record(it)) }
        assertEquals(3 to 2L, queue.health())
        assertEquals(listOf(3L, 4L), queue.drain(2).map { it.sequence })
        assertEquals(listOf(5L), queue.drain().map { it.sequence })
        assertEquals(0 to 2L, queue.health())
    }
    @Test fun publicMirrorReadsOnlyNewBytesUntilRotation() {
        val journal = TraceJournal(temporary.newFolder())
        assertNull(journal.snapshot(null))
        journal.append(listOf("{\"a\":1}"))
        val first = requireNotNull(journal.snapshot(null))
        assertTrue(first.reset)
        val cursor = first.cursor
        assertEquals("{\"a\":1}\n", first.read())
        assertNull(journal.snapshot(cursor))
        journal.append(listOf("{\"b\":2}"))
        val second = requireNotNull(journal.snapshot(cursor))
        assertFalse(second.reset)
        assertEquals("{\"b\":2}\n", second.read())
    }
    @Test fun rotationResetsCursorAndKeepsAChronologicalBoundedSnapshot() {
        val root = temporary.newFolder()
        val journal = TraceJournal(root, segmentBytes = 8, archives = 2)
        journal.append(listOf("111"))
        val cursor = requireNotNull(journal.snapshot(null)).use { it.cursor }
        journal.append(listOf("222", "333", "444", "555", "666", "777"))
        val slice = requireNotNull(journal.snapshot(cursor))
        assertTrue(slice.reset)
        assertEquals("333\n444\n555\n666\n777\n", slice.read())
        assertTrue(root.listFiles().orEmpty().filter { it.extension == "jsonl" }.all { it.length() <= 8 })
        assertEquals(3, root.listFiles().orEmpty().size)
    }
    @Test fun openSnapshotSurvivesConcurrentRotationAndArchiveDeletion() {
        val journal = TraceJournal(temporary.newFolder(), segmentBytes = 4, archives = 1)
        journal.append(listOf("111"))
        val before = requireNotNull(journal.snapshot(null))
        journal.append(listOf("222", "333", "444"))
        assertEquals("111\n", before.read())
        assertEquals("333\n444\n", requireNotNull(journal.snapshot(null)).read())
    }
    @Test fun startupRepairsAnInterruptedLargeLineWithoutDiscardingCompleteEvents() {
        val root = temporary.newFolder()
        File(root, "current.jsonl").writeText("{\"complete\":true}\n" + "x".repeat(80_000))
        val journal = TraceJournal(root)
        journal.append(listOf("{\"next\":true}"))
        val text = requireNotNull(journal.snapshot(null)).read()
        assertEquals("{\"complete\":true}\n{\"next\":true}\n", text)
        text.lineSequence().filter { it.isNotBlank() }.forEach { JSONObject(it) }
    }
    @Test fun startupRepairsAFileContainingOnlyAnInterruptedFirstLine() {
        val root = temporary.newFolder()
        File(root, "current.jsonl").writeText("partial")
        val journal = TraceJournal(root)
        journal.append(listOf("{}"))
        assertEquals("{}\n", requireNotNull(journal.snapshot(null)).read())
    }
    @Test fun staleCursorAfterTruncationRequestsAnAuthoritativeSnapshot() {
        val root = temporary.newFolder()
        val journal = TraceJournal(root)
        journal.append(listOf("123456789"))
        val cursor = requireNotNull(journal.snapshot(null)).use { it.cursor }
        File(root, "current.jsonl").writeText("{}\n")
        val snapshot = requireNotNull(TraceJournal(root).snapshot(cursor))
        assertTrue(snapshot.reset)
        assertEquals("{}\n", snapshot.read())
    }
    @Test fun emergencyRecordIsAvailableAfterAnUncleanRestart() {
        val root = temporary.newFolder()
        TraceJournal(root).emergency("{\"event\":\"fatal\"}")
        val restarted = TraceJournal(root)
        restarted.append(listOf("{\"event\":\"start\"}"))
        assertEquals(listOf("fatal", "start"), requireNotNull(restarted.snapshot(null)).read().lineSequence()
            .filter { it.isNotBlank() }.map { JSONObject(it).getString("event") }.toList())
    }
}
