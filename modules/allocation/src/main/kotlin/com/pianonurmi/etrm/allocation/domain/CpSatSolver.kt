package com.pianonurmi.etrm.allocation.domain

import com.google.ortools.Loader
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverStatus
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import com.google.ortools.sat.LinearExprBuilder
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaSubject
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import java.time.Duration

/** Limits of the exact solver; beyond them it hands the problem to the greedy one. */
data class ExactSolverSettings(
    /** Wall-clock budget for all three phases together. */
    val timeLimit: Duration = Duration.ofSeconds(10),
    /** Largest number of (class, obligation) pairs modelled; a bigger problem goes to greedy. */
    val maxPairs: Int = 200_000,
    val workers: Int = 4,
)

/**
 * Exact allocation with OR-Tools CP-SAT. The decision variable is the quantity of each eligible class assigned to each
 * obligation, with class supply, obligation demand and `MaxSharePerDevice` as hard limits. Three objectives are
 * solved in order, each one fixed before the next:
 * 1. cover as much demand as possible, priority tier by tier (a lower tier never gains at a higher tier's expense);
 * 2. miss as little of the `MinShare` quotas as possible;
 * 3. deliver at the lowest total cost (EUR per MWh times quantity, ADR 0035: what greedy sorts by).
 *
 * The greedy solution is the starting hint, and the result is never worse than greedy: the two are compared on the
 * three objectives in order (coverage per priority tier, then shortfall, then cost), and when the exact result is worse, or the solver
 * finds nothing in time, or the problem is too large, or the native library cannot load, the greedy result is
 * returned and the reason is reported.
 */
