package io.embodit.lubanlock.domain

/** design gameSpec.reasonTemplates kinds. */
enum class Kind { KEY, OUT_FREED, OUT, INTO_GAP, SHIFT_FREED, SHIFT, SHIFT_BACK, NEUTRAL, INSERT_LAST, INSERT_BEFORE, INSERT_CLEAR, SHIFT_MAKEWAY, SHIFT_CLOSE, BLOCKED }

/**
 * Why one step works (design "每一步都说为什么"): the template kind and its parameters, all in lock coordinates. The
 * direction word is chosen when the sentence is shown, from the current turn of the lock ([Words]).
 *
 * [p] this stick, [q] the stick moved just before that used to block p this way, [n] the next stick of the hint search /
 * the plan, [mustBeat] put back: the later sticks that would block p's way in, [nextIsInsert] SHIFT_MAKEWAY wording.
 */
data class Reason(
    val kind: Kind,
    val p: Int,
    val dir: Dir,
    val cells: Int = 1,
    val q: Int? = null,
    val n: Int? = null,
    val mustBeat: List<Int> = emptyList(),
    val nextIsInsert: Boolean = false,
    val key: Boolean = false,
    val blockers: List<Int> = emptyList(),
    /** The next step of the hint search takes [n] out whole (for "it can come out itself" when n == p). */
    val nextOut: Boolean = false,
)

/**
 * Classifies a take-apart step (design reasonTemplates.rule): a removal is KEY / OUT_FREED / OUT; a slide says why only
 * when it brought the next removal closer (hint-search distance went down), otherwise NEUTRAL. [prevPose] / [prevMove]:
 * the step before (for "q just moved aside").
 */
class Reasons(val rules: Rules, val solver: Solver) {
    private val lock get() = rules.lock

    fun freedBy(prevPose: Pose?, prevMove: Move?, pose: Pose, m: Move): Int? {
        if (prevPose == null || prevMove == null || prevMove.piece == m.piece) return null
        val q = prevMove.piece
        return q.takeIf { it in rules.pathBlockers(prevPose, m.piece, m.dir, m.out) }
    }

    fun takeApart(prevPose: Pose?, prevMove: Move?, pose: Pose, m: Move): Reason {
        val after = rules.apply(pose, m)
        val q = freedBy(prevPose, prevMove, pose, m)
        val first = solver.search(after).first
        val n = first?.piece
        val nextOut = first?.out == true
        if (m.out) {
            val kind = when { lock.piece(m.piece).isKey -> Kind.KEY; q != null -> Kind.OUT_FREED; else -> Kind.OUT }
            return Reason(kind, m.piece, m.dir, q = q, n = n, nextOut = nextOut)
        }
        val before = solver.distance(pose)
        val now = solver.distance(after)
        if (now >= before) return Reason(Kind.NEUTRAL, m.piece, m.dir, m.cells, n = n, nextOut = nextOut)
        val off0 = pose.at(m.piece)!!
        val off1 = after.at(m.piece)!!
        val cells = lock.piece(m.piece).cells
        val was = cells.map { it + off0 }.toHashSet()
        val gap = cells.any { val c = it + off1; c !in was && c in lock.holes }
        val kind = when { gap -> Kind.INTO_GAP; q != null -> Kind.SHIFT_FREED; else -> Kind.SHIFT }
        return Reason(kind, m.piece, m.dir, m.cells, q = q, n = n, nextOut = nextOut)
    }

    /**
     * The hint's sentence for the next step from [pose] (design S07: the same template, no "next:" prefix). SHIFT_BACK
     * when the next step simply undoes [lastMove].
     */
    fun hint(prevPose: Pose?, lastMove: Move?, pose: Pose): Pair<Move, Reason>? {
        val m = solver.search(pose).first ?: return null
        if (lastMove != null && !lastMove.out && !m.out && m.piece == lastMove.piece && m.dir == lastMove.dir.opposite) {
            return m to Reason(Kind.SHIFT_BACK, m.piece, m.dir, 1)
        }
        return m to takeApart(prevPose, lastMove, pose, m)
    }

