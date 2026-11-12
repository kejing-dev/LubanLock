package com.kejing.lubanlock.domain

import kotlin.math.abs

/** An integer cell / offset in unit cells (u = 2.25 cm at the 2x scale). Cell (x, y, z) fills [x, x+1] x [y, y+1] x [z, z+1]. */
data class V3i(val x: Int, val y: Int, val z: Int) {
    operator fun plus(o: V3i) = V3i(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3i) = V3i(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Int) = V3i(x * k, y * k, z * k)
    operator fun unaryMinus() = V3i(-x, -y, -z)
    operator fun get(axis: Int): Int = when (axis) { 0 -> x; 1 -> y; else -> z }
    val maxAbs: Int get() = maxOf(abs(x), abs(y), abs(z))
    override fun toString() = "($x,$y,$z)"

    companion object {
        val ZERO = V3i(0, 0, 0)
        fun axis(axis: Int, k: Int) = when (axis) { 0 -> V3i(k, 0, 0); 1 -> V3i(0, k, 0); else -> V3i(0, 0, k) }
    }
}

/** The six slide directions, in the order every search and every "who blocks it" scan uses (design gameSpec). */
enum class Dir(val code: String, val v: V3i) {
    PX("+x", V3i(1, 0, 0)), NX("-x", V3i(-1, 0, 0)), PY("+y", V3i(0, 1, 0)), NY("-y", V3i(0, -1, 0)), PZ("+z", V3i(0, 0, 1)), NZ("-z", V3i(0, 0, -1));

    val axis: Int get() = ordinal / 2
    val sign: Int get() = if (ordinal % 2 == 0) 1 else -1
    val opposite: Dir get() = entries[ordinal xor 1]

    companion object {
        fun parse(s: String): Dir? = entries.firstOrNull { it.code == s.trim() || it.name.equals(s.trim(), true) }
        fun of(axis: Int, sign: Int): Dir = entries[axis * 2 + if (sign > 0) 0 else 1]
    }
}

enum class Wood { BEECH, CHERRY, WALNUT }

/**
 * One stick (design gameSpec.grid / pieceFrames): 2 x 2 x 8 cells along its long [axis] minus its [notches].
 * ① top / ② bottom along x, ③ front / ④ back along y, ⑤ left / ⑥ right along z.
 */
class Piece(val id: Int, val notches: Set<V3i>) {
    val axis: Int = AXIS[id - 1]
    val min: V3i = FRAME_MIN[id - 1]
    val max: V3i = min + V3i.axis(0, if (axis == 0) 8 else 2) + V3i.axis(1, if (axis == 1) 8 else 2) + V3i.axis(2, if (axis == 2) 8 else 2)
    val cells: List<V3i> = buildList {
        for (x in min.x until max.x) for (y in min.y until max.y) for (z in min.z until max.z) {
            val c = V3i(x, y, z)
            if (c !in notches) add(c)
        }
    }
    val cellSet: Set<V3i> = cells.toHashSet()
    val label: String get() = LABELS[id - 1]
    val isKey: Boolean get() = notches.isEmpty()

    /** Centre of the full 2 x 2 x 8 envelope (cells). */
    val centre: FloatArray get() = floatArrayOf((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)

    companion object {
        val LABELS = listOf("①", "②", "③", "④", "⑤", "⑥")
        private val AXIS = intArrayOf(0, 0, 1, 1, 2, 2)
        private val FRAME_MIN = listOf(V3i(-4, 0, -1), V3i(-4, -2, -1), V3i(-1, -4, 0), V3i(-1, -4, -2), V3i(-2, -1, -4), V3i(0, -1, -4))
        /** The pair direction of each stick for the exploded view (design spatialLayout.explode). */
        val EXPLODE = listOf(Dir.PY, Dir.NY, Dir.PZ, Dir.NZ, Dir.NX, Dir.PX)
        fun label(id: Int): String = LABELS[id - 1]
    }
}

class Lock(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val taglineZh: String,
    val taglineEn: String,
    val wood: Wood,
    val stars: Int,
    val minMoves: Int,
    val holes: Set<V3i>,
    notches: Map<Int, List<V3i>>,
) {
    val pieces: List<Piece> = (1..6).map { Piece(it, notches[it].orEmpty().toSet()) }
    fun piece(id: Int): Piece = pieces[id - 1]
}

/** Where every stick is: a whole-cell offset from home (each axis -1..+1 while it is in the lock), or null once it is out. */
data class Pose(val offsets: List<V3i?>) {
    init { require(offsets.size == 6) }
    fun at(p: Int): V3i? = offsets[p - 1]
    fun isIn(p: Int): Boolean = offsets[p - 1] != null
    fun with(p: Int, off: V3i?): Pose = Pose(offsets.toMutableList().also { it[p - 1] = off })
    val inLock: List<Int> get() = (1..6).filter { isIn(it) }
    val allOut: Boolean get() = offsets.all { it == null }
    val allHome: Boolean get() = offsets.all { it == V3i.ZERO }
    fun key(): String = offsets.joinToString(";") { it?.let { o -> "${o.x},${o.y},${o.z}" } ?: "-" }

