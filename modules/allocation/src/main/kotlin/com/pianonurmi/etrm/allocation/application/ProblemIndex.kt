package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.AvailableLot
import com.pianonurmi.etrm.allocation.domain.LotClass
import com.pianonurmi.etrm.allocation.domain.LotClassifier
import com.pianonurmi.etrm.allocation.domain.ObligationModel
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.registry.api.TransferRoute
import java.time.LocalDate
import java.util.BitSet
import java.util.TreeMap
import java.util.TreeSet
import java.util.UUID

/**
 * The classes, obligations and eligibility of one problem, kept so that it can be changed in place (ADR 0043). A full
 * build creates it and publishes once; the portfolio cache keeps it and applies lot and obligation changes between
 * publications. Both paths share this code, so a patched problem is the one a full build would make:
 *
 * - **Classes** keep their index for life. A class that loses its last lot stays as a tombstone with no quantity and
 *   no eligibility; a lot of its key later revives it. A new class key is appended.
 * - **Eligibility** of a held class is the conjunction of three parts kept here: the matches of each distinct bound
 *   criterion, the registries that reach a target, and the expiry day. A row is cached per (criterion, delivery date,
 *   target registry); a new class gets its bit in every cached match and row, a new row is made from the parts.
 * - **Publishing** copies what readers see (the class list, the rows), so a published problem never changes.
 *
 * Not thread-safe: one writer at a time, and publishing must not overlap a change.
 */
