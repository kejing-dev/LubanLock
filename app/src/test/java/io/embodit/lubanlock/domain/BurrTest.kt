package io.embodit.lubanlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance.mustPass: 规范解单测 / 求解器提示单测 / 合法方向 + 顶住句子单测 / 步数口径. */
class BurrTest {
    private fun refMove(r: Rules, pose: Pose, s: RefStep): Move {
        val d = Dir.parse(s.dir)!!
        return if (s.dist == "out") Move(s.piece, d, 1, true) else Move(s.piece, d, s.dist.toInt(), false)
    }

    @Test fun reachablePosesMatchTheProductSolver() {
        for (lock in Locks.ALL) assertEquals(lock.id, ReferenceData.REACHABLE[lock.id], Rules(lock).reachable().size)
    }

    @Test fun fewestStepsAre6_8_10() {
        for (lock in Locks.ALL) assertEquals(lock.id, lock.minMoves, Solver(Rules(lock)).minSteps())
        assertEquals(listOf(6, 8, 10), Locks.ALL.map { it.minMoves })
    }

    @Test fun firstRemovalFromTheStartIs1_3_3() {
        for (lock in Locks.ALL) assertEquals(lock.id, ReferenceData.FIRST_REMOVAL[lock.id], Solver(Rules(lock)).distance(Pose.HOME))
    }

    @Test fun theCanonicalTakeApartIsLegalAndEndsWithAllSixOnTheTray() {
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            var pose = Pose.HOME
            for (s in ReferenceData.TAKE_APART[lock.id]!!) {
                val m = refMove(r, pose, s)
                if (m.out) assertTrue("${lock.id} $m", r.canExit(pose, m.piece, m.dir))
                else {
                    val unit = r.unitMoves(pose).map { it.first }
                    assertTrue("${lock.id} $m legal", Move(m.piece, m.dir, 1, false) in unit)
                }
                pose = r.apply(pose, m)
            }
            assertTrue(lock.id, pose.allOut)
            assertEquals(lock.minMoves, ReferenceData.TAKE_APART[lock.id]!!.size)
        }
    }

    @Test fun theHintFromEveryCanonicalPoseIsLegalAndNeverLongerThanTheCanonicalRest() {
        for (lock in Locks.ALL) {
            val r = Rules(lock); val sv = Solver(r)
            var pose = Pose.HOME
            val steps = ReferenceData.TAKE_APART[lock.id]!!
            for ((i, s) in steps.withIndex()) {
                val res = sv.search(pose)
                val first = assertNotNull(res.first).let { res.first!! }
                assertTrue("${lock.id} step $i hint $first legal", first in r.unitMoves(pose).map { it.first })
                // canonical unit moves until the next removal
                val rest = steps.drop(i)
                val untilOut = rest.indexOfFirst { it.dist == "out" } + 1
                assertTrue("${lock.id} step $i hint distance ${res.distance} <= $untilOut", res.distance <= untilOut)
                pose = r.apply(pose, refMove(r, pose, s))
            }
        }
    }

    @Test fun atTheStartOfWheresTheKeyOnlyFiveMovesAndBothWaysAreOut() {
        val lock = Locks.byId("key")!!
        val r = Rules(lock)
        for (p in 1..6) {
            val dirs = r.legalDirs(Pose.HOME, p)
            if (p == 5) assertEquals(setOf(Dir.PZ, Dir.NZ), dirs.toSet()) else assertTrue("$p $dirs", dirs.isEmpty())
        }
        assertTrue(r.canExit(Pose.HOME, 5, Dir.PZ))
        assertTrue(lock.piece(5).isKey)
    }

    @Test fun theBlockedSentenceNamesTheSticksReallyInTheWay() {
        val lock = Locks.byId("key")!!
        val r = Rules(lock)
        assertEquals(listOf(1, 5), r.blockedBy(Pose.HOME, 3))
        val reason = Reasons(r, Solver(r)).blocked(Pose.HOME, 3)
        assertEquals("③ 动不了：① 和 ⑤ 卡着它。换一根试试。", Words.say(reason, View(), true))
        assertEquals("③ won't budge. ① and ⑤ are holding it. Try another.", Words.say(reason, View(), false))
        // every blocker named for every stuck stick in every reachable pose really is on a one-cell path
        for (l in Locks.ALL) {
            val rr = Rules(l)
            for (pose in rr.reachable()) for (p in pose.inLock) {
                if (rr.legalDirs(pose, p).isNotEmpty()) continue
                val named = rr.blockedBy(pose, p)
                val inWay = Dir.entries.flatMap { rr.blockersOneCell(pose, p, it) }.toSet()
                assertTrue("${l.id} ${pose.key()} $p $named", inWay.containsAll(named))
            }
        }
    }

    @Test fun anArrowSlidesToTheEndButStopsOnTheGhost() {
        val lock = Locks.byId("hollow")!!
        val r = Rules(lock)
        val m = r.arrow(Pose.HOME, 1, Dir.PX)!!
        assertEquals(Move(1, Dir.PX, 1, false), m)
        // the +-1 cap: from -1 an arrow can cross home to +1 (2 cells) unless the ghost stops it at home
        val p0 = Pose.HOME.with(1, V3i(-1, 0, 0))
        if (r.fits(1, V3i(-1, 0, 0), r.occupancy(Pose.HOME, 1)) && r.fits(1, V3i(1, 0, 0), r.occupancy(Pose.HOME, 1))) {
            assertEquals(2, r.arrow(p0, 1, Dir.PX)!!.cells)
            assertEquals(1, r.arrow(p0, 1, Dir.PX, stopAt = V3i.ZERO)!!.cells)
        }
    }
}