    companion object {
        val HOME = Pose(List(6) { V3i.ZERO })
        val APART = Pose(List(6) { null })
        fun parse(s: String): Pose? = runCatching {
            Pose(s.split(";").map { f -> if (f == "-") null else f.split(",").map { it.toInt() }.let { V3i(it[0], it[1], it[2]) } })
        }.getOrNull()
    }
}

/** One step: [piece] slides [cells] whole cells toward [dir], or leaves the lock along [dir] when [out]. */
data class Move(val piece: Int, val dir: Dir, val cells: Int, val out: Boolean) {
    override fun toString() = "${Piece.label(piece)}${dir.code}${if (out) " out" else " $cells"}"
}

/**
 * The rules (design gameSpec.move): one stick slides along an axis by whole cells, never through another stick; a stick
 * still in the lock stays within +-1 cell of home on every axis; a stick whose path is clear forever along a direction
 * comes out whole. Pure, stateless, unit-tested.
 */
class Rules(val lock: Lock) {
    companion object {
        const val CAP = 1
        /** Far enough that any stick has left every other stick's span (8 cells long + 1 offset + margin). */
        const val CLEAR_RUN = 14
    }

    fun occupancy(pose: Pose, skip: Int? = null): HashMap<V3i, Int> {
        val o = HashMap<V3i, Int>(200)
        for (p in 1..6) {
            if (p == skip) continue
            val off = pose.at(p) ?: continue
            for (c in lock.piece(p).cells) o[c + off] = p
        }
        return o
    }

    fun fits(p: Int, off: V3i, occ: Map<V3i, Int>): Boolean = lock.piece(p).cells.none { (it + off) in occ }

    /** True when [p] can slide along [d] forever without touching anything (it comes out whole). */
    fun canExit(pose: Pose, p: Int, d: Dir, occ: Map<V3i, Int> = occupancy(pose, p)): Boolean {
        val off = pose.at(p) ?: return false
        for (k in 1..CLEAR_RUN) if (!fits(p, off + d.v * k, occ)) return false
        return true
    }

    /** The sticks in the way of [p] going one cell along [d] (sorted by id). */
    fun blockersOneCell(pose: Pose, p: Int, d: Dir, occ: Map<V3i, Int> = occupancy(pose, p)): List<Int> {
        val off = pose.at(p) ?: return emptyList()
        val n = off + d.v
        return lock.piece(p).cells.mapNotNull { occ[it + n] }.distinct().sorted()
    }

    /** Every stick anywhere on [p]'s path along [d] (one cell, or the whole run when it would come out). */
    fun pathBlockers(pose: Pose, p: Int, d: Dir, whole: Boolean): Set<Int> {
        val occ = occupancy(pose, p)
        val off = pose.at(p) ?: return emptySet()
        val out = HashSet<Int>()
        for (k in 1..(if (whole) CLEAR_RUN else 1)) {
            val n = off + d.v * k
            for (c in lock.piece(p).cells) occ[c + n]?.let { out += it }
        }
        return out
    }

    /** One-cell moves (the hint search's moves, design gameSpec.hint): a free cell within the cap, or out. */
    fun unitMoves(pose: Pose): List<Pair<Move, Pose>> {
        val out = ArrayList<Pair<Move, Pose>>()
        for (p in 1..6) {
            val off = pose.at(p) ?: continue
            val occ = occupancy(pose, p)
            for (d in Dir.entries) {
                if (canExit(pose, p, d, occ)) { out += Move(p, d, 1, true) to pose.with(p, null); continue }
                val n = off + d.v
                if (n.maxAbs > CAP || !fits(p, n, occ)) continue
                out += Move(p, d, 1, false) to pose.with(p, n)
            }
        }
        return out
    }

    /** Directions [p] can go right now (design gameSpec.legalDirections). */
    fun legalDirs(pose: Pose, p: Int): List<Dir> {
        val off = pose.at(p) ?: return emptyList()
        val occ = occupancy(pose, p)
        return Dir.entries.filter { d -> canExit(pose, p, d, occ) || ((off + d.v).maxAbs <= CAP && fits(p, off + d.v, occ)) }
    }

    /**
     * Tapping the arrow (design gameSpec.move): out if the way is clear forever; otherwise slide until blocked or at the
     * +-1 cap, or stop on [stopAt] (the hint ghost on this path). Null when it cannot move at all.
     */
    fun arrow(pose: Pose, p: Int, d: Dir, stopAt: V3i? = null): Move? {
        val off = pose.at(p) ?: return null
        val occ = occupancy(pose, p)
        if (canExit(pose, p, d, occ)) return Move(p, d, 1, true)
        var cur = off
        var k = 0
        while (true) {
            val n = cur + d.v
            if (n.maxAbs > CAP || !fits(p, n, occ)) break
            cur = n; k++
            if (stopAt != null && cur == stopAt) break
        }
        return if (k > 0) Move(p, d, k, false) else null
    }

    fun apply(pose: Pose, m: Move): Pose = if (m.out) pose.with(m.piece, null) else pose.with(m.piece, pose.at(m.piece)!! + m.dir.v * m.cells)

    /**
     * Design gameSpec.blocked: for each of the six directions the first stick in the way (lowest id), de-duplicated,
     * the first two. Empty when nothing blocks it (it is only held by the +-1 cap).
     */
    fun blockedBy(pose: Pose, p: Int): List<Int> {
        val occ = occupancy(pose, p)
        return Dir.entries.mapNotNull { d -> blockersOneCell(pose, p, d, occ).firstOrNull() }.distinct().take(2)
    }

    /** All poses reachable by arrow moves (design verified.reachableStatesCap1: 9 / 260 / 90). */
    fun reachable(from: Pose = Pose.HOME): Set<Pose> {
        val seen = hashSetOf(from)
        val q = ArrayDeque(listOf(from))
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            for (m in arrowMoves(u)) { val v = apply(u, m); if (seen.add(v)) q.addLast(v) }
        }
        return seen
    }

    /** Every arrow a player could tap from [pose]. */
    fun arrowMoves(pose: Pose): List<Move> = buildList {
        for (p in pose.inLock) for (d in Dir.entries) arrow(pose, p, d)?.let { add(it) }
    }
}
