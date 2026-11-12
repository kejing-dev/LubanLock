package com.kejing.lubanlock.domain

/** One take-apart step as it happened: the pose before and the move (consecutive slides of one stick one way merged). */
data class Done(val before: Pose, val move: Move)

/** One put-back step: [piece] goes [dir] ([insert] = from the tray into the lock), from [before] to [after]. */
data class BackStep(val piece: Int, val dir: Dir, val insert: Boolean, val cells: Int, val before: Pose, val after: Pose, val reason: Reason)

/**
 * Put back = your own take-apart in reverse (design gameSpec.putBack): every time the history comes back to a pose it
 * already passed, the loop in between is cut; what is left, reversed, is the plan. Every reversed step is a legal move,
 * so the lock always goes back together. Each step carries its sentence (INSERT_* / SHIFT_* templates).
 */
object PutBack {
    fun plan(rules: Rules, history: List<Done>, start: Pose = Pose.HOME): List<BackStep> {
        val poses = arrayListOf(start)
        val moves = ArrayList<Move>()
        var cur = start
        for (h in history) {
            val next = rules.apply(cur, h.move)
            val i = poses.indexOf(next)
            if (i >= 0) {
                while (poses.size > i + 1) poses.removeAt(poses.size - 1)
                while (moves.size > i) moves.removeAt(moves.size - 1)
            } else {
                poses += next; moves += h.move
            }
            cur = next
        }
        data class Raw(val piece: Int, val dir: Dir, val insert: Boolean, val cells: Int, val before: Pose, val after: Pose)
        val raw = (moves.indices.reversed()).map { i ->
            val m = moves[i]
            Raw(m.piece, m.dir.opposite, m.out, if (m.out) 0 else m.cells, poses[i + 1], poses[i])
        }
        return raw.mapIndexed { i, s ->
            val reason = if (s.insert) {
                if (i == raw.lastIndex) Reason(Kind.INSERT_LAST, s.piece, s.dir, key = rules.lock.piece(s.piece).isKey)
                else {
                    val path = swept(rules, s.after, s.piece, s.dir)
                    val beat = ArrayList<Int>()
                    for (t in raw.subList(i + 1, raw.size)) {
                        if (!t.insert || t.piece in beat) continue
                        val off = t.after.at(t.piece)!!
                        if (rules.lock.piece(t.piece).cells.any { (it + off) in path }) beat += t.piece
                    }
                    Reason(if (beat.isEmpty()) Kind.INSERT_CLEAR else Kind.INSERT_BEFORE, s.piece, s.dir, mustBeat = beat)
                }
            } else {
                val laterInsert = raw.subList(i + 1, raw.size).any { it.insert }
                if (laterInsert) {
                    val nx = raw[i + 1]
                    Reason(Kind.SHIFT_MAKEWAY, s.piece, s.dir, s.cells, n = nx.piece, nextIsInsert = nx.insert)
                } else Reason(Kind.SHIFT_CLOSE, s.piece, s.dir, s.cells)
            }
            BackStep(s.piece, s.dir, s.insert, s.cells, s.before, s.after, reason)
        }
    }

    /** Cells [p] sweeps coming in from far away along [dir] to its place in [target]. */
    private fun swept(rules: Rules, target: Pose, p: Int, dir: Dir): Set<V3i> {
        val off = target.at(p)!!
        val back = dir.opposite.v
        val out = HashSet<V3i>()
        for (k in 0..Rules.CLEAR_RUN) for (c in rules.lock.piece(p).cells) out += c + off + back * k
        return out
    }
}
