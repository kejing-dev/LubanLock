package io.embodit.lubanlock.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The main states of design states[] that live in the game (S01 / S14 / S15 / S16 / S18 are window states). */
enum class Phase(val id: String) { LOADING("S00"), APART("S02"), APART_DONE("S10"), BACK("S11"), SOLVED("S13") }

enum class Sound { SLIDE, BLOCK, SNAP, WHOOSH, DROP, CLICK, APART, SOLVED, HINT, PUFF }

/** Something moving in the volume; the scene reads it every frame (never through Compose). */
sealed interface Anim {
    val piece: Int
    val startedAt: Long
    val ms: Long
}

/** A stick sliding in the lock from [from] to [to] (lock-frame offsets). */
data class SlideAnim(override val piece: Int, val from: V3i, val to: V3i, override val startedAt: Long, override val ms: Long) : Anim

/** A stick on the L-shaped way between the lock and its tray slot (out, or [reversed] back in). */
data class PathAnim(override val piece: Int, val path: Layout.Path, override val startedAt: Long, override val ms: Long) : Anim

/** S05: the stick shakes 2 mm twice along its long axis. */
data class BumpAnim(override val piece: Int, override val startedAt: Long, override val ms: Long) : Anim

/** S11 wrong pick: the stick lifts 1 cm and drops back. */
data class NopeAnim(override val piece: Int, override val startedAt: Long, override val ms: Long) : Anim

/** S06b: a tray stick lifts 3 cm and turns once about its long axis, notch faces red. */
data class InspectAnim(override val piece: Int, override val startedAt: Long, override val ms: Long) : Anim

enum class ConfirmKind { RESET, LOCK }

/** Row 3 of the play page when it is not "what to do now" (design panels.play). */
sealed interface Line {
    data class Why(val reason: Reason) : Line
    data class Turned(val piece: Int) : Line
    data class Inspect(val piece: Int, val notches: Int) : Line
    data object CloseFirst : Line
    data class Selected(val piece: Int, val dirs: List<Dir>) : Line
}

data class Result(val apartSteps: Int, val backSteps: Int, val min: Int, val best: Int, val newRecord: Boolean)

/** The next step the hint shows (ghost + arrow + why). */
data class Hint(val piece: Int, val dir: Dir, val out: Boolean, val ghost: V3i?, val reason: Reason)

/** The one state every surface reads (scene, control window, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val lockIndex: Int = 0,
    val pose: Pose = Pose.HOME,
    val history: List<Done> = emptyList(),
    val plan: List<BackStep> = emptyList(),
    val planIndex: Int = 0,
    val view: View = View(),
    val turnFrom: View? = null,
    val turnAt: Long = 0,
    val selected: Int? = null,
    val arrows: List<Dir> = emptyList(),
    val anim: Anim? = null,
    /** Gold outline (design outline): blockers 0.6 s, the right stick after a wrong pick, the slow replay's helper. */
    val glow: Set<Int> = emptySet(),
    val glowUntil: Long = 0,
    /** S01 / first game: ⑤'s end lights for 2 s. */
    val coachGlow: Int? = null,
    val coachGlowUntil: Long = 0,
    val coach: Boolean = false,
    val line: Line? = null,
    val lineUntil: Long = 0,
    val hintOn: Boolean = false,
    val hint: Hint? = null,
    val hintsUsed: Int = 0,
    /** M2 slow replay start (0 = off). */
    val slowAt: Long = 0,
    val explodeOn: Boolean = false,
    val explodeAt: Long = 0,
    /** The [拆开看] button flashes when a stick is tapped while the lock is open (design explodeRules). */
    val explodeFlashAt: Long = 0,
    val confirm: ConfirmKind? = null,
    val confirmLock: Int = 0,
    val welcome: Boolean = false,
    val restored: Boolean = false,
    val result: Result? = null,
    val solvedAt: Long = 0,
    /** This step: the player turned the lock / the lock turned itself (each at most once per step). */
    val manualTurn: Boolean = false,
    val autoTurned: Boolean = false,
    val settledAt: Long = 0,
    /** Debug hold: animations stop this many ms after their start. */
    val freezeMs: Long? = null,
) {
    val lock: Lock get() = Locks.ALL[lockIndex]
    val steps: Int get() = history.size
    val busy: Boolean get() = anim != null || turnFrom != null
    val inputLocked: Boolean get() = phase == Phase.LOADING || confirm != null || busy
    val canUndo: Boolean get() = !inputLocked && when (phase) {
        Phase.APART, Phase.APART_DONE -> history.isNotEmpty()
        Phase.BACK -> planIndex > 0
        else -> false
    }
    val hasProgress: Boolean get() = (phase == Phase.APART && history.isNotEmpty()) || phase == Phase.APART_DONE || phase == Phase.BACK
    val nextBack: BackStep? get() = if (phase == Phase.BACK || phase == Phase.APART_DONE) plan.getOrNull(planIndex) else null

    /** Design state id for logs / QA (main state + overlays). */
    val stateId: String get() {
        val main = when {
            confirm != null -> "S16"
            anim is SlideAnim || (anim is PathAnim && phase == Phase.APART) -> if (anim is PathAnim) "S06" else "S04"
            anim is BumpAnim -> "S05"
            anim is InspectAnim -> "S06b"
            anim is PathAnim || (anim is SlideAnim && phase == Phase.BACK) -> "S12"
            selected != null -> "S03"
            restored && phase == Phase.APART && line == null -> "S17"
            else -> phase.id
        }
        val m = buildString { if (hintOn) append("+M1"); if (slowAt > 0) append("+M2"); if (explodeOn) append("+M3") }
        return main + m
    }
}

