package app.chocola

import app.chocola.data.project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StateProjectionTest {
    @Test fun aMountedChatsProjectionStaysCurrentWhenTheActivityHasNoCollector() = runTest {
        val source = MutableStateFlow("before background")
        val screen = source.project(backgroundScope, 32, eager = true) { it }
        runCurrent()
        source.value = "completed in background"
        advanceTimeBy(33); runCurrent()
        assertEquals("completed in background", screen.value)
    }
    @Test fun anExistingConversationIsAvailableBeforeItsCollectorStarts() = runTest {
        val source = MutableStateFlow(listOf("saved reply"))
        val screen = source.project(backgroundScope, 32) { it }
        assertEquals(listOf("saved reply"), screen.value)
    }

    @Test fun rapidUpdatesRenderTheLatestSnapshotAndRetainTheFinalValue() = runTest {
        val source = MutableStateFlow(0)
        val screen = source.project(backgroundScope, 32) { it }
        val seen = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { screen.collect { seen += it } }
        runCurrent()
        source.value = 1; runCurrent()
        source.value = 2; runCurrent()
        advanceTimeBy(33); runCurrent()
        assertEquals(listOf(0, 2), seen)
        advanceTimeBy(33); runCurrent()
        assertEquals(2, screen.value)
    }

    @Test fun leavingAScreenReleasesItsProjection() = runTest {
        val source = MutableStateFlow(0)
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val screen = source.project(owner) { it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { screen.collect {} }
        runCurrent()
        source.value = 1; runCurrent()
        assertEquals(1, screen.value)
        owner.cancel()
        source.value = 2; runCurrent()
        assertEquals(1, screen.value)
    }
}