    fun blocked(pose: Pose, p: Int): Reason = Reason(Kind.BLOCKED, p, Dir.PX, blockers = rules.blockedBy(pose, p))
}

/** Direction words relative to the viewer (design: 往上 / 往下 / 往左 / 往右 / 朝你 / 往里). */
object Words {
    fun zh(world: Dir): String = when (world) { Dir.PX -> "往右"; Dir.NX -> "往左"; Dir.PY -> "往上"; Dir.NY -> "往下"; Dir.PZ -> "朝你"; Dir.NZ -> "往里" }
    fun en(world: Dir): String = when (world) { Dir.PX -> "right"; Dir.NX -> "left"; Dir.PY -> "up"; Dir.NY -> "down"; Dir.PZ -> "toward you"; Dir.NZ -> "away from you" }
    /** Key labels for the direction row. */
    fun shortEn(world: Dir): String = when (world) { Dir.PX -> "Right"; Dir.NX -> "Left"; Dir.PY -> "Up"; Dir.NY -> "Down"; Dir.PZ -> "Toward"; Dir.NZ -> "Away" }
    private val ZH_NUM = listOf("零", "一", "两", "三", "四")

    private fun list(ids: List<Int>, zh: Boolean): String {
        val l = ids.map { Piece.label(it) }
        return if (zh) {
            if (l.size <= 2) l.joinToString("、") else "${l[0]}、${l[1]} 等 ${l.size} 根"
        } else when (l.size) {
            1 -> l[0]
            2 -> "${l[0]} and ${l[1]}"
            else -> "${l[0]}, ${l[1]} and ${l.size - 2} more"
        }
    }

