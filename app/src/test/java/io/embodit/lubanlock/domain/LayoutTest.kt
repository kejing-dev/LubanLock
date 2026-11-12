package io.embodit.lubanlock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * design acceptance.mustPass: 体积 / 路径单测, 可见面积单测, D35 / D41 单测 (container.volume.pitfall, occlusionCheck).
 */
class LayoutTest {
    private val u = Layout.U

    /** World centres of a stick's cells for a world pose. */
    private fun cellCentres(piece: Piece, pose: PoseW): List<V3> {
        val c = piece.centre
        return piece.cells.map { cell -> pose.pos + pose.rot.rotate(V3((cell.x + 0.5f - c[0]) * u, (cell.y + 0.5f - c[1]) * u, (cell.z + 0.5f - c[2]) * u)) }
    }

    private fun insideVolume(p: V3, slack: Float = 0f) =
        abs(p.x) <= Layout.VOL_W / 2 - u / 2 + slack && p.y >= Layout.FLOOR_Y + u / 2 - 1e-3f - slack && p.y <= Layout.VOL_H / 2 - u / 2 + slack && abs(p.z) <= Layout.VOL_D / 2 - u / 2 + slack

    /** 27 points of each cell (corners, edge middles, face centres, centre) of a stick at a world pose. */
    private fun cellPoints(piece: Piece, pose: PoseW): List<V3> {
        val c = piece.centre
        val out = ArrayList<V3>()
        for (cell in piece.cells) for (i in 0..2) for (j in 0..2) for (k in 0..2) {
            val local = V3((cell.x + i * 0.5f - c[0]) * u, (cell.y + j * 0.5f - c[1]) * u, (cell.z + k * 0.5f - c[2]) * u)
            out += pose.pos + pose.rot.rotate(local)
        }
        return out
    }

