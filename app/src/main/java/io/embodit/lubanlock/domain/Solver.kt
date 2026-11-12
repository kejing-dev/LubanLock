package io.embodit.lubanlock.domain

/**
 * The hint search (design gameSpec.hint): breadth-first over one-cell moves (+-1 cap) from a pose until some stick can
 * come out whole. [distance] counts that removal as a move (start of "钥匙在哪" = 1, the other two = 3), and [first] is the
 * first move on the shortest path, found in a fixed order (sticks 1..6, directions +x -x +y -y +z -z) so the sentences are
 * reproducible. A few hundred poses at most (reachable 9 / 260 / 90); callers run it off the main thread.
 */
class Solver(val rules: Rules) {
    data class Result(val distance: Int, val first: Move?)

    private val cache = HashMap<Pose, Result>()

    fun search(pose: Pose): Result {
        if (pose.allOut) return Result(0, null)
        synchronized(cache) { cache[pose]?.let { return it } }
        val parent = HashMap<Pose, Pair<Pose, Move>?>()
        parent[pose] = null
        val q = ArrayDeque(listOf(pose))
        var found: Result? = null
        loop@ while (q.isNotEmpty()) {
            val u = q.removeFirst()
            for ((m, v) in rules.unitMoves(u)) {
                if (m.out) {
                    var first = m
                    var depth = 1
                    var x = u
                    while (true) {
                        val pm = parent[x] ?: break
                        first = pm.second; x = pm.first; depth++
                    }
                    found = Result(depth, first)
                    break@loop
                }
                if (v !in parent) { parent[v] = u to m; q.addLast(v) }
            }
        }
        val r = found ?: Result(Int.MAX_VALUE, null)
        synchronized(cache) { cache[pose] = r }
        return r
    }

    fun distance(pose: Pose): Int = search(pose).distance

    /**
     * The fewest steps to take the lock apart, counted like the game counts them (design gameSpec.stepCounting: one stick
     * one direction any distance = 1, a removal = 1): 6 / 8 / 10.
     */
    fun minSteps(from: Pose = Pose.HOME): Int {
        val seen = hashMapOf(from to 0)
        val q = ArrayDeque(listOf(from))
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u.allOut) return seen[u]!!
            for (p in u.inLock) for (d in Dir.entries) {
                var cur = u
                while (true) {
                    if (rules.canExit(cur, p, d)) { val v = cur.with(p, null); if (v !in seen) { seen[v] = seen[u]!! + 1; q.addLast(v) }; break }
                    val n = cur.at(p)!! + d.v
                    if (n.maxAbs > Rules.CAP || !rules.fits(p, n, rules.occupancy(cur, p))) break
                    cur = cur.with(p, n)
                    if (cur !in seen) { seen[cur] = seen[u]!! + 1; q.addLast(cur) }
                }
            }
        }
        return -1
    }
}
