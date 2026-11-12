package com.kejing.lubanlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** design verification: 装法单测：随机乱拆（含来回绕、悔步）50 次，去环倒放后都能装回原位. */
class PutBackTest {
    /** Plays the plan step by step with the real rules: every insert comes in along a clear path, every push fits. */
    private fun replay(r: Rules, plan: List<BackStep>): Pose {
        var pose = Pose.APART
        for (b in plan) {
            assertEquals(pose, b.before)
            if (b.insert) {
                // the reverse of a removal: from b.after the stick could leave along the opposite way
                assertTrue("insert ${b.piece} ${b.dir}", r.canExit(b.after, b.piece, b.dir.opposite))
            } else {
                var cur = pose
                repeat(b.cells) {
                    val n = cur.at(b.piece)!! + b.dir.v
                    assertTrue("push ${b.piece} ${b.dir}", n.maxAbs <= Rules.CAP && r.fits(b.piece, n, r.occupancy(cur, b.piece)))
                    cur = cur.with(b.piece, n)
                }
                assertEquals(b.after, cur)
            }
            pose = b.after
        }
        return pose
    }

    @Test fun fiftyRandomTakeApartsAllGoBackTogether() {
        val rnd = Random(20261110)
        var total = 0; var loops = 0; var undos = 0
        for (round in 0 until 50) {
            val lock = Locks.ALL[round % 3]
            val r = Rules(lock); val sv = Solver(r)
            var pose = Pose.HOME
            val hist = ArrayList<Done>()
            var guard = 0
            while (!pose.allOut && guard++ < 400) {
                // mostly random arrows (wandering, loops), sometimes an undo, sometimes the hint's move
                val roll = rnd.nextInt(10)
                if (roll == 0 && hist.isNotEmpty()) { pose = hist.removeAt(hist.lastIndex).before; undos++; continue }
                val m = if (roll < 6) r.arrowMoves(pose).randomOrNull(rnd) else sv.search(pose).first?.let { f -> r.arrow(pose, f.piece, f.dir, if (f.out) null else pose.at(f.piece)!! + f.dir.v) }
                if (m == null) continue
                val last = hist.lastOrNull()
                val after = r.apply(pose, m)
                if (last != null && last.move.piece == m.piece && last.move.dir == m.dir && !last.move.out) {
                    hist[hist.lastIndex] = Done(last.before, Move(m.piece, m.dir, last.move.cells + if (m.out) 0 else m.cells, m.out))
                } else hist += Done(pose, m)
                pose = after
            }
            assertTrue("round $round finished", pose.allOut)
            val plan = PutBack.plan(r, hist)
            if (plan.size < hist.size) loops++
            assertEquals("round $round ${lock.id}", Pose.HOME, replay(r, plan))
            total += plan.size
        }
        println("PUTBACK 50 rounds: plan steps $total, rounds with cut loops $loops, undos $undos")
        assertTrue(loops > 0)
    }

    @Test fun theCanonicalPlanIsTheReverseOfTheCanonicalTakeApart() {
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            var pose = Pose.HOME
            val hist = ArrayList<Done>()
            for (s in ReferenceData.TAKE_APART[lock.id]!!) {
                val m = if (s.dist == "out") Move(s.piece, Dir.parse(s.dir)!!, 1, true) else Move(s.piece, Dir.parse(s.dir)!!, s.dist.toInt(), false)
                hist += Done(pose, m); pose = r.apply(pose, m)
            }
            val plan = PutBack.plan(r, hist)
            assertEquals(lock.minMoves, plan.size)
            assertEquals(Pose.HOME, replay(r, plan))
            assertEquals(hist.reversed().map { it.move.piece }, plan.map { it.piece })
        }
    }
}