    /** The sentence for [r] with the lock turned by [view] (design reasonTemplates; English is built the same way). */
    fun say(r: Reason, view: View, zh: Boolean): String {
        val w = view.rot.dir(r.dir)
        val d = if (zh) zh(w) else en(w)
        val p = Piece.label(r.p)
        val q = r.q?.let { Piece.label(it) }
        val n = r.n?.let { Piece.label(it) }
        val self = r.n == r.p
        // n == p but its next step is another nudge, not the way out: never promise "it can come out itself"
        val selfZh = if (r.nextOut) "挪完它自己就能出来了。" else "下一步还要再挪它。"
        val selfEn = if (r.nextOut) "Then it can come out itself." else "It needs one more nudge next."
        val cellsZh = if (r.cells in 1..4) ZH_NUM[r.cells] else r.cells.toString()
        val stepsEn = if (r.cells == 1) "one step" else "${r.cells} steps"
        return if (zh) when (r.kind) {
            Kind.KEY -> "$p 身上一道槽都没有，是钥匙。别的五根都给它留了路，所以能直接${d}抽出来。"
            Kind.OUT_FREED -> "$q 刚让开，$p ${d}这条路空了，整根拿出来。"
            Kind.OUT -> "$p ${d}这条路空了，整根拿出来。"
            Kind.INTO_GAP -> "$p ${d}挪${cellsZh}格，挪进里面那个谁都不占的空格，" + if (self) selfZh else "给 $n 腾出路。"
            Kind.SHIFT_FREED -> "$q 刚挪开，$p 能${d}挪${cellsZh}格了，" + if (self) selfZh else "再给 $n 腾路。"
            Kind.SHIFT -> "$p ${d}挪${cellsZh}格，" + if (self) selfZh else "给 $n 腾出路。"
            Kind.SHIFT_BACK -> "把 $p ${d}退回一格，刚才那步没用上。"
            Kind.NEUTRAL -> "$p ${d}挪了${cellsZh}格，这一步没让谁的路变空。可以悔一步，或者换一根。"
            Kind.INSERT_LAST -> "最后放 $p${if (r.key) "（钥匙）" else ""}：它${d}一插到底，六根就全锁住了。"
            Kind.INSERT_BEFORE -> "现在放 $p，${d}推进去。要赶在 ${list(r.mustBeat, true)}${if (r.mustBeat.size >= 3) "" else " "}前面：${if (r.mustBeat.size == 1) "它" else "它们"}一进来就挡住 $p 的路。"
            Kind.INSERT_CLEAR -> "现在放 $p，${d}推进去，这条路是空的。"
            Kind.SHIFT_MAKEWAY -> if (r.nextIsInsert) "把 $p ${d}推一格，给 $n 让出路。" else "把 $p ${d}推一格，给 $n 腾出挪的地方。"
            Kind.SHIFT_CLOSE -> "把 $p ${d}推回一格，锁又合上一点。"
            Kind.BLOCKED -> when (r.blockers.size) {
                0 -> "$p 这边到头了，换个方向或者换一根试试。"
                1 -> "$p 动不了：${Piece.label(r.blockers[0])} 卡着它。换一根试试。"
                else -> "$p 动不了：${Piece.label(r.blockers[0])} 和 ${Piece.label(r.blockers[1])} 卡着它。换一根试试。"
            }
        } else when (r.kind) {
            Kind.KEY -> "$p has no cuts at all. It's the key: the other five are cut around it, so it slides straight out $d."
            Kind.OUT_FREED -> "$q just moved aside, so the way $d is clear. Pull $p out."
            Kind.OUT -> "The way $d is clear. Pull $p out."
            Kind.INTO_GAP -> "Nudge $p $d $stepsEn into the hidden gap. " + if (self) selfEn else "That makes room for $n."
            Kind.SHIFT_FREED -> "$q moved, so $p can slide $d $stepsEn. " + if (self) selfEn else "That clears the way for $n."
            Kind.SHIFT -> if (self) "Nudge $p $d $stepsEn. $selfEn" else "Nudge $p $d $stepsEn to make room for $n."
            Kind.SHIFT_BACK -> "Move $p back $d one step. That last move didn't help."
            Kind.NEUTRAL -> "$p moved $d $stepsEn, but nothing opened up. Undo, or try another."
            Kind.INSERT_LAST -> "$p goes in last${if (r.key) " (the key)" else ""}. Slide it in $d and all six lock up."
            Kind.INSERT_BEFORE -> "Now $p, sliding in $d. It has to beat ${list(r.mustBeat, false)}: once ${if (r.mustBeat.size == 1) "it is" else "they are"} in, its way is blocked."
            Kind.INSERT_CLEAR -> "Now $p, sliding in $d. The way is clear."
            Kind.SHIFT_MAKEWAY -> if (r.nextIsInsert) "Push $p $d one step to make way for $n." else "Push $p $d one step so $n has room to move."
            Kind.SHIFT_CLOSE -> "Push $p back $d one step. The lock closes up a bit more."
            Kind.BLOCKED -> when (r.blockers.size) {
                0 -> "$p can't go further this way. Try another way or another stick."
                1 -> "$p won't budge. ${Piece.label(r.blockers[0])} is holding it. Try another."
                else -> "$p won't budge. ${Piece.label(r.blockers[0])} and ${Piece.label(r.blockers[1])} are holding it. Try another."
            }
        }
    }

    /** "⑤ 能朝你走。点下面的方向键。" (design S03, rework v1.3: the keys, not the arrows), every legal direction listed. */
    fun canGo(p: Int, dirs: List<Dir>, view: View, zh: Boolean): String {
        val w = dirs.map { view.rot.dir(it) }
        return if (zh) "${Piece.label(p)} 能${w.joinToString("、") { zh(it) }}走。点下面的方向键。"
        else "${Piece.label(p)} can go ${w.joinToString(" or ") { en(it) }}. Tap a direction key."
    }
}
