package io.embodit.lubanlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance.mustPass: 「为什么」模板单测 + 走偏 NEUTRAL 单测 + 装法去环倒放单测. */
class ReasonTest {
    private fun refMove(s: RefStep): Move {
        val d = Dir.parse(s.dir)!!
        return if (s.dist == "out") Move(s.piece, d, 1, true) else Move(s.piece, d, s.dist.toInt(), false)
    }

    /** Walks the canonical take-apart; returns the history. */
    private fun canonical(lock: Lock): List<Done> {
        val r = Rules(lock)
        var pose = Pose.HOME
        val h = ArrayList<Done>()
        for (s in ReferenceData.TAKE_APART[lock.id]!!) { val m = refMove(s); h += Done(pose, m); pose = r.apply(pose, m) }
        return h
    }

    @Test fun takeApartSentencesEqualTheDesignReferenceInBothLanguages() {
        var n = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock); val rs = Reasons(r, Solver(r))
            var pose = Pose.HOME
            var prev: Done? = null
            for (s in ReferenceData.TAKE_APART[lock.id]!!) {
                val m = refMove(s)
                val reason = rs.takeApart(prev?.before, prev?.move, pose, m)
                assertEquals("${lock.id} ${s.zh}", s.kind, reason.kind.name)
                assertEquals("${lock.id} ${s.zh}", s.freedBy, reason.q)
                assertEquals("${lock.id} ${s.zh}", s.next, reason.n)
                assertEquals(s.zh, Words.say(reason, View(), true))
                assertEquals(s.en, Words.say(reason, View(), false))
                prev = Done(pose, m); pose = r.apply(pose, m); n++
            }
        }
        assertEquals(24, n)
    }

    @Test fun putBackPlanAndSentencesEqualTheDesignReference() {
        var n = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            val plan = PutBack.plan(r, canonical(lock))
            val ref = ReferenceData.PUT_BACK[lock.id]!!
            assertEquals(lock.id, ref.size, plan.size)
            for ((b, s) in plan.zip(ref)) {
                assertEquals(s.zh, s.piece, b.piece)
                assertEquals(s.zh, s.dir, b.dir.code)
                assertEquals(s.zh, s.kind, b.reason.kind.name)
                assertEquals(s.zh, s.mustBeat, b.reason.mustBeat)
                assertEquals(s.zh, Words.say(b.reason, View(), true))
                assertEquals(s.en, Words.say(b.reason, View(), false))
                n++
            }
        }
        assertEquals(24, n)
    }

    /**
     * Every legal move from every reachable pose (design rule): a slide that did not bring the next removal closer is
     * always NEUTRAL; "makes room for" only when the distance really went down; "it can come out itself" only when its
     * next step really takes it out.
     */
    @Test fun scanEveryReachablePoseAndMove() {
        var moves = 0; var neutral = 0; var selfOut = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock); val sv = Solver(r); val rs = Reasons(r, sv)
            for (pose in r.reachable()) {
                if (pose.allOut) continue
                val before = sv.distance(pose)
                for (m in r.arrowMoves(pose) + r.unitMoves(pose).map { it.first }.filter { !it.out }) {
                    val reason = rs.takeApart(null, null, pose, m)
                    val after = r.apply(pose, m)
                    moves++
                    if (m.out) { assertTrue(reason.kind in setOf(Kind.KEY, Kind.OUT, Kind.OUT_FREED)); continue }
                    val closer = sv.distance(after) < before
                    if (closer) assertTrue("${lock.id} ${pose.key()} $m ${reason.kind}", reason.kind in setOf(Kind.INTO_GAP, Kind.SHIFT, Kind.SHIFT_FREED))
                    else { assertEquals("${lock.id} ${pose.key()} $m", Kind.NEUTRAL, reason.kind); neutral++ }
                    if (reason.kind != Kind.NEUTRAL && reason.n == reason.p) {
                        val zh = Words.say(reason, View(), true)
                        val next = sv.search(after).first!!
                        if (zh.contains("它自己就能出来了")) { assertTrue("${lock.id} $m", next.piece == m.piece && next.out); selfOut++ }
                    }
                    if (reason.kind == Kind.INTO_GAP) {
                        val was = lock.piece(m.piece).cells.map { it + pose.at(m.piece)!! }.toSet()
                        assertTrue(lock.piece(m.piece).cells.any { val c = it + after.at(m.piece)!!; c !in was && c in lock.holes })
                    }
                }
            }
        }
        assertTrue("moves scanned $moves", moves > 300)
        assertTrue("some neutral moves exist", neutral > 0)
        println("REASON scan: $moves moves, $neutral NEUTRAL, $selfOut 'comes out itself'")
    }

    @Test fun theNeutralSentenceOnTheDesignExample() {
        // design T20: 里面是空的, before step 4 tap ④ to the right: distance 3 -> 4 (评审实算)
        val lock = Locks.byId("hollow")!!
        val r = Rules(lock); val sv = Solver(r); val rs = Reasons(r, sv)
        var pose = Pose.HOME
        for (s in ReferenceData.TAKE_APART["hollow"]!!.take(3)) pose = r.apply(pose, refMove(s))
        val m = r.arrow(pose, 4, Dir.PX)
        if (m != null && !m.out) {
            val reason = rs.takeApart(null, null, pose, m)
            assertEquals(Kind.NEUTRAL, reason.kind)
            assertTrue(Words.say(reason, View(), true).startsWith("④ 往右挪了一格，这一步没让谁的路变空"))
        }
    }

    @Test fun directionWordsFollowTheTurnedLock() {
        val lock = Locks.byId("key")!!
        val r = Rules(lock); val rs = Reasons(r, Solver(r))
        val reason = rs.takeApart(null, null, Pose.HOME, Move(5, Dir.PZ, 1, true))
        assertTrue(Words.say(reason, View(), true).contains("能直接朝你抽出来"))
        // [右转]: the face toward you swings right, so the key now leaves to the right
        assertTrue(Words.say(reason, View(1), true).contains("能直接往右抽出来"))
        assertTrue(Words.say(reason, View(3), false).contains("slides straight out left"))
        // [翻个面]: top toward you, so the old "toward you" now points down
        assertTrue(Words.say(reason, View(0, true), true).contains("能直接往下抽出来"))
    }

    @Test fun theHintSaysShiftBackWhenTheBestNextStepUndoesTheLastOne() {
        for (lock in Locks.ALL) {
            val r = Rules(lock); val sv = Solver(r); val rs = Reasons(r, sv)
            for (pose in r.reachable()) for ((m, after) in r.unitMoves(pose)) {
                if (m.out) continue
                val h = rs.hint(pose, m, after) ?: continue
                if (h.first.piece == m.piece && h.first.dir == m.dir.opposite && !h.first.out) assertEquals(Kind.SHIFT_BACK, h.second.kind)
                else assertFalse(h.second.kind == Kind.SHIFT_BACK)
            }
        }
    }
}
