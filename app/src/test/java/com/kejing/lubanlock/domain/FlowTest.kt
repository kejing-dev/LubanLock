package com.kejing.lubanlock.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game flow end to end on a virtual clock: taps, arrows, blocked, exit, put back (right / wrong), undo, hint,
 * explode rules, auto-turn timing, confirmations, restore, and the row-3 readout (D38). Same calls as the volume,
 * the window and the debug broadcast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    class MemStore : GameStore {
        var saved: SavedGame? = null
        override fun load() = saved
        override fun save(game: SavedGame) { saved = game }
        override fun clear() { saved = null }
    }

    class Harness(val scope: TestScope, var settings: Settings = Settings(firstRunDone = true, coach = false), val store: MemStore = MemStore()) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(
            scope = scope, engine = StandardTestDispatcher(scope.testScheduler), clock = { scope.testScheduler.currentTime },
            store = store, settings = { settings }, onSettings = { settings = it }, onSound = { sounds += it },
        )
        val st: PlayState get() = flow.state.value
        fun settle(ms: Long = 3000) { scope.runCurrent(); scope.advanceTimeBy(ms); scope.runCurrent() }
    }

    private fun TestScope.started(lock: String = "key", settings: Settings = Settings(firstRunDone = true, coach = false)): Harness {
        val h = Harness(this, settings)
        h.flow.start(); runCurrent()
        h.flow.gotoLock(Locks.ALL.indexOfFirst { it.id == lock }); h.settle(100)
        return h
    }

    @Test fun tapThenArrowTakesTheKeyOutAndSaysWhy() = runTest {
        val h = started("key")
        h.flow.tapPiece(5); runCurrent()
        assertEquals(5, h.st.selected)
        assertEquals(setOf(Dir.PZ, Dir.NZ), h.st.arrows.toSet())
        assertEquals("S03", h.st.stateId)
        h.flow.tapArrow(Dir.PZ); runCurrent()
        assertTrue(h.st.anim is PathAnim)
        assertEquals("S06", h.st.stateId)
        h.settle(1300)
        assertFalse(h.st.pose.isIn(5))
        assertEquals(1, h.st.steps)
        val row3 = Readout.row3(h.st, testScheduler.currentTime)
        assertTrue(row3 is Row3.Why && row3.reason.kind == Kind.KEY)
        assertTrue(Sound.WHOOSH in h.sounds && Sound.DROP in h.sounds)
        // 3 s later row 3 is back to "what to do now"
        advanceTimeBy(3100); runCurrent()
        assertEquals(Row3.Start, Readout.row3(h.st, testScheduler.currentTime))
    }

    @Test fun aStuckStickShakesNamesItsBlockersAndDoesNotMove() = runTest {
        val h = started("key")
        h.flow.tapPiece(3); runCurrent()
        assertTrue(h.st.anim is BumpAnim)
        assertEquals(setOf(1, 5), h.st.glow)
        val r = (h.st.line as Line.Why).reason
        assertEquals(Kind.BLOCKED, r.kind)
        h.settle(400)
        assertEquals(Pose.HOME, h.st.pose)
        assertEquals(0, h.st.steps)
        assertTrue(Sound.BLOCK in h.sounds)
    }

    @Test fun theSelectionClearsAfterFifteenSecondsOfNothing() = runTest {
        val h = started("key")
        h.flow.tapPiece(5); runCurrent()
        advanceTimeBy(14900); runCurrent()
        assertEquals(5, h.st.selected)
        advanceTimeBy(200); runCurrent()
        assertNull(h.st.selected)
    }

    @Test fun solveThenPutBackEndsSolvedWithTheRightCounts() = runTest {
        for (id in listOf("key", "nudge", "hollow")) {
            val h = started(id)
            h.flow.solve(100); advanceTimeBy(60_000); runCurrent()
            assertEquals(id, Phase.APART_DONE, h.st.phase)
            assertEquals(h.st.lock.minMoves, h.st.steps)
            assertEquals(h.st.lock.minMoves, h.settings.best[id])
            h.flow.assemble(100); advanceTimeBy(60_000); runCurrent()
            assertEquals(id, Phase.SOLVED, h.st.phase)
            assertEquals(Pose.HOME, h.st.pose)
            assertEquals(h.st.lock.minMoves, h.st.result!!.backSteps)
            assertTrue(Sound.SOLVED in h.sounds)
        }
    }

    @Test fun aWrongPickInPutBackLiftsItTellsWhyAndLightsTheRightOne() = runTest {
        val h = started("key")
        h.flow.solve(100); advanceTimeBy(30_000); runCurrent()
        h.flow.startBack(); runCurrent()
        val right = h.st.nextBack!!.piece
        val wrong = (1..6).first { it != right }
        h.flow.tapPiece(wrong); runCurrent()
        assertTrue(h.st.anim is NopeAnim)
        assertEquals(setOf(right), h.st.glow)
        assertEquals(h.st.plan[0].reason, (h.st.line as Line.Why).reason)
        h.settle(600)
        assertEquals(Pose.APART, h.st.pose)
        assertEquals(0, h.st.planIndex)
        h.flow.tapPiece(right); h.settle(1400)
        assertEquals(1, h.st.planIndex)
        assertTrue(h.st.pose.isIn(right))
        assertEquals(Row3.BackInsert, Readout.row3(h.st, testScheduler.currentTime + 4000))
    }

    @Test fun inS10ATapOnTheTrayStartsPuttingBack() = runTest {
        val h = started("key")
        h.flow.solve(100); advanceTimeBy(30_000); runCurrent()
        assertEquals(Phase.APART_DONE, h.st.phase)
        val right = h.st.plan[0].piece
        h.flow.tapPiece(right); h.settle(1400)
        assertEquals(Phase.BACK, h.st.phase)
        assertEquals(1, h.st.planIndex)
    }

    @Test fun undoBringsTheStickBackAndCountsDown() = runTest {
        val h = started("key")
        h.flow.arrowOf(5, Dir.PZ); h.settle(1400)
        assertEquals(1, h.st.steps)
        h.flow.undo(); runCurrent()
        assertTrue(h.st.anim is PathAnim)
        h.settle(1400)
        assertEquals(0, h.st.steps)
        assertEquals(Pose.HOME, h.st.pose)
    }

    @Test fun whileOpenUpIsOnSticksDoNotMoveAndUndoClosesItFirst() = runTest {
        val h = started("key")
        h.flow.arrowOf(5, Dir.PZ); h.settle(1400)
        h.flow.setExplode(true); h.settle(700)
        h.flow.tapPiece(4); runCurrent()
        assertEquals(Line.CloseFirst, h.st.line)
        assertNull(h.st.selected)
        assertTrue(h.st.explodeFlashAt > 0)
        h.flow.undo(); h.settle(3000)
        assertFalse(h.st.explodeOn)
        assertEquals(0, h.st.steps)
    }

    @Test fun theHintShowsTheNextStepAndAGhostAndTheArrowStopsThere() = runTest {
        val h = started("nudge")
        h.flow.setHint(true); runCurrent()
        val hint = h.st.hint!!
        assertEquals(2, hint.piece); assertEquals(Dir.NY, hint.dir)
        assertEquals(V3i(0, -1, 0), hint.ghost)
        assertTrue(Readout.row3(h.st, testScheduler.currentTime) is Row3.Why)
        h.flow.arrowOf(2, Dir.NY); h.settle(1000)
        assertEquals(V3i(0, -1, 0), h.st.pose.at(2))
        assertEquals(6, h.st.hint!!.piece)
    }

    @Test fun aNamedStickThatShowsDoesNotTurnTheLock() = runTest {
        val h = started("key")
        h.flow.setHint(true); runCurrent()
        h.flow.arrowOf(5, Dir.PZ); h.settle(1200)
        // hint now names ④; with the three-quarter presentation it shows from the default eye, so the lock stays put
        assertEquals(4, h.st.hint!!.piece)
        assertNull(h.st.turnFrom)
        advanceTimeBy(1500); runCurrent()
        assertEquals(View(), h.st.view)
        assertFalse(h.st.autoTurned)
    }

    @Test fun aManualTurnInThisStepStopsTheAutoTurn() = runTest {
        val h = started("key")
        h.flow.arrowOf(5, Dir.PZ); h.settle(1200)
        h.flow.flip(); h.settle(500)
        val v = h.st.view
        h.flow.setHint(true); advanceTimeBy(2000); runCurrent()
        assertEquals(v, h.st.view)
    }

    @Test fun putBackWithTheHintOffNeverTurnsTheLockForTheNextStick() = runTest {
        val h = started("key")
        h.flow.solve(100); advanceTimeBy(30_000); runCurrent()
        h.flow.startBack(); runCurrent()
        val v = h.st.view
        for (i in 0 until 3) { h.flow.tapPiece(h.st.nextBack!!.piece); h.settle(1500); advanceTimeBy(1000); runCurrent() }
        assertEquals(v, h.st.view)
    }

    @Test fun resetWithProgressAsksFirst() = runTest {
        val h = started("key")
        h.flow.arrowOf(5, Dir.PZ); h.settle(1400)
        h.flow.requestReset(); runCurrent()
        assertEquals(ConfirmKind.RESET, h.st.confirm)
        assertTrue(h.st.inputLocked)
        h.flow.tapPiece(4); runCurrent()
        assertNull(h.st.selected)
        h.flow.confirm(); runCurrent()
        assertEquals(Pose.HOME, h.st.pose)
        assertNull(h.st.confirm)
        h.flow.requestLock(1); runCurrent()
        assertEquals(1, h.st.lockIndex) // no progress: no question
    }

    @Test fun restartPicksUpWhereItWas() = runTest {
        val h = started("nudge")
        h.flow.arrowOf(2, Dir.NY); h.settle(1000)
        h.flow.turn(1); h.settle(500)
        val again = Harness(this, h.settings, h.store)
        again.flow.start(); runCurrent()
        assertEquals("nudge", again.st.lock.id)
        assertEquals(h.st.pose, again.st.pose)
        assertEquals(View(1), again.st.view)
        assertEquals(Row3.Restored(1, false, 1, 0), Readout.row3(again.st, testScheduler.currentTime))
    }

    @Test fun restartInThePutBackPhase() = runTest {
        val h = started("key")
        h.flow.solve(100); advanceTimeBy(30_000); runCurrent()
        h.flow.startBack(); runCurrent()
        h.flow.tapPiece(h.st.nextBack!!.piece); h.settle(1500)
        val again = Harness(this, h.settings, h.store)
        again.flow.start(); runCurrent()
        assertEquals(Phase.BACK, again.st.phase)
        assertEquals(1, again.st.planIndex)
        assertEquals(h.st.pose, again.st.pose)
    }

    @Test fun firstRunShowsWelcomeThenTheCoachGivesOnlyTheCanonicalArrow() = runTest {
        val h = Harness(this, Settings(firstRunDone = false, coach = true))
        h.flow.start(); runCurrent()
        assertTrue(h.st.welcome)
        h.flow.welcomeDone(); runCurrent()
        assertTrue(h.settings.firstRunDone)
        assertEquals(5, h.st.coachGlow)
        h.flow.tapPiece(5); runCurrent()
        assertEquals(listOf(Dir.PZ), h.st.arrows)
    }

    @Test fun row2CountsFollowThePhase() = runTest {
        val h = started("nudge")
        assertEquals(Readout.Row2(false, 0, 0, 8, null), Readout.row2(h.st, null))
        h.flow.solve(100); advanceTimeBy(30_000); runCurrent()
        h.flow.startBack(); runCurrent()
        assertEquals(Readout.Row2(true, 1, 8, 8, 8), Readout.row2(h.st, 8))
    }

    @Test fun reducedMotionMakesMovesShort() = runTest {
        val h = started("key", Settings(firstRunDone = true, coach = false, reducedMotion = true))
        h.flow.arrowOf(5, Dir.PZ); runCurrent()
        assertEquals(GameFlow.REDUCED_MS, h.st.anim!!.ms)
    }
}
