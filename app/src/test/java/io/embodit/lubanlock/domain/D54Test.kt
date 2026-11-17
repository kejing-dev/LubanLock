package io.embodit.lubanlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Profile D54 "3D 主体撑满空间" (v0.1.3, user 2026-11-13 / scale picked 2026-11-16): the assembled lock fills the small
 * volume (longest edge >= 70 %, shortest >= 50 % of the volume's matching edge); everything shown while the volume is
 * small stays in it; anything that can leave the lock switches to the big volume; the window clears the big volume.
 */
class D54Test {
    private val u = Layout.U
    private val small = Layout.Vol.SMALL

    private fun inside(p: V3, v: Layout.Vol, slack: Float = 0f) =
        abs(p.x) <= v.w / 2 + slack && abs(p.y) <= v.h / 2 + slack && abs(p.z) <= v.d / 2 + slack

    /** All 8 corners of every cell of the sticks in the lock (world, presentation applied). */
    private fun corners(rules: Rules, pose: Pose, view: View): List<V3> = buildList {
        for (c in Layout.worldCells(rules, pose, view)) for (i in 0..1) for (j in 0..1) for (k in 0..1)
            add(Layout.lockToWorld(V3((c.x + i) * u, (c.y + j) * u, (c.z + k) * u)))
    }

    @Test fun theAssembledLockFillsTheSmallVolume() {
        for (lock in Locks.ALL) {
            val (lo, hi) = Layout.lockBounds(Rules(lock))
            val size = floatArrayOf(hi.x - lo.x, hi.y - lo.y, hi.z - lo.z)
            val vol = floatArrayOf(small.w, small.h, small.d)
            val ratio = FloatArray(3) { size[it] / vol[it] }
            val longest = size.indices.maxBy { size[it] }
            val shortest = size.indices.minBy { size[it] }
            println("D54 ${lock.id} lock ${"%.3f x %.3f x %.3f".format(size[0], size[1], size[2])} m in ${small.w} x ${small.h} x ${small.d} m: " +
                ratio.joinToString(" / ") { "%.0f %%".format(it * 100) })
            assertTrue("${lock.id} longest ${ratio[longest]}", ratio[longest] >= 0.70f)
            assertTrue("${lock.id} shortest ${ratio[shortest]}", ratio[shortest] >= 0.50f)
        }
    }

    /** User's pick (B, 4x): about 13° at the measured default eye, below D54's 25° (deviation, ui-audit). */
    @Test fun theLocksHorizontalAngleFromTheDefaultEye() {
        val eye = Layout.DEFAULT_EYE
        val (lo, hi) = Layout.lockBounds(Rules(Locks.ALL[0]))
        var minA = 1e9; var maxA = -1e9
        for (x in listOf(lo.x, hi.x)) for (z in listOf(lo.z, hi.z)) {
            val a = Math.toDegrees(atan2((x - eye.x).toDouble(), (eye.z - z).toDouble()))
            minA = minOf(minA, a); maxA = maxOf(maxA, a)
        }
        val deg = maxA - minA
        println("D54 horizontal angle from the default eye: ${"%.1f".format(deg)}° (2x was ${"%.1f".format(deg / Layout.K)}°)")
        assertTrue(deg in 12.0..14.0)
    }

    /** Every in-lock pose x 8 turns: the sticks stay in the small volume (a stick nudged one cell still fits). */
    @Test fun sticksInTheLockStayInTheSmallVolume() {
        var n = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            for (pose in r.reachable()) {
                if (pose.inLock.size < 6) continue
                for (view in View.ALL) for (c in corners(r, pose, view)) { n++; assertTrue("${lock.id} ${pose.key()} ${view.code()} $c", inside(c, small)) }
            }
        }
        assertTrue(n > 1000)
    }

    /**
     * Arrows shown while the volume is small (a picked stick none of whose ways takes it out, Layout.volumeFor) stay in
     * the small volume; a picked stick that can come out asks for the big one.
     */
    @Test fun arrowsShownInTheSmallVolumeStayInIt() {
        var shown = 0; var big = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            for (pose in r.reachable()) {
                if (pose.inLock.size < 6) continue
                for (view in View.ALL) for (p in pose.inLock) {
                    val dirs = r.legalDirs(pose, p)
                    val st = PlayState(pose = pose, selected = p, arrows = dirs)
                    if (Layout.volumeFor(st, r) == Layout.Vol.BIG) { big++; assertTrue(dirs.any { r.arrow(pose, p, it)?.out == true }); continue }
                    for (d in dirs) {
                        val (spotL, w) = Layout.arrowSpot(r, pose, p, d, view)
                        shown++
                        // both ends of the arrow, plus half its head width all round
                        for (s in listOf(-1f, 1f)) {
                            val tip = Layout.PRES_Q.rotate(spotL + V3.of(w.v, s * Layout.ARROW_LEN / 2))
                            assertTrue("${lock.id} ${pose.key()} arrow $p $d ${view.code()} $tip", inside(tip, small, slack = -0.6f * Layout.AU))
                        }
                    }
                }
            }
        }
        println("D54 small-volume arrows checked: $shown, picks that ask for the big volume: $big")
        assertTrue(shown > 50)
    }

    @Test fun anythingThatCanLeaveTheLockAsksForTheBigVolume() {
        val r = Rules(Locks.ALL[0])
        assertEquals(Layout.Vol.SMALL, Layout.volumeFor(PlayState(), r))
        assertEquals(Layout.Vol.BIG, Layout.volumeFor(PlayState(explodeOn = true), r))
        assertEquals(Layout.Vol.BIG, Layout.volumeFor(PlayState(pose = Pose.HOME.with(5, null)), r))
        val out = r.arrowMoves(Pose.HOME).first { it.out }
        assertEquals(Layout.Vol.BIG, Layout.volumeFor(PlayState(selected = out.piece, arrows = r.legalDirs(Pose.HOME, out.piece)), r))
    }

    /** The window stays where Placement.right(small volume) put it; that is outside the big volume's right face. */
    @Test fun theWindowStaysClearOfTheBigVolume() {
        val left = Layout.windowFrameLeft().x
        println("D54 window left edge ${"%.3f".format(left)} m, big volume face ${Layout.VOL_W / 2} m")
        assertTrue(left >= Layout.VOL_W / 2)
    }
}
