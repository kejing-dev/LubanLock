package io.embodit.lubanlock.domain

/**
 * What the play page's row 3 says (design panels.play 第 3 行, eachMoveWhy): a step's why for 3 s, then "what to do now";
 * with the hint on, the next step's why. One pure function so every surface and the D38 unit test read the same source.
 */
sealed interface Row3 {
    data class Why(val reason: Reason) : Row3
    data class Turned(val piece: Int) : Row3
    data class Inspect(val piece: Int, val notches: Int) : Row3
    data class Selected(val piece: Int, val dirs: List<Dir>) : Row3
    data class Restored(val lockIndex: Int, val back: Boolean, val step: Int, val of: Int) : Row3
    data object CloseFirst : Row3
    data object Exploded : Row3
    data object Start : Row3
    data object BackInsert : Row3
    data object BackShift : Row3
    data object None : Row3
}

object Readout {
    fun row3(st: PlayState, now: Long): Row3 {
        val line = st.line?.takeIf { st.lineUntil > now || st.freezeMs != null }
        when (line) {
            is Line.Why -> return Row3.Why(line.reason)
            is Line.Turned -> return Row3.Turned(line.piece)
            is Line.Inspect -> return Row3.Inspect(line.piece, line.notches)
            is Line.CloseFirst -> return Row3.CloseFirst
            is Line.Selected -> if (st.selected == line.piece) return Row3.Selected(line.piece, line.dirs)
            null -> Unit
        }
        if (st.explodeOn) return Row3.Exploded
        if (st.hintOn && st.hint != null && (st.phase == Phase.APART || st.phase == Phase.BACK)) return Row3.Why(st.hint.reason)
        return when (st.phase) {
            Phase.APART -> if (st.restored && st.history.isNotEmpty()) Row3.Restored(st.lockIndex, false, st.steps, 0) else Row3.Start
            Phase.BACK -> {
                val b = st.nextBack ?: return Row3.None
                if (st.restored && st.planIndex > 0) Row3.Restored(st.lockIndex, true, st.planIndex + 1, st.plan.size)
                else if (b.insert) Row3.BackInsert else Row3.BackShift
            }
            else -> Row3.None
        }
    }

    /** Row 2: phase + step counters (design: 拆 · 第 3 步 · 最少 6 · 你最好 7 / 装 · 第 2 / 8 步). */
    data class Row2(val back: Boolean, val step: Int, val of: Int, val min: Int, val best: Int?)

    fun row2(st: PlayState, best: Int?): Row2 =
        if (st.phase == Phase.BACK) Row2(true, (st.planIndex + 1).coerceAtMost(st.plan.size), st.plan.size, st.lock.minMoves, best)
        else Row2(false, st.steps, 0, st.lock.minMoves, best)
}