class ProblemIndex internal constructor(
    private val criteria: CriteriaService,
    private val builder: ProblemBuilder,
    /** Route lookups, remembered for the life of the index. */
    val routeOf: (String, String) -> TransferRoute,
) {
    private data class RowKey(val criterion: String, val deliveryDate: LocalDate, val targetRegistryCode: String?)

    private class Row(val bound: BoundCriterion, val deliveryDay: Long, val targetRegistryCode: String?, val bits: BitSet)

    private data class BoundObligation(val version: Int, val criterionJson: String, val model: (Int, ObligationView) -> ObligationModel)

    private val classes = ArrayList<LotClass>()
    private val members = ArrayList<HashMap<UUID, AvailableLot>?>()
    private val indexOfKey = HashMap<String, Int>()

    /** Held classes in class key order: the order of a full build, so patched and built flows are the same graph. */
    private val keyOrder = TreeMap<String, Int>()
    private val futureIndexes = ArrayList<Int>()
    private var expiryDay = LongArray(INITIAL_CAPACITY)
    private val lots = HashMap<UUID, LotView>()
    private val classOfLot = HashMap<UUID, Int>()
    private val byRegistry = HashMap<String, BitSet>()
    private val reachable = HashMap<String?, BitSet>()
    private val matches = LinkedHashMap<BoundCriterion, BitSet>()
    private val rows = HashMap<RowKey, Row>()
    private val bound = HashMap<UUID, BoundObligation>()
    private var obligations: List<ObligationModel> = emptyList()
    private var rowOf: List<RowKey> = emptyList()
    private var softReserved: Map<UUID, Long> = emptyMap()

    /** Classes without lots, kept for their index. */
    var tombstones: Int = 0
        private set

    val classCount: Int get() = classes.size

    /** Live lots applied so far, held or not. */
    val lotCount: Int get() = lots.size

    /**
     * Against the holder's live lots now, by id and version: the lots that are new or changed, and those no longer
     * live. Only the changed ones need to be read in full.
     */
    fun diff(current: Map<UUID, Int>): Pair<Set<UUID>, List<UUID>> {
        val changed = current.filterTo(HashMap()) { (id, version) -> lots[id]?.version != version }.keys
        val removed = lots.keys.filter { it !in current }
        return changed to removed
    }

    internal fun load(live: List<LotView>, reserved: Map<UUID, Long>, future: List<ProblemBuilder.FutureLot>) {
        softReserved = reserved
        live.forEach { lots[it.id] = it }
        LotClassifier.classify(live, reserved).forEach { cls ->
            classes += cls
            members += cls.lots.associateByTo(HashMap()) { it.lot.id }
            indexOfKey[cls.key] = cls.index
            keyOrder[cls.key] = cls.index
            cls.lots.forEach { classOfLot[it.lot.id] = cls.index }
            setLive(cls)
        }
        future.filter { it.quantityMwh > 0 }.forEach { f ->
            val cls = LotClass(classes.size, "future:${f.future.ref}", listOf(AvailableLot(f.lot, f.quantityMwh)), f.future)
            classes += cls
            members += null
            futureIndexes += cls.index
            ensureCapacity(cls.index)
            expiryDay[cls.index] = cls.expiryDate.toEpochDay()
        }
    }

    /**
     * Lots as they are now: [changed] are new or changed lots of the holder in any status (only held ones count as
     * supply), [removed] are lots no longer live for it. Applied as a whole: every touched class is settled once.
     */
    fun applyLots(changed: Collection<LotView>, removed: Collection<UUID>) {
        require(futureIndexes.isEmpty()) { "only an index of held lots is patched" }
        val touched = HashSet<Int>()
        val wasLive = HashMap<Int, Boolean>()

        fun leave(id: UUID) {
            val c = classOfLot.remove(id) ?: return
            wasLive.putIfAbsent(c, classes[c].quantity > 0)
            members[c]!!.remove(id)
            touched += c
        }
        removed.forEach { id ->
            leave(id)
            lots.remove(id)
        }
        changed.forEach { lot ->
            leave(lot.id)
            if (lot.status !in LotStatus.LIVE) {
                lots.remove(lot.id)
                return@forEach
            }
            lots[lot.id] = lot
            val available = lot.quantityMwh - (softReserved[lot.id] ?: 0L)
            if (lot.status != LotStatus.HELD || available <= 0) return@forEach
            val c = indexOfKey[lot.classKey] ?: newClass(lot)
            wasLive.putIfAbsent(c, classes[c].quantity > 0)
            members[c]!![lot.id] = AvailableLot(lot, available)
            classOfLot[lot.id] = c
            touched += c
        }
        touched.forEach { c -> settle(c, wasLive[c] ?: false) }
    }

    /** The open obligations, replacing the previous ones. Criteria are bound again only for new or changed ones. */
    fun setObligations(open: List<ObligationView>) {
        val models = ArrayList<ObligationModel>()
        val keys = ArrayList<RowKey>()
        open.filter { it.remainingMwh > 0 }.forEach { o ->
            val cached = bound[o.id]?.takeIf { it.version == o.version }
                ?: BoundObligation(o.version, criteria.toJson(o.criterion), builder.modelFactory(o)).also { bound[o.id] = it }
            val model = cached.model(models.size, o)
            models += model
            val key = RowKey(cached.criterionJson, o.deliveryDate, o.targetRegistryCode)
            rows.getOrPut(key) {
                Row(model.bound, o.deliveryDate.toEpochDay(), o.targetRegistryCode, rowBits(model.bound, o.deliveryDate, o.targetRegistryCode))
            }
            keys += key
        }
        obligations = models
        rowOf = keys
        val ids = open.mapTo(HashSet()) { it.id }
        bound.keys.retainAll(ids)
        // Rows and matches nobody uses would only cost upkeep.
        val used = keys.toHashSet()
        rows.keys.retainAll(used)
        val bounds = rows.values.mapTo(HashSet()) { it.bound }
        matches.keys.retainAll(bounds)
    }

    /**
     * The problem as it stands, with copies of everything that changes, so readers of an earlier publication are not
     * disturbed. Obligations whose source is in [excludedSources] are left out (an amendment's own schedules).
     */
    fun publish(excludedSources: Set<UUID> = emptySet()): Published {
        val copies = HashMap<RowKey, BitSet>()
        val kept = obligations.indices.filter { obligations[it].obligation.sourceRef !in excludedSources }
        val models = kept.mapIndexed { i, o -> obligations[o].let { if (it.index == i) it else it.reindexed(i) } }
        val eligible = kept.map { o -> copies.getOrPut(rowOf[o]) { rows.getValue(rowOf[o]).bits.clone() as BitSet } }
        val order = IntArray(classes.size).also { order ->
            var i = 0
            keyOrder.values.forEach { order[i++] = it }
            futureIndexes.forEach { order[i++] = it }
        }
        return Published(ArrayList(classes), models, eligible, profiles(), order)
    }

    /** What a publication hands to [ProblemBuilder] to make an [AllocationProblem]. */
    class Published(
        val classes: List<LotClass>,
        val obligations: List<ObligationModel>,
        val eligible: List<BitSet>,
        val profiles: IntArray,
        val unitOrder: IntArray,
    )

    private fun newClass(lot: LotView): Int {
        val c = classes.size
        classes += LotClass(c, lot.classKey, listOf(AvailableLot(lot, 0)))
        members += HashMap()
        indexOfKey[lot.classKey] = c
        keyOrder[lot.classKey] = c
        ensureCapacity(c)
        expiryDay[c] = lot.expiryDate.toEpochDay()
        tombstones++
        return c
    }

    /** Rebuilds a touched class from its lots, and keeps its eligibility in step when it dies or comes back. */
    private fun settle(c: Int, wasLive: Boolean) {
        val lots = members[c]!!
        val cls = classes[c]
        if (lots.isEmpty()) {
            classes[c] = LotClass(c, cls.key, listOf(AvailableLot(cls.representative, 0)))
            if (wasLive) {
                setDead(c, cls.registryCode)
                tombstones++
            }
            return
        }
        classes[c] = LotClass(c, cls.key, lots.values.sortedWith(compareBy({ it.lot.expiryDate }, { it.lot.id })))
        if (!wasLive) {
            tombstones--
            setLive(classes[c])
        }
    }

    /** A held class takes part: its bit in its registry, in every match it satisfies and in every row it is in. */
    private fun setLive(cls: LotClass) {
        val c = cls.index
        ensureCapacity(c)
        expiryDay[c] = cls.expiryDate.toEpochDay()
        byRegistry.getOrPut(cls.registryCode) { BitSet() }.set(c)
        reachable.clear()
        matches.forEach { (b, bits) -> if (criteria.evaluate(b, cls.representative)) bits.set(c) }
        rows.values.forEach { row ->
            if (matches.getValue(row.bound).get(c) && reachableFrom(row.targetRegistryCode).get(c) && expiryDay[c] > row.deliveryDay) row.bits.set(c)
        }
    }

    private fun setDead(c: Int, registryCode: String) {
        byRegistry[registryCode]?.clear(c)
        reachable.clear()
        matches.values.forEach { it.clear(c) }
        rows.values.forEach { it.bits.clear(c) }
    }

    /**
     * A row from the parts: held classes that match [bound], reach the target and expire after the delivery date;
     * future classes (a few) by the rule of [ProblemBuilder.satisfies].
     */
    private fun rowBits(bound: BoundCriterion, deliveryDate: LocalDate, targetRegistryCode: String?): BitSet {
        val bits = matches.getOrPut(bound) {
            BitSet(classes.size).apply {
                keyOrder.values.forEach { c -> if (classes[c].quantity > 0 && criteria.evaluate(bound, classes[c].representative)) set(c) }
            }
        }.clone() as BitSet
        bits.and(reachableFrom(targetRegistryCode))
        val day = deliveryDate.toEpochDay()
        var c = bits.nextSetBit(0)
        while (c >= 0) {
            if (expiryDay[c] <= day) bits.clear(c)
            c = bits.nextSetBit(c + 1)
        }
        if (futureIndexes.isNotEmpty()) {
            val context by lazy { criteria.contextFor(deliveryDate) }
            futureIndexes.forEach { f ->
                val cls = classes[f]
                if (cls.expiryDate > deliveryDate &&
                    cls.sourceRegistry(targetRegistryCode).let { routeOf(it, targetRegistryCode ?: it).feasible } &&
                    builder.satisfies(cls, bound, deliveryDate) { context }
                ) {
                    bits.set(f)
                }
            }
        }
        return bits
    }

    /** Held classes whose registry reaches the target (each class its own registry when there is no target). */
    private fun reachableFrom(targetRegistryCode: String?): BitSet = reachable.getOrPut(targetRegistryCode) {
        BitSet(classes.size).apply {
            byRegistry.forEach { (registry, members) -> if (routeOf(registry, targetRegistryCode ?: registry).feasible) or(members) }
        }
    }

    /**
     * A profile per class: held classes with the same matches of every bound criterion in use, the same registry and
     * the same expiry rank among the delivery dates are in exactly the same rows, so the flow may compress them as one
     * (ADR 0043). Each future class has a profile of its own; tombstones are in no row.
     */
    private fun profiles(): IntArray {
        val bounds = matches.values.toList()
        val days = rows.values.mapTo(TreeSet()) { it.deliveryDay }.toLongArray()
        val ids = HashMap<Profile, Int>()
        var next = 0
        return IntArray(classes.size) { c ->
            val cls = classes[c]
            if (cls.future != null) return@IntArray next++
            val words = LongArray((bounds.size + WORD - 1) / WORD)
            bounds.forEachIndexed { i, matching -> if (matching.get(c)) words[i / WORD] = words[i / WORD] or (1L shl (i % WORD)) }
            // A row keeps the class when it expires after the delivery date: the rank is how many dates are before it.
            val found = java.util.Arrays.binarySearch(days, expiryDay[c])
            val rank = if (found >= 0) found else -(found + 1)
            ids.getOrPut(Profile(words.asList(), cls.registryCode, rank)) { next++ }
        }
    }

    private data class Profile(val matches: List<Long>, val registryCode: String, val expiryRank: Int)

    private fun ensureCapacity(c: Int) {
        if (c >= expiryDay.size) expiryDay = expiryDay.copyOf(maxOf(c + 1, expiryDay.size * 2))
    }

    private companion object {
        const val WORD = Long.SIZE_BITS
        const val INITIAL_CAPACITY = 1_024
    }
}
