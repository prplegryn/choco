package app.chocola

import app.chocola.api.StreamTrace
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NetworkTraceTest {
    private var now = 100L
    private fun trace() = StreamTrace(null, "request", "session", "operation", 0) { now }
    private fun source(read: (Buffer, Long) -> Long) = object : Source {
        override fun read(sink: Buffer, byteCount: Long) = read.invoke(sink, byteCount)
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }
    @Test fun receivedBytesAndParseProgressAreIndependentAndPayloadIsNotRetained() {
        val trace = trace()
        val observed = trace.source(source { sink, _ -> now = 150; sink.writeUtf8("abc"); 3 })
        val target = Buffer()
        assertEquals(3L, observed.read(target, 10))
        assertEquals("abc", target.readUtf8())
        now = 200; trace.parsed()
        now = 250
        val fields = trace.fields()
        assertEquals(3L, fields["decodedBytes"])
        assertEquals(1L, fields["eventsParsed"])
        assertEquals(50L, fields["firstDataMs"])
        assertEquals(100L, fields["sinceLastReadMs"])
        assertEquals(50L, fields["sinceLastEventMs"])
        assertNull(fields["readInFlightMs"])
        assertFalse(fields.values.contains("abc"))
    }
    @Test fun independentProbeCanObserveAReadThatHasNotReturned() {
        val trace = trace()
        val observed = trace.source(source { _, _ ->
            now = 2100
            assertEquals(2000L, trace.fields()["readInFlightMs"])
            -1
        })
        assertEquals(-1L, observed.read(Buffer(), 10))
        assertNull(trace.fields()["readInFlightMs"])
        assertEquals(2000L, trace.fields()["maxReadMs"])
    }
    @Test fun timedOutReadRetainsItsDurationAndDoesNotInventReceivedData() {
        val trace = trace()
        val observed = trace.source(source { _, _ -> now = 180_100; throw IOException("timeout") })
        assertThrows(IOException::class.java) { observed.read(Buffer(), 10) }
        val fields = trace.fields()
        assertEquals(180_000L, fields["maxReadMs"])
        assertEquals(0L, fields["decodedBytes"])
        assertNull(fields["firstDataMs"])
        assertNull(fields["readInFlightMs"])
    }
    @Test fun slowConsumerIsVisibleSeparatelyFromNetworkReadWait() = runBlocking {
        val trace = trace()
        trace.consume {
            now = 600
            assertEquals(500L, trace.fields()["consumerInFlightMs"])
            assertNull(trace.fields()["readInFlightMs"])
        }
        assertEquals(500L, trace.fields()["maxConsumerMs"])
        assertNull(trace.fields()["consumerInFlightMs"])
    }
    @Test fun failedConsumerClearsInFlightStateAndPreservesTheOriginalFailure() = runBlocking {
        val trace = trace()
        val failure = IOException("expected")
        try {
            trace.consume { now = 800; throw failure }
            fail("Expected consumer failure")
        } catch (error: IOException) { assertSame(failure, error) }
        assertEquals(700L, trace.fields()["maxConsumerMs"])
        assertNull(trace.fields()["consumerInFlightMs"])
    }
    @Test fun independentRuntimeProbeIncludesCurrentSemanticProgressWhileReadIsBlocked() {
        val trace = trace()
        var reasoning = 12L
        trace.protocolSamples { mapOf("reasoningDeltaChars" to reasoning, "streamPhase" to "THINKING") }
        val observed = trace.source(source { _, _ ->
            now = 1100; reasoning = 80L
            assertEquals(80L, trace.fields()["reasoningDeltaChars"])
            assertEquals("THINKING", trace.fields()["streamPhase"])
            assertEquals(1000L, trace.fields()["readInFlightMs"])
            -1
        })
        observed.read(Buffer(), 10)
        assertNull(trace.fields()["readInFlightMs"])
    }
}
