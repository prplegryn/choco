package app.chocola

import app.chocola.agent.ProcessCounters
import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeMetricsTest {
    private fun stamp(wall: Long, elapsed: Long, awake: Long) = TraceStamp(wall, elapsed, awake, 1, "probe")
    @Test fun sleepingDeviceIsNotReportedAsFiveSecondsOfMainThreadWork() {
        val gap = ClockGap.between(stamp(1000, 1000, 500), stamp(6000, 6000, 1000), 500, 20)
        assertEquals(5000L, gap.elapsedMs)
        assertEquals(500L, gap.awakeMs)
        assertEquals(4500L, gap.sleepMs)
        assertEquals(0L, gap.lateMs)
        assertEquals(20L, gap.cpuMs)
    }
    @Test fun awakeSchedulingGapAndClockAdjustmentRemainDistinguishable() {
        val gap = ClockGap.between(stamp(1000, 1000, 1000), stamp(90_000, 6000, 6000), 2000, 80)
        assertEquals(0L, gap.sleepMs)
        assertEquals(3000L, gap.lateMs)
        assertEquals(80L, gap.cpuMs)
    }
    @Test fun invalidOrRestartedClocksDoNotCreateNegativeDurations() {
        val gap = ClockGap.between(stamp(9000, 9000, 9000), stamp(1000, 1000, 1000), 2000, -1)
        assertEquals(ClockGap(0, 0, 0, 0, 0), gap)
    }
    @Test fun frameBudgetUsesEachFramesOwnDeadlineAndSeparatesLostCallbacks() {
        val samples = FrameSamples()
        samples.add(10_000_000, 16_666_667, 0, longArrayOf(0, 0, 0, 3_000_000))
        samples.add(10_000_000, 8_333_333, 4, longArrayOf(0, 0, 0, 6_000_000))
        samples.add(50_000_000, 16_666_667, 0, LongArray(9))
        val fields = samples.fields()
        assertEquals(3, fields["frames"])
        assertEquals(2, fields["overBudget"])
        assertEquals(1, fields["over32ms"])
        assertEquals(4, fields["lostMetricCallbacks"])
        assertEquals(50.0, fields["p95UpperMs"])
        assertEquals(6.0, (fields["maxStagesMs"] as Map<*, *>)["layout"])
    }
    @Test fun invalidMetricsDoNotPolluteFrameDistribution() {
        val samples = FrameSamples()
        samples.add(-1, 16_666_667, 10, LongArray(9))
        samples.add(1, -1, 10, LongArray(9))
        assertEquals(0, samples.count)
        assertEquals(0.0, samples.fields()["p99UpperMs"])
        samples.add(600_000_000, 16_666_667, 0, LongArray(9))
        assertEquals(600.0, samples.fields()["p99UpperMs"])
    }
    @Test fun durationPercentilesAreUpperBoundsAndHandleAnEmptySampleSet() {
        assertEquals(0.0, DurationStats().fields()["p95UpperMs"])
        val stats = DurationStats()
        repeat(95) { stats.add(1_100_000) }
        repeat(5) { stats.add(20_000_000) }
        val data = stats.fields()
        assertEquals(100L, data["count"])
        assertEquals(2.0, data["p95UpperMs"])
        assertEquals(20.0, data["p99UpperMs"])
        assertEquals(20.0, data["maxMs"])
    }
    @Test fun processTelemetryDistinguishesQuietOutputFromAnUnscheduledReader() {
        val counters = ProcessCounters().apply {
            bytes = 10; retained = 10; polls = 30; lastPoll = 2_000_000_000; lastOutput = 1_000_000_000
            consumer = 2_200_000_000; maxPollGap = 500_000_000
        }
        val sample = counters.fields(2_500_000_000)
        assertEquals(500L, sample["sincePollMs"])
        assertEquals(1500L, sample["sinceOutputMs"])
        assertEquals(300L, sample["consumerInFlightMs"])
        assertEquals(10L, sample["outputBytes"])
        assertFalse(sample.containsKey("output"))
    }
}
