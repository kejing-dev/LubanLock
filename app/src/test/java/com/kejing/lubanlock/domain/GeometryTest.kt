package com.kejing.lubanlock.domain

import com.kejing.lubanlock.ui.board.Atlas
import com.kejing.lubanlock.ui.board.PieceGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stick meshes come straight from the data (design entities3d.pieces, budget): faces, colliders, UVs, no mirroring. */
class GeometryTest {
    @Test fun meshesCollidersAndBudgets() {
        val u = Layout.U
        var total = 0
        for (lock in Locks.ALL) {
            var lockTris = 0
            for (p in lock.pieces) {
                val g = PieceGeometry(p)
                val tris = g.outer.triangles + g.cut.triangles
                lockTris += tris
                assertTrue("${lock.id} ${p.label} $tris tris", tris <= 600)
                // every exposed cell face is drawn exactly once
                val exposed = p.cells.sumOf { c -> Dir.entries.count { (c + it.v) !in p.cellSet } }
                assertEquals(exposed * 2, tris)
                // colliders = the cells, merged into <= 12 boxes (D17: collider = visible body)
                assertTrue("${lock.id} ${p.label} ${g.boxes.size} boxes", g.boxes.size <= 12)
                val vol = g.boxes.sumOf { (_, s) -> (s.x * s.y * s.z / (u * u * u)).toDouble() }
                assertEquals(p.cells.size.toDouble(), vol, 1e-3)
                // UVs inside the texture
                for (uv in g.outer.uv + g.cut.uv) assertTrue(uv[0] in -1e-4f..1.0001f && uv[1] in -1e-4f..1.0001f)
                // the key has no notch walls
                if (p.isKey) assertEquals(0, g.cut.triangles)
            }
            assertTrue("${lock.id} $lockTris <= 6000", lockTris <= 6000)
            total += lockTris
        }
        println("GEOMETRY triangles per lock avg ${total / 3}")
    }

    /** Profile D10: the burnt numbers are never mirrored: on every face right x up = outward normal. */
    @Test fun numbersAreNeverMirrored() {
        for (axis in 0..2) {
            val l = V3i.axis(axis, 1)
            for (n in listOf(V3i(1, 0, 0), V3i(-1, 0, 0), V3i(0, 1, 0), V3i(0, -1, 0), V3i(0, 0, 1), V3i(0, 0, -1))) {
                val (r, up) = Atlas.frame(n, l)
                val c = V3i(r.y * up.z - r.z * up.y, r.z * up.x - r.x * up.z, r.x * up.y - r.y * up.x)
                assertEquals("axis $axis n $n", n, c)
                // on the vertical long faces of a lying stick the text stands upright
                if (n.y == 0 && axis != 1 && n != l && n != -l) assertEquals("upright on $n", 1, up.y)
            }
        }
    }
}
