package app.chocola

import app.chocola.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScheduleTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private fun task(frequency: String = "daily", start: String = "2026-10-24T01:00:00Z") = ScheduledTask(
        id = "plan", sessionId = "chat", title = "检查", prompt = "检查并记录结果", frequency = frequency, startAt = at(start), zone = "Asia/Shanghai")
    @Test fun encryptedRecordPayloadRoundTripsAllSchedulingAndExecutionFields() {
        val original = task("weekly").copy(weekdays = setOf(1, 3, 7), endAt = at("2026-12-01T00:00:00Z"), maxRuns = 9,
            timeoutMinutes = 12, runCount = 3, nextAt = 123, lastAt = 100, lastStatus = "failed", lastDetail = "网络不可用")
        assertEquals(original, ScheduledTask.from(original.json()))
    }
    @Test fun firstOccurrenceIncludesTheStartBoundaryButNeverRunsAgainAtTheSameInstant() {
        val t = task("once")
        assertEquals(t.startAt, ScheduleTimes.next(t, t.startAt - 1))
        assertEquals(0L, ScheduleTimes.next(t, t.startAt))
    }
    @Test fun dailyRecurrenceUsesTheSavedLocalTime() {
        val t = task()
        assertEquals(at("2026-10-25T01:00:00Z"), ScheduleTimes.next(t, t.startAt))
        assertEquals(t.startAt, ScheduleTimes.next(t, at("2026-10-23T12:00:00Z")))
    }
    @Test fun intervalKeepsOriginalCadenceAfterLateExecutionAndSkipsMissedIntervals() {
        val t = task("interval").copy(intervalMinutes = 30)
        assertEquals(at("2026-10-24T02:30:00Z"), ScheduleTimes.next(t, at("2026-10-24T02:17:00Z")))
    }
    @Test fun weeklyHonorsSelectedDaysAndNeverSchedulesBeforeStart() {
        val t = task("weekly").copy(weekdays = setOf(1, 3))
        assertEquals(at("2026-10-26T01:00:00Z"), ScheduleTimes.next(t, t.startAt - 1))
        assertEquals(at("2026-10-28T01:00:00Z"), ScheduleTimes.next(t, at("2026-10-26T01:00:00Z")))
    }
    @Test fun weeklyCanJumpAFullWeek() {
        val t = task("weekly").copy(weekdays = setOf(6))
        assertEquals(at("2026-10-31T01:00:00Z"), ScheduleTimes.next(t, t.startAt))
    }
    @Test fun endIsInclusiveAndExhaustedOrPausedPlansHaveNoNextOccurrence() {
        val t = task().copy(endAt = at("2026-10-25T01:00:00Z"))
        assertEquals(t.endAt, ScheduleTimes.next(t, t.startAt))
        assertEquals(0L, ScheduleTimes.next(t, t.endAt))
        assertEquals(0L, ScheduleTimes.next(t.copy(enabled = false), t.startAt - 1))
        assertEquals(0L, ScheduleTimes.next(t.copy(maxRuns = 3, runCount = 3), t.startAt - 1))
    }
    @Test fun springDaylightSavingGapMovesToAValidLocalTimeAndReturnsToOriginalTimeNextDay() {
        val t = task(start = "2026-03-07T07:30:00Z").copy(zone = "America/New_York")
        val shifted = ScheduleTimes.next(t, t.startAt)
        assertEquals(at("2026-03-08T07:30:00Z"), shifted)
        assertEquals(at("2026-03-09T06:30:00Z"), ScheduleTimes.next(t, shifted))
    }
    @Test fun fallDaylightSavingOverlapDoesNotDuplicateTheDailyOccurrence() {
        val t = task(start = "2026-10-31T05:30:00Z").copy(zone = "America/New_York")
        val first = ScheduleTimes.next(t, t.startAt)
        assertEquals(at("2026-11-01T05:30:00Z"), first)
        assertEquals(at("2026-11-02T06:30:00Z"), ScheduleTimes.next(t, first))
    }
    @Test fun displayedTimeRoundTripsAndInvalidDatesAreRejected() {
        val t = task()
        assertEquals(t.startAt, ScheduleTimes.parse(ScheduleTimes.display(t.startAt, t.zone), t.zone))
        assertTrue(runCatching { ScheduleTimes.parse("2026-02-30 09:00", t.zone) }.isFailure)
    }
    @Test fun overlappingOccurrencesCannotEraseTheActiveRunOrRestartItsCounter() {
        val t = task("interval").copy(intervalMinutes = 15)
        val active = ScheduleTimes.claim(t, t.startAt, "running", "run-1")
        assertEquals(1, active.runCount)
        assertEquals("run-1", active.activeRun)
        val skipped = ScheduleTimes.claim(active, active.nextAt, "busy", "unused")
        assertEquals("running", skipped.lastStatus)
        assertEquals(active.lastAt, skipped.lastAt)
        assertEquals(1, skipped.runCount)
        assertEquals("run-1", skipped.activeRun)
        assertTrue(skipped.nextAt > active.nextAt)
        assertEquals(skipped, ScheduleTimes.complete(skipped, "stale-run", "done"))
        val completed = ScheduleTimes.complete(skipped, "run-1", "timeout")
        assertEquals("", completed.activeRun)
        assertEquals("timeout", completed.lastStatus)
    }
    @Test fun skippedAndMissedOccurrencesDoNotConsumeTheActualExecutionLimit() {
        val t = task().copy(maxRuns = 1)
        val missed = ScheduleTimes.claim(t, t.startAt + 1000000, "missed", "unused")
        assertEquals(0, missed.runCount)
        assertTrue(missed.nextAt > 0)
        val started = ScheduleTimes.claim(missed, missed.nextAt, "running", "run")
        assertEquals(1, started.runCount)
        assertEquals(0L, started.nextAt)
        assertEquals("run", started.activeRun)
    }
    @Test fun unsafeOrUnusableSchedulesAreRejectedBeforePersistence() {
        val t = task()
        t.validate()
        listOf(t.copy(title = ""), t.copy(prompt = ""), t.copy(frequency = "unknown"), t.copy(zone = "wrong-zone"),
            t.copy(endAt = t.startAt - 1), t.copy(intervalMinutes = 0), t.copy(weekdays = emptySet()), t.copy(weekdays = setOf(8)),
            t.copy(timeoutMinutes = 0), t.copy(maxRuns = -1)).forEach { invalid -> assertTrue(runCatching { invalid.validate() }.isFailure) }
    }
}
