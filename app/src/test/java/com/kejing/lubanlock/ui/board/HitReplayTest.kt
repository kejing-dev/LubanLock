package com.kejing.lubanlock.ui.board

import com.kejing.lubanlock.domain.Dir
import com.kejing.lubanlock.domain.FlowTest
import com.kejing.lubanlock.domain.Locks
import com.kejing.lubanlock.domain.PlayState
import com.kejing.lubanlock.domain.Pose
import com.kejing.lubanlock.ui.board.input.parseLoggedPath
import com.kejing.lubanlock.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * build.md「命中目标 → 格子必须有回放单测」+ board-input README 必带单测: replay what the system reports as the hit node —
 * a stick's wood / notch / outline child, its holder, an arrow — through the same name resolver the template uses
 * (TargetResolver.byName + resolvePath), then into GameFlow through the same [routeFlow] the volume uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HitReplayTest {
    private val up = listOf("content", "root")
    private val st = PlayState(lockIndex = 0, pose = Pose.HOME.with(5, null))

    private fun sq(path: List<String>) = BoardTargets.squareOf(BoardTargets.resolvePath(path), st)

    @Test fun everyChildOfEveryStickResolvesToTheStick() {
        for (p in 1..6) {
            val holder = BoardNames.piece(p)
            val want = if (p == 5) "tray5" else "lock$p"
            assertEquals(want, sq(listOf("wood:$p", holder) + up))
            assertEquals(want, sq(listOf("cut:$p", holder) + up))
            assertEquals(want, sq(listOf("hull:$p", holder) + up))
            assertEquals(want, sq(listOf(holder) + up))
        }
    }

    @Test fun everyArrowResolvesToItsDirection() {
        for (d in Dir.entries) assertEquals("arrow${d.code}", sq(listOf(BoardNames.arrow(d)) + up))
    }

    @Test fun shadowsGhostsAndStrayNamesResolveToNothing() {
        assertEquals("-", sq(listOf("lockShadow") + up))
        assertEquals("-", sq(listOf("trayShadow:3") + up))
        assertEquals("-", sq(listOf("ghost:4") + up))
        assertEquals("-", sq(listOf("hole:0") + up))
        assertNull(BoardTargets.resolvePath(listOf("piece:9") + up))
        assertNull(BoardTargets.resolvePath(listOf("arrow:+w") + up))
        assertNull(BoardTargets.resolvePath(listOf("piece:1:2") + up))
    }

    /** Paths in the exact `path=` format of the template's TAP lines (replay of a headset input-log once there is one). */
    @Test fun replayLoggedPaths() {
        val logged = listOf(
            "wood:3 < piece:3 < content < root" to "lock3",
            "cut:5 < piece:5 < content < root" to "tray5",
            "arrow:+z < content < root" to "arrow+z",
        )
        for ((p, want) in logged) assertEquals(p, want, BoardTargets.squareOf(resolvePath(parseLoggedPath(p), BoardTargets::targetOf), st))
    }

    @Test fun aPinchOnTheKeyThenOnItsArrowTakesItOutThroughTheSameRoute() = runTest {
        val h = FlowTest.Harness(this)
        h.flow.start(); runCurrent()
        h.flow.gotoLock(Locks.ALL.indexOfFirst { it.id == "key" }); runCurrent()
        routeFlow(h.flow, BoardTargets.resolvePath(listOf("wood:5", "piece:5") + up))
        runCurrent()
        assertEquals(5, h.st.selected)
        routeFlow(h.flow, BoardTargets.resolvePath(listOf("arrow:+z") + up))
        runCurrent(); advanceTimeBy(1500); runCurrent()
        assertFalse(h.st.pose.isIn(5))
        assertEquals(1, h.st.steps)
    }

    @Test fun aPinchOnTheHintArrowWorksWithoutSelectingFirst() = runTest {
        val h = FlowTest.Harness(this)
        h.flow.start(); runCurrent()
        h.flow.gotoLock(Locks.ALL.indexOfFirst { it.id == "nudge" }); runCurrent()
        h.flow.setHint(true); runCurrent()
        routeFlow(h.flow, BoardTargets.resolvePath(listOf("arrow:-y") + up))
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(1, h.st.steps)
    }
}