class CpSatSolver(
    private val evaluate: (BoundCriterion, CriteriaSubject) -> Boolean,
    private val settings: ExactSolverSettings = ExactSolverSettings(),
    private val greedy: Solver = GreedySolver(evaluate),
) : Solver {
    override val name: String = "CP_SAT"

    override fun solve(problem: AllocationProblem): List<Assignment> = solveDetailed(problem).assignments

    override fun solveDetailed(problem: AllocationProblem): SolveResult {
        val baseline = greedy.solve(problem)
        if (!nativeLoaded || !nativeUsable) return fallback(baseline, "native OR-Tools library not available")
        val pairs = problem.obligations.filter { it.demand > 0 }.sumOf { problem.eligible[it.index].cardinality().toLong() }
        if (pairs > settings.maxPairs) return fallback(baseline, "problem too large ($pairs pairs, limit ${settings.maxPairs})")
        if (pairs == 0L) return SolveResult(baseline, name, mapOf("status" to "TRIVIAL"))
        return try {
            ExactModel(problem, baseline).run()
        } catch (e: LinkageError) {
            // The library loaded but cannot be linked, e.g. extracted to a temp directory mounted noexec.
            log.error("OR-Tools native library is not usable; exact allocation uses greedy until restart", e)
            nativeUsable = false
            fallback(baseline, "native OR-Tools library not usable")
        }
    }

    /** The score as the integer the model minimises (four decimals). */
    private fun costOf(score: ScoreBreakdown): Long = score.total.movePointRight(SCORE_DECIMALS).setScale(0, RoundingMode.HALF_UP).longValueExact()

    /** The three objectives of a solution, in the order they are optimised. */
    internal data class Objectives(val coveredByTier: List<Long>, val shortfall: Long, val cost: Long) {
        /** The first objective on which this solution is worse than [other], or null when it is at least as good. */
        fun worseThan(other: Objectives): String? {
            coveredByTier.zip(other.coveredByTier).forEachIndexed { tier, (mine, theirs) ->
                if (mine != theirs) return if (mine < theirs) "coverage of priority tier ${tier + 1}" else null
            }
            return when {
                shortfall != other.shortfall -> if (shortfall > other.shortfall) "minimum-share shortfall" else null
                cost > other.cost -> "cost"
                else -> null
            }
        }
    }

    private fun fallback(baseline: List<Assignment>, reason: String): SolveResult {
        log.info("Exact allocation falls back to greedy: {}", reason)
        return SolveResult(baseline, greedy.name, mapOf("fallbackReason" to reason))
    }

    private inner class ExactModel(private val problem: AllocationProblem, private val baseline: List<Assignment>) {
        private val model = CpModel()
        private val deadline = System.nanoTime() + settings.timeLimit.toNanos()
        private val vars = mutableListOf<Pair>()
        private val shortfalls = mutableListOf<IntVar>()

        private inner class Pair(val cls: LotClass, val obl: ObligationModel, val x: IntVar, val score: ScoreBreakdown, val cost: Long)

        init {
            val byClass = HashMap<Int, MutableList<IntVar>>()
            problem.obligations.filter { it.demand > 0 }.forEach { obl ->
                val mine = problem.eligibleClasses(obl).map { cls ->
                    val score = problem.score(cls, obl)
                    val x = model.newIntVar(0, minOf(cls.quantity, obl.demand), "x_${cls.index}_${obl.index}")
                    byClass.getOrPut(cls.index) { mutableListOf() } += x
                    Pair(cls, obl, x, score, costOf(score))
                        .also { vars += it }
                }
                if (mine.isEmpty()) return@forEach
                model.addLessOrEqual(LinearExpr.sum(mine.map { it.x }.toTypedArray()), obl.demand)
                obl.maxSharePerDevice?.let { pct ->
                    val cap = obl.demand * pct / 100
                    mine.groupBy { it.cls.deviceGsrn }.values.forEach { group ->
                        model.addLessOrEqual(LinearExpr.sum(group.map { it.x }.toTypedArray()), cap)
                    }
                }
                obl.minShares.forEach { (bound, pct) ->
                    val quota = (obl.demand * pct + 99) / 100
                    val matching = mine.filter { evaluate(bound, it.cls.representative) }.map { it.x }
                    val shortfall = model.newIntVar(0, quota, "short_${obl.index}_${shortfalls.size}")
                    // shortfall >= quota - sum(matching)
                    model.addGreaterOrEqual(LinearExpr.sum((matching + shortfall).toTypedArray()), quota)
                    shortfalls += shortfall
                }
            }
            byClass.forEach { (index, xs) -> model.addLessOrEqual(LinearExpr.sum(xs.toTypedArray()), problem.classes[index].quantity) }
            hint(baseline)
        }

        private fun hint(assignments: List<Assignment>) {
            model.clearHints()
            val given = assignments.associate { (it.classIndex to it.obligationIndex) to it.quantityMwh }
            vars.forEach { model.addHint(it.x, given[it.cls.index to it.obl.index] ?: 0L) }
        }

        fun run(): SolveResult {
            val missed = LinearExpr.sum(shortfalls.toTypedArray())
            val cost = LinearExpr.weightedSum(vars.map { it.x }.toTypedArray(), vars.map { it.cost }.toLongArray())

            // Coverage tier by tier, highest priority first (the order coverage analysis grants supply in, ADR 0007):
            // a lower tier can never gain at a higher tier's expense.
            var best: kotlin.Pair<List<Assignment>, String>? = null
            val pending = tiers().toMutableList()
            while (pending.isNotEmpty()) {
                val tier = pending.first()
                model.maximize(tierCoverage(tier))
                best?.let { hint(it.first) }
                val solved = phase() ?: break
                best = solved
                model.addGreaterOrEqual(tierCoverage(tier), coveredIn(solved.first, tier))
                pending.removeAt(0)
            }
            var top: kotlin.Pair<List<Assignment>, String> = best ?: return give("no solution within the time limit")
            // Out of time before the last tier: hold the remaining tiers at what the best solution covers, so the
            // later phases cannot trade their coverage for cost.
            pending.forEach { tier -> model.addGreaterOrEqual(tierCoverage(tier), coveredIn(top.first, tier)) }

            if (shortfalls.isNotEmpty()) {
                model.minimize(missed)
                hint(top.first)
                val second = phase()
                if (second != null) {
                    top = second
                    model.addLessOrEqual(missed, shortfallOf(second.first))
                }
            }
            model.minimize(cost)
            hint(top.first)
            phase()?.let { top = it }

            // The floor: the result must be at least as good as greedy on all three objectives, compared in order.
            objectivesOf(top.first).worseThan(objectivesOf(baseline))?.let { objective ->
                return give("exact result is worse than greedy on $objective")
            }
            return SolveResult(
                top.first,
                name,
                mapOf("status" to top.second, "pairs" to vars.size, "wallTimeMs" to settings.timeLimit.toMillis() - remainingMs()),
            )
        }

        private fun tierCoverage(tier: Int): LinearExpr = LinearExpr.sum(vars.filter { it.obl.priority == tier }.map { it.x }.toTypedArray())

        private fun coveredIn(assignments: List<Assignment>, tier: Int): Long =
            assignments.filter { problem.obligations[it.obligationIndex].priority == tier }.sumOf { it.quantityMwh }

        private fun tiers(): List<Int> = problem.obligations.filter { it.demand > 0 }.map { it.priority }.distinct().sorted()

        private fun objectivesOf(assignments: List<Assignment>) = Objectives(
            coveredByTier = tiers().map { tier -> coveredIn(assignments, tier) },
            shortfall = shortfallOf(assignments),
            cost = assignments.sumOf { it.quantityMwh * costOf(it.score) },
        )

        private fun shortfallOf(assignments: List<Assignment>): Long = shortfalls.size.let {
            // Shortfall of a solution: recomputed from the assignments, the same way the model defines it.
            problem.obligations.sumOf { obl ->
                obl.minShares.sumOf { (bound, pct) ->
                    val quota = (obl.demand * pct + 99) / 100
                    val got = assignments.filter { a -> a.obligationIndex == obl.index && evaluate(bound, problem.classes[a.classIndex].representative) }
                        .sumOf { a -> a.quantityMwh }
                    maxOf(0L, quota - got)
                }
            }
        }

        private fun give(reason: String): SolveResult = fallback(baseline, reason)

        private fun remainingMs(): Long = maxOf(0L, (deadline - System.nanoTime()) / 1_000_000)

        /** One solve with the time left; null when no feasible solution was found. */
        private fun phase(): kotlin.Pair<List<Assignment>, String>? {
            val left = remainingMs()
            if (left <= 0) return null
            val solver = CpSolver()
            solver.parameters.maxTimeInSeconds = left / 1000.0
            solver.parameters.numWorkers = settings.workers
            solver.parameters.randomSeed = 0
            val status = solver.solve(model)
            if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) return null
            val assignments = vars.mapNotNull { p ->
                val q = solver.value(p.x)
                if (q > 0) Assignment(p.cls.index, p.obl.index, q, p.score) else null
            }.sortedWith(compareBy({ it.obligationIndex }, { it.classIndex }))
            return assignments to status.name
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(CpSatSolver::class.java)
        const val SCORE_DECIMALS = 4

        @Volatile
        var nativeUsable = true

        val nativeLoaded: Boolean by lazy {
            try {
                Loader.loadNativeLibraries()
                true
            } catch (e: LinkageError) {
                log.warn("OR-Tools native library could not be loaded; exact allocation uses greedy", e)
                false
            }
        }
    }
}