/**
 * The middle layer (rules - state - render). Main-thread confined. The volume input, the panel buttons and the debug
 * broadcast all call these same functions (profile D29 / build.md).
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engine: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val SLIDE_MS_PER_CELL = 260L
        const val PATH_MS_MIN = 800L
        const val PATH_MS_MAX = 1200L
        const val BUMP_MS = 300L
        const val NOPE_MS = 450L
        const val INSPECT_MS = 2000L
        const val TURN_MS = 400L
        const val EXPLODE_MS = 600L
        const val GLOW_MS = 1200L
        const val RIGHT_GLOW_MS = 1500L
        const val LINE_MS = 3000L
        const val SELECT_MS = 15000L
        const val SETTLE_MS = 600L
        const val COACH_GLOW_MS = 2000L
        const val SLOW_RUN_MS = 1000L
        const val SLOW_RUNS = 3
        const val REDUCED_MS = 500L
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private var rules = Rules(Locks.ALL[0])
    private var solver = Solver(rules)
    private var reasons = Reasons(rules, solver)
    private var animJob: Job? = null
    private var turnJob: Job? = null
    private var selectJob: Job? = null
    private var slowJob: Job? = null
    private var autoJob: Job? = null
    private var pendingFinish: (() -> Unit)? = null
    private var solveJob: Job? = null
    /** `solve` plays the hint search's one-cell steps (an arrow would otherwise slide to the end of its run). */
    private var solving = false
    private var gen = 0

    private fun set(next: PlayState) { _state.value = next }
    private fun now() = clock()
    private val reduced: Boolean get() = settings().reducedMotion
    private fun sound(x: Sound) = onSound(x)

    fun rulesFor(): Rules = rules

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        val st = settings()
        val saved = store.load()
        if (!st.firstRunDone) {
            useLock(0)
            set(PlayState(phase = Phase.APART, lockIndex = 0, welcome = true, coach = st.coach))
            log("STATE S01 welcome")
            return
        }
        if (saved != null && restore(saved)) return
        useLock(0)
        set(PlayState(phase = Phase.APART, lockIndex = 0))
        log("STATE S02 fresh")
    }

    private fun useLock(i: Int) {
        rules = Rules(Locks.ALL[i])
        solver = Solver(rules)
        reasons = Reasons(rules, solver)
        // warm the hint search for every reachable pose off the main thread (first-tap latency, build.md 懒初始化)
        val r = rules; val sv = solver
        scope.launch { withContext(engine) { for (p in r.reachable()) sv.search(p) } }
    }

    private fun restore(g: SavedGame): Boolean {
        val i = Locks.ALL.indexOfFirst { it.id == g.lockId }.takeIf { it >= 0 } ?: return false
        useLock(i)
        var pose = Pose.HOME
        val hist = ArrayList<Done>()
        for (m in g.history) { hist += Done(pose, m); pose = rules.apply(pose, m) }
        val plan = if (g.back) PutBack.plan(rules, hist) else emptyList()
        var phase = if (g.back) Phase.BACK else if (pose.allOut) Phase.APART_DONE else Phase.APART
        var planIndex = 0
        if (g.back) {
            planIndex = g.planIndex.coerceIn(0, plan.size)
            for (k in 0 until planIndex) pose = plan[k].after
            if (planIndex == plan.size) phase = Phase.APART // finished games are not saved; start over
        }
        if (phase == Phase.APART && planIndex > 0) { pose = Pose.HOME; hist.clear() }
        set(PlayState(phase = phase, lockIndex = i, pose = pose, history = hist, plan = plan, planIndex = planIndex, view = g.view,
            hintOn = g.hintOn, hintsUsed = g.hintsUsed, restored = true))
        refreshHint()
        log("STATE S17 restored lock=${g.lockId} phase=$phase steps=${hist.size} plan=$planIndex/${plan.size} pose=${pose.key()}")
        return true
    }

    private fun save() {
        val st = s
        if (st.phase == Phase.LOADING || st.phase == Phase.SOLVED) { if (st.phase == Phase.SOLVED) store.clear(); return }
        store.save(SavedGame(st.lock.id, st.phase == Phase.BACK, st.history.map { it.move }, st.planIndex, st.view, st.hintOn, st.hintsUsed))
    }

    /** ON_STOP: the last settled pose is already on disk; write once more (design lifecycle 退后台落盘). */
    fun onBackground() { if (s.anim == null) save() }

    fun welcomeDone() {
        if (!s.welcome) return
        onSettings(settings().copy(firstRunDone = true))
        val t = now()
        val coach = settings().coach && s.lockIndex == 0 && s.history.isEmpty()
        set(s.copy(welcome = false, coach = coach, coachGlow = if (coach) firstCanonicalPiece() else null, coachGlowUntil = if (coach) t + COACH_GLOW_MS else 0))
        log("STATE S02 coach=$coach")
        save()
    }

    private fun firstCanonicalPiece(): Int = solver.search(Pose.HOME).first?.piece ?: 5

    // ------------------------------------------------------------------ taps in the volume

    /** A pinch / trigger on stick [p] (in the lock or on the tray). */
    fun tapPiece(p: Int) {
        val st = s
        if (st.welcome) welcomeDone()
        if (s.inputLocked || p !in 1..6) { log("TAP piece $p ignored (locked=${s.inputLocked})"); return }
        if (s.pose.isIn(p) && s.phase == Phase.APART) tapInLock(p)
        else if (s.phase == Phase.APART && !s.pose.isIn(p)) inspect(p)
        else if (s.phase == Phase.APART_DONE) { startBack(); tapBack(p) }
        else if (s.phase == Phase.BACK) tapBack(p)
    }

    private fun tapInLock(p: Int) {
        val st = s
        if (st.explodeOn) {
            set(st.copy(line = Line.CloseFirst, lineUntil = now() + LINE_MS, explodeFlashAt = now()))
            log("TAP $p while exploded -> close first")
            return
        }
        if (st.selected == p && st.arrows.size == 1) { tapArrow(st.arrows[0]); return }
        var dirs = rules.legalDirs(st.pose, p)
        if (st.coach && st.history.isEmpty()) {
            val first = solver.search(st.pose).first
            if (first != null && first.piece == p) dirs = dirs.filter { it == first.dir }
        }
        if (dirs.isEmpty()) { blocked(p); return }
        selectJob?.cancel()
        set(st.copy(selected = p, arrows = dirs, line = Line.Selected(p, dirs), lineUntil = now() + SELECT_MS))
        log("SELECT ${Piece.label(p)} dirs=${dirs.map { it.code }} S03")
        val g = gen
        selectJob = scope.launch { delay(SELECT_MS); if (g == gen && s.selected == p && s.anim == null && s.freezeMs == null) { set(s.copy(selected = null, arrows = emptyList(), line = null)); log("SELECT timeout -> S02") } }
    }

    private fun blocked(p: Int) {
        val r = reasons.blocked(s.pose, p)
        val t = now()
        set(s.copy(selected = null, arrows = emptyList(), anim = BumpAnim(p, t, BUMP_MS), glow = r.blockers.toSet(), glowUntil = t + GLOW_MS,
            line = Line.Why(r), lineUntil = t + LINE_MS))
        sound(Sound.BLOCK)
        log("BLOCKED ${Piece.label(p)} by ${r.blockers.map { Piece.label(it) }} S05")
        finishAfter(BUMP_MS) { set(s.copy(anim = null)); r.blockers.firstOrNull()?.let { named(it) } }
    }

    /** Tap on an arrow of the selected stick (design T2). */
    fun tapArrow(d: Dir) {
        val st = s
        val p = st.selected ?: return
        if (st.inputLocked || st.explodeOn || st.phase != Phase.APART || d !in st.arrows) return
        val h = st.hint
        val stopAt = if (st.hintOn && h != null && h.piece == p && h.dir == d && !h.out) h.ghost
        else if (solving) st.pose.at(p)!! + d.v else null
        val m = rules.arrow(st.pose, p, d, stopAt) ?: run { blocked(p); return }
        play(m)
    }

    /** Runs one take-apart move (the arrow, the debug `arrow`, `solve`). */
    private fun play(m: Move) {
        val st = s
        val prev = st.history.lastOrNull()
        val reason = reasons.takeApart(prev?.before, prev?.move, st.pose, m)
        val t = now()
        selectJob?.cancel()
        val anim: Anim
        val ms: Long
        if (m.out) {
            val path = Layout.exitPath(rules, st.pose, m.piece, m.dir, st.view)
            ms = if (reduced) REDUCED_MS else pathMs(path)
            anim = PathAnim(m.piece, path, t, ms)
            sound(Sound.WHOOSH)
        } else {
            val from = st.pose.at(m.piece)!!
            ms = if (reduced) REDUCED_MS else SLIDE_MS_PER_CELL * m.cells
            anim = SlideAnim(m.piece, from, from + m.dir.v * m.cells, t, ms)
            sound(Sound.SLIDE)
        }
        set(st.copy(selected = null, arrows = emptyList(), anim = anim, line = null, coachGlow = null))
        log("MOVE $m reason=${reason.kind} ${if (m.out) "S06" else "S04"}")
        finishAfter(ms) { settleApart(m, reason) }
    }

    private fun pathMs(path: Layout.Path): Long = (PATH_MS_MIN + (PATH_MS_MAX - PATH_MS_MIN) * (path.total / 0.9f).coerceIn(0f, 1f)).toLong()

    private fun settleApart(m: Move, reason: Reason) {
        val st = s
        val before = st.pose
        val pose = rules.apply(before, m)
        val last = st.history.lastOrNull()
        val hist = if (last != null && last.move.piece == m.piece && last.move.dir == m.dir && !last.move.out)
            st.history.dropLast(1) + Done(last.before, Move(m.piece, m.dir, last.move.cells + if (m.out) 0 else m.cells, m.out))
        else st.history + Done(before, m)
        val t = now()
        sound(if (m.out) Sound.DROP else Sound.SNAP)
        var next = st.copy(pose = pose, history = hist, anim = null, line = Line.Why(reason), lineUntil = t + LINE_MS, restored = false,
            manualTurn = false, autoTurned = false, settledAt = t, coach = st.coach && hist.isEmpty())
        if (pose.allOut) {
            val best0 = settings().best[st.lock.id]
            val best = minOf(best0 ?: Int.MAX_VALUE, hist.size)
            onSettings(settings().let { it.copy(best = it.best + (st.lock.id to best)) })
            next = next.copy(phase = Phase.APART_DONE, plan = PutBack.plan(rules, hist), planIndex = 0,
                result = Result(hist.size, 0, st.lock.minMoves, best, best0 == null || hist.size < best0), explodeOn = false)
            sound(Sound.APART)
            log("STATE S10 apart in ${hist.size} (min ${st.lock.minMoves})")
        }
        set(next)
        refreshHint()
        save()
        log("SETTLED pose=${pose.key()} steps=${hist.size} why=${reason.kind}")
    }

    /** S06b: a tray stick lifts and turns once to show its notches. */
    private fun inspect(p: Int) {
        if (s.explodeOn) { setExplode(false); scope.launch { delay(EXPLODE_MS); inspect(p) }; return }
        val t = now()
        set(s.copy(selected = null, arrows = emptyList(), anim = InspectAnim(p, t, INSPECT_MS), line = Line.Inspect(p, s.lock.piece(p).notches.size), lineUntil = t + LINE_MS))
        log("INSPECT ${Piece.label(p)} S06b")
        finishAfter(INSPECT_MS) { set(s.copy(anim = null)) }
    }

    // ------------------------------------------------------------------ put back

    /** S10 [开始装] (or a tap on the tray in S10). */
    fun startBack() {
        val st = s
        if (st.phase != Phase.APART_DONE || st.busy) return
        set(st.copy(phase = Phase.BACK, planIndex = 0, plan = if (st.plan.isEmpty()) PutBack.plan(rules, st.history) else st.plan, line = null, result = null))
        refreshHint()
        save()
        log("STATE S11 put back ${s.plan.size} steps")
    }

    private fun tapBack(p: Int) {
        val st = s
        val step = st.plan.getOrNull(st.planIndex) ?: return
        if (st.explodeOn) { setExplode(false); scope.launch { delay(EXPLODE_MS); tapBack(p) }; return }
        val t = now()
        if (p != step.piece) {
            set(st.copy(anim = NopeAnim(p, t, NOPE_MS), glow = setOf(step.piece), glowUntil = t + RIGHT_GLOW_MS, line = Line.Why(step.reason), lineUntil = t + LINE_MS))
            sound(Sound.BLOCK)
            log("BACK wrong ${Piece.label(p)} (next ${Piece.label(step.piece)})")
            finishAfter(NOPE_MS) { set(s.copy(anim = null)); named(step.piece) }
            return
        }
        val anim: Anim
        val ms: Long
        if (step.insert) {
            val path = Layout.exitPath(rules, step.after, p, step.dir.opposite, st.view).reversed()
            ms = if (reduced) REDUCED_MS else pathMs(path)
            anim = PathAnim(p, path, t, ms)
            sound(Sound.WHOOSH)
        } else {
            ms = if (reduced) REDUCED_MS else SLIDE_MS_PER_CELL * step.cells
            anim = SlideAnim(p, step.before.at(p)!!, step.after.at(p)!!, t, ms)
            sound(Sound.SLIDE)
        }
        set(st.copy(anim = anim, line = null))
        log("BACK ${Piece.label(p)} ${step.dir.code} insert=${step.insert} S12")
        finishAfter(ms) { settleBack(step) }
    }

    private fun settleBack(step: BackStep) {
        val st = s
        val idx = st.planIndex + 1
        val t = now()
        sound(Sound.CLICK)
        var next = st.copy(pose = step.after, planIndex = idx, anim = null, line = Line.Why(step.reason), lineUntil = t + LINE_MS, manualTurn = false, autoTurned = false, settledAt = t, restored = false)
        if (idx >= st.plan.size) {
            if (st.lock.piece(step.piece).isKey && step.insert) sound(Sound.DROP)
            sound(Sound.SOLVED)
            val best = settings().best[st.lock.id] ?: st.history.size
            onSettings(settings().let { it.copy(solved = it.solved + st.lock.id) })
            next = next.copy(phase = Phase.SOLVED, result = Result(st.history.size, st.plan.size, st.lock.minMoves, best, false), solvedAt = t, hintOn = false, hint = null)
            log("STATE S13 solved apart=${st.history.size} back=${st.plan.size}")
            store.clear()
            set(next)
            return
        }
        set(next)
        refreshHint()
        save()
    }

    // ------------------------------------------------------------------ undo / hint / slow / explode / turn

    fun undo() {
        val st = s
        if (!st.canUndo) return
        val t = now()
        if (st.phase == Phase.BACK) {
            val step = st.plan[st.planIndex - 1]
            val anim: Anim = if (step.insert) PathAnim(step.piece, Layout.exitPath(rules, step.after, step.piece, step.dir.opposite, st.view), t, if (reduced) REDUCED_MS else PATH_MS_MIN)
            else SlideAnim(step.piece, step.after.at(step.piece)!!, step.before.at(step.piece)!!, t, if (reduced) REDUCED_MS else SLIDE_MS_PER_CELL * step.cells)
            set(st.copy(anim = anim, line = null, selected = null, arrows = emptyList()))
            sound(Sound.SLIDE)
            log("UNDO back step ${st.planIndex}")
            finishAfter(anim.ms) { set(s.copy(anim = null, pose = step.before, planIndex = s.planIndex - 1, settledAt = now())); refreshHint(); save() }
            return
        }
        if (st.explodeOn) { setExplode(false); scope.launch { delay(EXPLODE_MS); undo() }; return }
        val last = st.history.last()
        val m = last.move
        val anim: Anim = if (m.out) PathAnim(m.piece, Layout.exitPath(rules, last.before, m.piece, m.dir, st.view).reversed(), t, if (reduced) REDUCED_MS else PATH_MS_MIN)
        else SlideAnim(m.piece, st.pose.at(m.piece)!!, last.before.at(m.piece)!!, t, if (reduced) REDUCED_MS else SLIDE_MS_PER_CELL * m.cells)
        set(st.copy(anim = anim, line = null, selected = null, arrows = emptyList()))
        sound(Sound.SLIDE)
        log("UNDO $m")
        finishAfter(anim.ms) {
            set(s.copy(anim = null, pose = last.before, history = s.history.dropLast(1), phase = Phase.APART, plan = emptyList(), result = null, settledAt = now(), manualTurn = false, autoTurned = false))
            refreshHint(); save()
        }
    }

    fun setHint(on: Boolean) {
        val st = s
        if (on == st.hintOn || st.phase == Phase.SOLVED || st.phase == Phase.LOADING) return
        set(st.copy(hintOn = on, hintsUsed = st.hintsUsed + if (on) 1 else 0, slowAt = 0))
        if (on) sound(Sound.HINT)
        refreshHint()
        log("HINT ${if (on) "on M1" else "off"}")
        save()
    }

    /** Recomputes the hint for the current pose; a new step "names" its stick (auto-turn if hidden). */
    private fun refreshHint() {
        val st = s
        if (!st.hintOn) { if (st.hint != null) set(st.copy(hint = null, slowAt = 0)); return }
        val h: Hint? = when (st.phase) {
            Phase.APART -> {
                val prev = st.history.lastOrNull()
                reasons.hint(prev?.before, prev?.move, st.pose)?.let { (m, r) ->
                    val ghost = if (m.out) null else st.pose.at(m.piece)!! + m.dir.v
                    Hint(m.piece, m.dir, m.out, ghost, r)
                }
            }
            Phase.BACK, Phase.APART_DONE -> st.plan.getOrNull(st.planIndex)?.let { b -> Hint(b.piece, b.dir, false, b.after.at(b.piece), b.reason) }
            else -> null
        }
        val changed = h?.piece != st.hint?.piece || h?.dir != st.hint?.dir || h?.ghost != st.hint?.ghost
        set(s.copy(hint = h, slowAt = if (changed) 0 else s.slowAt))
        if (h != null && changed) named(h.piece)
    }

    /** M2: the ghost walks its step three times at 1/4 speed (only with the hint on). */
    fun slow(on: Boolean = s.slowAt == 0L) {
        val st = s
        if (on && (!st.hintOn || st.hint == null)) return
        slowJob?.cancel()
        if (!on) { set(st.copy(slowAt = 0)); return }
        val t = now()
        val helper = st.hint?.reason?.let { it.q ?: it.n?.takeIf { n -> n != it.p } }
        set(st.copy(slowAt = t, glow = setOfNotNull(helper), glowUntil = t + SLOW_RUN_MS * SLOW_RUNS))
        log("SLOW M2 helper=$helper")
        slowJob = scope.launch { delay(SLOW_RUN_MS * SLOW_RUNS); if (s.slowAt == t && s.freezeMs == null) set(s.copy(slowAt = 0)) }
    }

    fun setExplode(on: Boolean) {
        val st = s
        if (on == st.explodeOn || st.busy || st.phase == Phase.LOADING || st.phase == Phase.SOLVED) return
        set(st.copy(explodeOn = on, explodeAt = now(), selected = null, arrows = emptyList()))
        sound(Sound.PUFF)
        log("EXPLODE ${if (on) "on M3" else "off"}")
    }

    /** [左转] -1 / [右转] +1 / [翻个面]: 0.4 s; the whole lock turns, the tray stays (design input.rotate). */
    fun turn(by: Int) = startTurn(s.view.turned(by), manual = true)

    fun flip() = startTurn(s.view.flip(), manual = true)

    private fun startTurn(to: View, manual: Boolean) {
        val st = s
        if (st.busy || st.phase == Phase.LOADING || st.confirm != null) return
        val t = now()
        set(st.copy(turnFrom = st.view, view = to, turnAt = t, selected = null, arrows = emptyList(),
            manualTurn = st.manualTurn || manual, autoTurned = st.autoTurned || !manual,
            line = if (manual) st.line else Line.Turned(autoPiece), lineUntil = if (manual) st.lineUntil else t + LINE_MS))
        log("TURN ${if (manual) "manual" else "auto"} -> ${to.code()}")
        turnJob?.cancel()
        val g = gen
        turnJob = scope.launch { delay(if (reduced) 1L else TURN_MS); if (g == gen) { set(s.copy(turnFrom = null)); save() } }
    }

    private var autoPiece = 0

    /**
     * A stick was "named" (design hiddenTarget): when it shows < 6 cm² from the seat, turn the lock once to the quarter
     * that shows it best. Never within 0.6 s of a settled step, never after a manual turn in this step, once per step;
     * in the put-back phase only with the hint on or after a wrong pick (callers decide).
     */
    private fun named(p: Int) {
        autoJob?.cancel()
        val g = gen
        autoJob = scope.launch {
            val wait = (s.settledAt + SETTLE_MS - now()).coerceAtLeast(0)
            if (wait > 0) delay(wait)
            while (s.busy) delay(50)
            if (g != gen) return@launch
            val st = s
            if (st.manualTurn || st.autoTurned || !st.pose.isIn(p) || st.phase == Phase.SOLVED) return@launch
            val area = Visibility.area(rules, st.pose, st.view, p)
            val to = Visibility.bestTurn(rules, st.pose, st.view, p)
            log("VISIBLE ${Piece.label(p)} area=${"%.1f".format(area * 1e4f)} cm2 view=${st.view.code()}${if (to != null) " -> auto turn ${to.code()}" else ""}")
            if (to != null) { autoPiece = p; startTurn(to, manual = false) }
        }
    }

    // ------------------------------------------------------------------ lock change / reset / confirm

    fun requestLock(i: Int) {
        val st = s
        if (i == st.lockIndex && st.phase != Phase.SOLVED) return
        if (i !in Locks.ALL.indices || st.busy) return
        if (st.hasProgress) { set(st.copy(confirm = ConfirmKind.LOCK, confirmLock = i)); log("CONFIRM lock $i S16"); return }
        gotoLock(i)
    }

    fun requestReset() {
        val st = s
        if (st.busy) return
        if (st.hasProgress) { set(st.copy(confirm = ConfirmKind.RESET)); log("CONFIRM reset S16"); return }
        gotoLock(st.lockIndex)
    }

    fun confirm() {
        val st = s
        when (st.confirm) {
            ConfirmKind.RESET -> gotoLock(st.lockIndex)
            ConfirmKind.LOCK -> gotoLock(st.confirmLock)
            null -> Unit
        }
    }

    fun cancelConfirm() { if (s.confirm != null) set(s.copy(confirm = null)) }

    /** A fresh lock [i] (S02). */
    fun gotoLock(i: Int) {
        gen++
        animJob?.cancel(); turnJob?.cancel(); selectJob?.cancel(); slowJob?.cancel(); autoJob?.cancel(); solveJob?.cancel()
        pendingFinish = null
        useLock(i)
        val keepHint = s.hintOn
        set(PlayState(phase = Phase.APART, lockIndex = i, hintOn = keepHint, freezeMs = s.freezeMs))
        store.clear()
        refreshHint()
        save()
        log("STATE S02 lock=${Locks.ALL[i].id}")
    }

    /** S13 [下一把] (the last lock: [再拆一次]). */
    fun next() { val i = s.lockIndex; gotoLock(if (i + 1 < Locks.ALL.size) i + 1 else i) }

    fun clearBest() { onSettings(settings().copy(best = emptyMap(), solved = emptySet())); log("BEST cleared") }

    // ------------------------------------------------------------------ timing

    /** Runs [block] when the current animation ends (held while a debug freeze is on). */
    private fun finishAfter(ms: Long, block: () -> Unit) {
        animJob?.cancel()
        val g = gen
        pendingFinish = block
        animJob = scope.launch {
            delay(ms)
            if (g != gen) return@launch
            if (s.freezeMs != null) return@launch // released by freeze(null)
            pendingFinish = null
            block()
        }
    }

    /** Debug hold: animations stop [ms] after their start (null releases them and runs what was waiting). */
    fun freeze(ms: Long?) {
        set(s.copy(freezeMs = ms))
        if (ms == null) { val f = pendingFinish; pendingFinish = null; if (f != null && s.anim != null) f() }
    }

    // ------------------------------------------------------------------ debug (same calls as the real input)

    /** A tap on an arrow of stick [p] (selects it first when the arrow belongs to the hint): the same calls as two taps. */
    fun arrowOf(p: Int, d: Dir) {
        if (s.selected != p) tapPiece(p)
        if (s.selected == p) tapArrow(d)
    }

    /** `solve`: the canonical take-apart (hint search moves) one tap pair every [ms]; `assemble`: the put back. */
    fun solve(ms: Long = 400L) {
        solveJob?.cancel()
        val g = gen
        solveJob = scope.launch {
            try {
                while (g == gen && s.phase == Phase.APART) {
                    while (s.busy) delay(30)
                    val m = solver.search(s.pose).first ?: break
                    solving = true
                    arrowOf(m.piece, m.dir)
                    solving = false
                    delay(ms)
                    while (s.busy) delay(30)
                }
            } finally { solving = false }
        }
    }

    fun assemble(ms: Long = 400L) {
        solveJob?.cancel()
        val g = gen
        solveJob = scope.launch {
            if (s.phase == Phase.APART_DONE) startBack()
            while (g == gen && s.phase == Phase.BACK) {
                while (s.busy) delay(30)
                val b = s.nextBack ?: break
                tapPiece(b.piece)
                delay(ms)
                while (s.busy) delay(30)
            }
        }
    }

    fun stopSolve() { solveJob?.cancel() }

    fun dump(): String {
        val st = s
        return "state=${st.stateId} lock=${st.lock.id} phase=${st.phase} pose=${st.pose.key()} steps=${st.steps} plan=${st.planIndex}/${st.plan.size} " +
            "view=${st.view.code()} sel=${st.selected} arrows=${st.arrows.map { it.code }} hint=${st.hint?.let { "${Piece.label(it.piece)}${it.dir.code}${if (it.out) " out" else ""} ${it.reason.kind}" }} " +
            "explode=${st.explodeOn} confirm=${st.confirm} anim=${st.anim?.javaClass?.simpleName} line=${st.line}"
    }
}