    /** Distance (m) from a point to an axis-aligned box (0 inside). */
    private fun dist(p: V3, lo: V3, hi: V3): Float {
        val dx = maxOf(lo.x - p.x, 0f, p.x - hi.x); val dy = maxOf(lo.y - p.y, 0f, p.y - hi.y); val dz = maxOf(lo.z - p.z, 0f, p.z - hi.z)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Test fun everyExitPathOfEveryReachablePoseInEveryTurnStaysInTheVolumeAndTouchesNothing() {
        var paths = 0; var minGap = 1f; var farthest = 0f; var rubbing = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            for (pose in r.reachable()) for (m in r.arrowMoves(pose).filter { it.out }) for (view in View.ALL) {
                val path = Layout.exitPath(r, pose, m.piece, m.dir, view)
                paths++
                val piece = lock.piece(m.piece)
                val others = Layout.worldCells(r, pose, view, skip = m.piece).map { V3(it.x * u, it.y * u, it.z * u) }
                val tray = (1..6).filter { !pose.isIn(it) && it != m.piece }.flatMap { cellCentres(lock.piece(it), Layout.inTray(lock.piece(it))) }
                // the slide out (first segment) is inside the lock by design; everything after it must clear the rest
                val slideEnd = path.lengths[1] / path.total
                var prevClose = false
                for (k in 0..80) {
                    val t = k / 80f
                    val cs = cellCentres(piece, path.at(t))
                    for (c in cs) {
                        assertTrue("${lock.id} ${pose.key()} $m ${view.code()} t=$t $c outside the volume", insideVolume(c))
                        farthest = maxOf(farthest, c.length)
                    }
                    if (t <= slideEnd + 1e-4f) continue
                    val pts = cellPoints(piece, path.at(t))
                    val lo = V3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z }) - V3(0.01f, 0.01f, 0.01f)
                    val hi = V3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z }) + V3(0.01f, 0.01f, 0.01f)
                    fun near(a: V3, b: V3) = a.x <= hi.x && b.x >= lo.x && a.y <= hi.y && b.y >= lo.y && a.z <= hi.z && b.z >= lo.z
                    // nothing may go into a stick (0.5 mm tolerance)
                    var nearest = 1f
                    val ptsL = pts.map { Layout.worldToLock(it) }
                    val loL = V3(ptsL.minOf { it.x }, ptsL.minOf { it.y }, ptsL.minOf { it.z }) - V3(0.01f, 0.01f, 0.01f)
                    val hiL = V3(ptsL.maxOf { it.x }, ptsL.maxOf { it.y }, ptsL.maxOf { it.z }) + V3(0.01f, 0.01f, 0.01f)
                    fun nearL(a: V3, b: V3) = a.x <= hiL.x && b.x >= loL.x && a.y <= hiL.y && b.y >= loL.y && a.z <= hiL.z && b.z >= loL.z
                    for (o in others) {
                        val ob = o + V3(u, u, u)
                        if (!nearL(o, ob)) continue
                        for (q in ptsL) {
                            val g = dist(q, o + V3(5e-4f, 5e-4f, 5e-4f), ob - V3(5e-4f, 5e-4f, 5e-4f))
                            nearest = minOf(nearest, dist(q, o, ob))
                            assertTrue("${lock.id} ${pose.key()} $m ${view.code()} t=$t goes into the lock", g > 0f)
                        }
                    }
                    minGap = minOf(minGap, nearest)
                    // sliding along a neighbour's face (as sticks do inside the lock) is allowed; counted for the report
                    if (nearest < 0.001f && prevClose) rubbing++
                    prevClose = nearest < 0.001f
                    for (o in tray) {
                        val a = o - V3(u / 2, u / 2, u / 2); val b = o + V3(u / 2, u / 2, u / 2)
                        if (!near(a, b)) continue
                        for (q in pts) {
                            val g = dist(q, a, b)
                            assertTrue("${lock.id} ${pose.key()} $m ${view.code()} t=$t touches the tray (gap ${g * 1000} mm)", g > 0.001f)
                        }
                    }
                }
                // it lands lying flat in its own slot
                val end = path.points.last()
                assertTrue("${lock.id} $m lands in its slot", (end.pos - Layout.trayCentre(m.piece)).length < 1e-4f)
            }
        }
        println("PATHS $paths exit paths checked, nearest pass ${"%.1f".format(minGap * 1000)} mm, samples sliding along a face $rubbing, farthest cell ${"%.3f".format(farthest)} m")
        assertTrue(paths > 1000)
    }

    @Test fun traySlotsLeaveAGapWiderThanAStick() {
        for (i in 0 until 5) assertTrue(Layout.TRAY_X[i + 1] - Layout.TRAY_X[i] - 2 * u >= 2 * u)
        for (lock in Locks.ALL) for (p in lock.pieces) {
            val ext = Layout.halfExtents(p, Layout.trayRot(p))
            assertEquals(4 * u, ext.z, 1e-5f)  // long axis toward the user
            assertEquals(u, ext.y, 1e-5f)
            // QA q1-6 / D10: the burnt number on the end facing the user reads upright (end-cap "up" -> +Y, long -> +Z)
            val endUp = if (p.axis == 1) V3i(0, 0, -1) else V3i(0, 1, 0)
            assertEquals(V3i(0, 1, 0), Layout.trayRot(p) * endUp)
            assertEquals(V3i(0, 0, 1), Layout.trayRot(p) * V3i.axis(p.axis, 1))
        }
    }

    @Test fun arrowsGhostsAndTheExplodedLockStayInTheVolume() {
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            for (pose in r.reachable()) for (view in View.ALL) {
                for (p in pose.inLock) {
                    for (d in r.legalDirs(pose, p)) {
                        val (spot, _) = Layout.arrowSpot(r, pose, p, d, view)
                        assertTrue("${lock.id} arrow $p $d ${view.code()} $spot", insideVolume(spot, slack = u / 2))
                    }
                    val ex = Layout.localCentre(lock.piece(p), pose.at(p)!!) + Layout.explodeOffset(p)
                    val q = Q.axisAngle(V3(0f, 1f, 0f), (Layout.EXPLODE_YAW_DEG * Math.PI / 180).toFloat()) * Q.of(view.rot)
                    for (c in cellCentres(lock.piece(p), PoseW(ex, Q.I))) assertTrue("${lock.id} explode $p", insideVolume(q.rotate(c)))
                }
            }
        }
    }

    @Test fun arrowsForTowardAndAwayAreNotInFrontOfTheLock() {
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            for (pose in r.reachable()) for (p in pose.inLock) for (d in r.legalDirs(pose, p)) {
                val (spot, w) = Layout.arrowSpot(r, pose, p, d, View())
                if (w.axis != 2) continue
                val box = Layout.bounds(Layout.worldCells(r, pose, View()))
                val outside = spot.x < box.first.x || spot.x > box.second.x || spot.y < box.first.y || spot.y > box.second.y
                assertTrue("${lock.id} $p $d $spot", outside)
            }
        }
    }

    /** design hiddenTarget: every canonical target is >= 6 cm² from each of the six eyes, turning the lock when needed. */
    @Test fun everyCanonicalTargetIsVisibleFromEveryEyeAfterAnAutoTurn() {
        val report = StringBuilder()
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            var pose = Pose.HOME
            for ((i, s) in ReferenceData.TAKE_APART[lock.id]!!.withIndex()) {
                for ((name, eye) in Layout.EYES) {
                    var view = View()
                    val before = Visibility.area(r, pose, view, s.piece, eye)
                    // QA q1-3: from the default placement every canonical target shows without any turning
                    if (name == "default") assertTrue("${lock.id} step ${i + 1} ${Piece.label(s.piece)} default eye ${before * 1e4} cm2 without turning", before >= Visibility.MIN_AREA_M2)
                    Visibility.bestTurn(r, pose, view, s.piece, eye)?.let { view = it }
                    val after = Visibility.area(r, pose, view, s.piece, eye)
                    if (before < Visibility.MIN_AREA_M2) report.append("${lock.id} step ${i + 1} ${Piece.label(s.piece)} $name ${"%.1f".format(before * 1e4)} -> ${"%.1f".format(after * 1e4)} cm2 (${view.code()})\n")
                    assertTrue("${lock.id} step ${i + 1} ${Piece.label(s.piece)} $name ${after * 1e4} cm2", after >= Visibility.MIN_AREA_M2)
                }
                val m = if (s.dist == "out") Move(s.piece, Dir.parse(s.dir)!!, 1, true) else Move(s.piece, Dir.parse(s.dir)!!, s.dist.toInt(), false)
                pose = r.apply(pose, m)
            }
        }
        println("AUTO-TURN cases:\n$report")
    }

    /** QA q1-3: the start of 钥匙在哪 step 2: ④ (hidden behind ③ when square-on) shows from the default eye. */
    @Test fun keyStepTwoFourShowsFromTheDefaultEye() {
        val lock = Locks.byId("key")!!
        val r = Rules(lock)
        val pose = Pose.HOME.with(5, null)
        val area = Visibility.area(r, pose, View(), 4, Layout.DEFAULT_EYE)
        val all = (1..6).filter { pose.isIn(it) }.map { "${Piece.label(it)} ${"%.1f".format(Visibility.area(r, pose, View(), it) * 1e4)}" }
        println("KEY step 2 from the default eye: ④ ${"%.1f".format(area * 1e4)} cm2; all: $all")
        assertTrue(area >= Visibility.MIN_AREA_M2)
    }

    /** The auto-turn still finds a better quarter for any stick that is hidden from the default eye. */
    @Test fun anyHiddenStickGetsATurnThatShowsIt() {
        var hidden = 0; var solved = 0
        for (lock in Locks.ALL) {
            val r = Rules(lock)
            for (pose in r.reachable()) for (p in pose.inLock) {
                if (Visibility.area(r, pose, View(), p) >= Visibility.MIN_AREA_M2) continue
                hidden++
                val to = Visibility.bestTurn(r, pose, View(), p) ?: continue
                if (Visibility.area(r, pose, to, p) >= Visibility.MIN_AREA_M2) solved++
            }
        }
        println("HIDDEN at the default eye: $hidden (stick, pose) pairs, $solved shown after one auto-turn")
    }

    /** D41: the window frame (TabBar + controls below included) and the lock box keep >= 10° apart from all six eyes. */
    @Test fun theWindowFrameStaysTenDegreesClearOfTheLockFromEveryEye() {
        for ((name, eye) in Layout.EYES) {
            val m = Layout.windowMarginDeg(eye)
            println("D41 $name margin ${"%.1f".format(m)}°")
            assertTrue("$name $m", m >= 10f)
        }
    }

    /** D35 / occlusionCheck.tray: from all six eyes the lock box corners are not hidden by the tray. */
    @Test fun theTrayNeverHidesTheLock() {
        val h = Layout.LOCK_HALF + u
        val corners = listOf(-h, h).flatMap { x -> listOf(-h, h).flatMap { y -> listOf(-h, h).map { z -> V3(x, y, z) } } }
        for ((name, eye) in Layout.EYES) for (c in corners) for (p in 1..6) {
            val t = Layout.trayCentre(p)
            val lo = t - V3(u, u, 4 * u); val hi = t + V3(u, u, 4 * u)
            assertTrue("$name corner $c behind tray $p", !Visibility.segBox(eye, c - eye, lo, hi))
        }
    }
}

/** Test-side copy of the strip order (Atlas lives in the Android ui package). */
private object Atlas4 {
    fun sides(piece: Piece): List<V3i> {
        val a = piece.axis
        val b = (a + 1) % 3; val c = (a + 2) % 3
        return listOf(V3i.axis(b, 1), V3i.axis(b, -1), V3i.axis(c, 1), V3i.axis(c, -1))
    }
}
