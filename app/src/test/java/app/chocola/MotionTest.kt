package app.chocola

import app.chocola.ui.DismissSequence
import app.chocola.ui.RevealMotion
import org.junit.Assert.*
import org.junit.Test

class MotionTest {
    @Test fun contentFollowsTheOpeningEdgeWithoutWaitingForExpansionToFinish() {
        assertEquals(0f, RevealMotion.following(0f), 0f)
        assertTrue(RevealMotion.following(.05f) > 0f)
        assertTrue(RevealMotion.following(.5f) in .46f..0.5f)
        assertEquals(1f, RevealMotion.following(1f), 0f)
        val values = (0..100).map { RevealMotion.following(it / 100f) }
        assertTrue(values.zipWithNext().all { (one, two) -> one <= two })
    }

    @Test fun nextSurfaceWaitsUntilThePreviousSurfaceIsDismissed() {
        val events = mutableListOf<String>()
        val sequence = DismissSequence()
        assertTrue(sequence.begin { events += "navigate" })
        assertTrue(events.isEmpty())
        sequence.finish { events += "dismiss" }
        assertEquals(listOf("dismiss", "navigate"), events)
    }

    @Test fun repeatedTapsCannotQueueAnotherNavigationOrResolveTwice() {
        var dismissals = 0
        var decisions = 0
        val sequence = DismissSequence()
        assertTrue(sequence.begin { decisions++ })
        assertFalse(sequence.begin { decisions += 100 })
        sequence.finish { dismissals++ }
        sequence.finish { dismissals++ }
        assertEquals(1, dismissals)
        assertEquals(1, decisions)
    }

    @Test fun anUncommittedDismissalCannotRemoveTheSurface() {
        val sequence = DismissSequence()
        sequence.finish { fail("The surface is still open") }
        assertFalse(sequence.closing)
        assertTrue(sequence.begin())
        var dismissed = false
        sequence.finish { dismissed = true }
        assertTrue(dismissed)
    }
}
