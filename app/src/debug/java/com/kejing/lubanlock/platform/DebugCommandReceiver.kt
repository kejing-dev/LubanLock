package com.kejing.lubanlock.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kejing.lubanlock.domain.Dir
import com.kejing.lubanlock.domain.GameFlow
import com.kejing.lubanlock.domain.Language
import com.kejing.lubanlock.domain.Locks
import com.kejing.lubanlock.domain.Phase
import com.kejing.lubanlock.domain.Settings
import com.kejing.lubanlock.domain.Visibility
import com.kejing.lubanlock.domain.Rules
import com.kejing.lubanlock.ui.Containers
import com.kejing.lubanlock.ui.Page
import com.kejing.lubanlock.ui.Ui
import com.kejing.lubanlock.ui.WindowConfirm
import com.kejing.lubanlock.ui.board.BoardListener
import com.kejing.lubanlock.ui.board.BoardTargets
import com.kejing.lubanlock.ui.board.SceneHolder
import com.kejing.lubanlock.ui.board.input.BoardInputLog
import com.kejing.lubanlock.ui.board.input.InputSource
import com.kejing.lubanlock.ui.board.input.resolveUp
import com.kejing.lubanlock.ui.board.routeFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only (design verification.debugHooks). Every command goes through the same GameFlow calls the volume
 * and the window use (D29): taps go through routeFlow with the holder names a real hit resolves to.
 *
 *   adb shell am broadcast -a com.kejing.lubanlock.DEBUG -p com.kejing.lubanlock --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S18 (S16 a = "reset" | "lock" | "best" | "exit")   lock key|nudge|hollow   tap <n> | tap "tray <n>"
 *   arrow "<n> <+x|-x|+y|-y|+z|-z>"   hint on|off   slow   explode on|off   turn left|right|flip   undo   reset   confirm   no
 *   solve [ms]   assemble [ms]   stop   hold S04|S05|S06|S06b|S08|S12 | hold off   lang zh|en|system   sound on|off
 *   volume 0-2   reduced on|off   coach on|off   page play|rules|settings   welcome   reopen   closepanel   closevolume
 *   reopenvolume   s00hold on|off   testf on|off   replay "<holder> [hand-ray|eye-hand|controller]"   hover <holder>|off
 *   visible <n>   dump | state   evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        val f = a.split(" ").filter { it.isNotBlank() }
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun seq(block: suspend () -> Unit) { graph.scope.launch { block() } }
        suspend fun idle() { var n = 0; while ((flow.state.value.busy) && n++ < 400) delay(30) }
        suspend fun until(p: Phase) { var n = 0; while (flow.state.value.phase != p && n++ < 1000) delay(30); idle() }
        fun fresh(i: Int = flow.state.value.lockIndex) { flow.freeze(null); flow.stopSolve(); Ui.confirm = WindowConfirm.NONE; flow.gotoLock(i); Ui.page = Page.PLAY }
        suspend fun hold(at: Long, action: () -> Unit) { idle(); flow.freeze(at); action() }
        when (cmd) {
            "goto", "hold" -> when (a.substringBefore(" ").uppercase()) {
                "OFF" -> flow.freeze(null)
                "S00" -> Log.i(TAG, "S00 is the first 0.5 s after launch: s00hold on, force-stop and start, then capture")
                "S01" -> { settings { it.copy(firstRunDone = false) }; fresh(0); flow.start() }
                "S02" -> fresh()
                "S03" -> seq { fresh(0); idle(); flow.freeze(60_000); routeFlow(flow, "piece:5") } // held: the 6 s deselect waits for `hold off`
                "S04" -> seq { fresh(1); idle(); hold(GameFlow.SLIDE_MS_PER_CELL / 2) { flow.arrowOf(2, Dir.NY) } }
                "S05" -> seq { fresh(0); idle(); hold(GameFlow.BUMP_MS / 4) { routeFlow(flow, "piece:3") } }
                "S06" -> seq { fresh(0); idle(); hold(380) { flow.arrowOf(5, Dir.PZ) } }
                "S06B" -> seq { fresh(0); idle(); flow.arrowOf(5, Dir.PZ); idle(); delay(200); hold(900) { routeFlow(flow, "piece:5") } }
                "S07" -> seq { fresh(); idle(); flow.setHint(true) }
                "S08" -> seq { fresh(1); idle(); flow.setHint(true); delay(300); flow.slow(true); delay(450); flow.freeze(450) }
                "S09" -> seq { fresh(); idle(); flow.setExplode(true) }
                "S10" -> seq { fresh(); idle(); flow.solve(250); until(Phase.APART_DONE) }
                "S11" -> seq { fresh(); idle(); flow.solve(250); until(Phase.APART_DONE); flow.startBack() }
                "S12" -> seq { fresh(); idle(); flow.solve(250); until(Phase.APART_DONE); flow.startBack(); idle(); val b = flow.state.value.nextBack ?: return@seq; hold(420) { routeFlow(flow, "piece:${b.piece}") } }
                "S13" -> seq { fresh(); idle(); flow.solve(250); until(Phase.APART_DONE); flow.assemble(250) }
                "S14" -> Ui.page = Page.SETTINGS
                "S15" -> Ui.page = Page.RULES
                "S16" -> when (f.getOrNull(1)) {
                    "exit" -> { Ui.page = Page.SETTINGS; Ui.confirm = WindowConfirm.EXIT }
                    "best" -> { Ui.page = Page.SETTINGS; Ui.confirm = WindowConfirm.CLEAR_BEST }
                    "lock" -> seq { fresh(); idle(); val m = Rules(flow.state.value.lock).arrowMoves(flow.state.value.pose).first(); flow.arrowOf(m.piece, m.dir); idle(); Ui.page = Page.PLAY; flow.requestLock((flow.state.value.lockIndex + 1) % Locks.ALL.size) }
                    else -> seq { fresh(); idle(); val m = Rules(flow.state.value.lock).arrowMoves(flow.state.value.pose).first(); flow.arrowOf(m.piece, m.dir); idle(); Ui.page = Page.PLAY; flow.requestReset() }
                }
                "S17" -> Log.i(TAG, "S17: make a move, force-stop, relaunch")
                "S18" -> graph.window.closeControlOnly()
                else -> Log.w(TAG, "unknown state $a")
            }
            "lock" -> Locks.ALL.indexOfFirst { it.id == a }.takeIf { it >= 0 }?.let { fresh(it) }
            "tap" -> if (f.getOrNull(0) == "tray") routeFlow(flow, "piece:${f.getOrNull(1)}") else routeFlow(flow, if (":" in a) a else "piece:$a")
            "arrow" -> { val p = f.getOrNull(0)?.toIntOrNull(); val d = f.getOrNull(1)?.let { Dir.parse(it) }
                if (p != null && d != null) seq { if (flow.state.value.selected != p) routeFlow(flow, "piece:$p"); delay(120); routeFlow(flow, "arrow:${d.code}") } }
            "undo" -> flow.undo()
            "hint" -> flow.setHint(on)
            "slow" -> flow.slow()
            "explode" -> flow.setExplode(on)
            "turn" -> when (a) { "left" -> flow.turn(-1); "right" -> flow.turn(1); "flip" -> flow.flip() }
            "reset" -> flow.requestReset()
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "solve" -> flow.solve(a.toLongOrNull() ?: 400L)
            "assemble" -> flow.assemble(a.toLongOrNull() ?: 400L)
            "stop" -> flow.stopSolve()
            "best" -> { val n = a.toIntOrNull(); settings { it.copy(best = if (n == null) emptyMap() else it.best + (flow.state.value.lock.id to n)) } }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> settings { it.copy(coach = on) }
            "page" -> Ui.page = when (a) { "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.confirm = WindowConfirm.EXIT }
            "welcome" -> { flow.welcomeDone(); Ui.page = Page.PLAY }
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "closevolume" -> graph.window.closeVolumeOnly()
            "reopenvolume" -> graph.window.reopenVolume()
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "testf" -> { SceneHolder.testF = on; SceneHolder.rebuild?.invoke() }
            // replay <holder> [src]: the real scene entities a ray lands on for that holder (holder + deepest mesh)
            // through the template's resolveUp, then the deepest one into the same listener.onTap
            "replay" -> {
                val scene = SceneHolder.scene ?: run { Log.w(TAG, "REPLAY not ready"); return }
                val src = InputSource.entries.firstOrNull { it.label == f.getOrNull(1) } ?: InputSource.HAND_RAY
                var last: String? = null
                val ents = scene.replayEntities(f.getOrNull(0).orEmpty())
                if (ents.isEmpty()) BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} no entity")
                for (e in ents) {
                    val (t, path) = resolveUp(e, BoardTargets())
                    BoardInputLog.write(TAG, "REPLAY ${f.getOrNull(0)} src=${src.label} entity=${e.getName()} path=$path -> square=${BoardTargets.squareOf(t, flow.state.value)}")
                    last = t
                }
                if (ents.isNotEmpty()) BoardListener(graph, scene).onTap(last, src, null)
            }
            "hover" -> SceneHolder.scene?.setHover(a.ifBlank { null }?.takeIf { it != "off" })
            "visible" -> a.toIntOrNull()?.let { p -> val st = flow.state.value; Log.i(TAG, "VISIBLE ${p} area=${Visibility.area(Rules(st.lock), st.pose, st.view, p) * 1e4f} cm2 view=${st.view.code()}") }
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState} volume=${graph.window.volumeState}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"} containers=${Containers.BOARD}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
