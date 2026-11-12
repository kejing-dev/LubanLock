package com.kejing.lubanlock.domain

enum class Language { SYSTEM, ZH, EN }

/** App settings (SharedPreferences, design dataModel.settings). volume: 0 / 1 / 2 = 35 / 70 / 100 %. */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 1,
    val reducedMotion: Boolean = false,
    val firstRunDone: Boolean = false,
    /** First-game coach on 钥匙在哪 (design coach): ⑤'s end glows, one arrow only. */
    val coach: Boolean = true,
    /** Best take-apart steps per lock id. */
    val best: Map<String, Int> = emptyMap(),
    /** Locks put back together at least once. */
    val solved: Set<String> = emptySet(),
)

/** What a game looks like on disk (files/game.txt, design dataModel.game). The put-back plan is rebuilt from [history]. */
data class SavedGame(
    val lockId: String,
    val back: Boolean,
    val history: List<Move>,
    val planIndex: Int,
    val view: View,
    val hintOn: Boolean,
    val hintsUsed: Int,
)

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/** `lb1|<lock>|<A|B>|<p,dir,cells,o;...>|<planIndex>|<view>|<hint 0/1>|<hints used>`; every move is replayed and checked on load. */
object SaveCodec {
    fun encode(g: SavedGame): String = listOf(
        "lb1", g.lockId, if (g.back) "B" else "A",
        g.history.joinToString(";") { "${it.piece},${it.dir.code},${it.cells},${if (it.out) 1 else 0}" },
        g.planIndex.toString(), g.view.code(), if (g.hintOn) "1" else "0", g.hintsUsed.toString(),
    ).joinToString("|")

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size != 8 || f[0] != "lb1") return null
        val lock = Locks.byId(f[1]) ?: return null
        val moves = if (f[3].isBlank()) emptyList() else f[3].split(";").map { e ->
            val x = e.split(",")
            Move(x[0].toInt(), Dir.parse(x[1])!!, x[2].toInt(), x[3] == "1")
        }
        // replay: every move legal from the pose before it
        val rules = Rules(lock)
        var pose = Pose.HOME
        for (m in moves) {
            require(pose.isIn(m.piece))
            if (m.out) require(rules.canExit(pose, m.piece, m.dir)) else {
                var cur = pose
                repeat(m.cells) { val n = cur.at(m.piece)!! + m.dir.v; require(n.maxAbs <= Rules.CAP && rules.fits(m.piece, n, rules.occupancy(cur, m.piece))); cur = cur.with(m.piece, n) }
            }
            pose = rules.apply(pose, m)
        }
        val back = f[2] == "B"
        if (back) require(pose.allOut)
        val g = SavedGame(lock.id, back, moves, f[4].toInt(), View.parse(f[5])!!, f[6] == "1", f[7].toInt())
        require(g.planIndex >= 0)
        g
    }.getOrNull()
}
