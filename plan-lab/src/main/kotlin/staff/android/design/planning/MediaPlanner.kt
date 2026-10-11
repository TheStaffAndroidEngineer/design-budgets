package staff.android.design.planning

import java.util.Collections

data class ResourceBudget(
    val transferLimitBytes: Long,
    val residentLimitBytes: Long,
    val baselineResidentBytes: Long,
)

data class MediaVariant(
    val id: String,
    val transferBytes: Long,
    val residentBytes: Long,
    val utility: Int,
)

data class MediaItem(
    val id: String,
    val required: Boolean,
    val variants: List<MediaVariant>,
)

data class Selection(val itemId: String, val variantId: String?)
enum class NoPlanReason { BaselineOverBudget, NoFeasibleSelection }

sealed interface PlanningResult {
    data class Plan(
        val selections: List<Selection>,
        val totalUtility: Int,
        val transferBytes: Long,
        val peakResidentBytes: Long,
    ) : PlanningResult

    data class Infeasible(val reason: NoPlanReason) : PlanningResult
}

fun interface MediaPlanner {
    fun plan(budget: ResourceBudget, items: List<MediaItem>): PlanningResult
}

/** Deliberately unfinished. Preserve this learner entry point. */
object LearnerPlanner : MediaPlanner {
    override fun plan(budget: ResourceBudget, items: List<MediaItem>): PlanningResult =
        PlanningResult.Plan(emptyList(), 0, 0, budget.baselineResidentBytes)
}

/**
 * Exact search in the stipulated additive model, not a runtime scheduler.
 * Inputs must remain stable while their bounded list structure is copied.
 * Utility is caller policy, not measured customer value, latency or energy.
 */
object ReferencePlanner : MediaPlanner {
    override fun plan(budget: ResourceBudget, items: List<MediaItem>): PlanningResult {
        val ordered = validatedCopy(budget, items)
        if (budget.baselineResidentBytes > budget.residentLimitBytes) {
            return PlanningResult.Infeasible(NoPlanReason.BaselineOverBudget)
        }
        var best: PlanningResult.Plan? = null
        val selected = ArrayList<Selection>(ordered.size)

        fun search(index: Int, transfer: Long, resident: Long, utility: Int) {
            if (index == ordered.size) {
                if (better(utility, transfer, resident, selected, best)) {
                    best = PlanningResult.Plan(
                        Collections.unmodifiableList(ArrayList(selected)),
                        utility, transfer, resident,
                    )
                }
                return
            }
            val item = ordered[index]
            if (!item.required) {
                selected.add(Selection(item.id, null))
                search(index + 1, transfer, resident, utility)
                selected.removeAt(selected.lastIndex)
            }
            for (variant in item.variants) {
                // Subtract before adding: an oversized sum is infeasible,
                // not a wrapped negative value or an arithmetic exception.
                if (variant.transferBytes > budget.transferLimitBytes - transfer ||
                    variant.residentBytes > budget.residentLimitBytes - resident) continue
                selected.add(Selection(item.id, variant.id))
                search(
                    index + 1, transfer + variant.transferBytes,
                    resident + variant.residentBytes, utility + variant.utility,
                )
                selected.removeAt(selected.lastIndex)
            }
        }

        search(0, 0, budget.baselineResidentBytes, 0)
        return best ?: PlanningResult.Infeasible(NoPlanReason.NoFeasibleSelection)
    }

    private fun validatedCopy(
        budget: ResourceBudget,
        items: List<MediaItem>,
    ): List<MediaItem> {
        require(budget.transferLimitBytes >= 0 && budget.residentLimitBytes >= 0 &&
            budget.baselineResidentBytes >= 0) { "Negative resource budget" }
        require(items.size <= 8) { "At most eight items" }
        val copied = items.map { item ->
            require(item.variants.size in 1..3) { "One to three variants per item" }
            item.copy(variants = item.variants.toList())
        }
        val idPattern = Regex("[a-z][a-z0-9_-]{0,31}")
        require(copied.map { it.id }.distinct().size == copied.size) { "Duplicate item ID" }
        copied.forEach { item ->
            require(item.id.matches(idPattern)) { "Invalid item ID" }
            require(item.variants.map { it.id }.distinct().size == item.variants.size) {
                "Duplicate variant ID"
            }
            item.variants.forEach { variant ->
                require(variant.id.matches(idPattern)) { "Invalid variant ID" }
                require(variant.transferBytes >= 0 && variant.residentBytes >= 0) {
                    "Negative resource cost"
                }
                require(variant.utility in 0..1_000) { "Utility outside policy range" }
            }
        }
        return copied.sortedBy { it.id }.map { it.copy(variants = it.variants.sortedBy { v -> v.id }) }
    }

    private fun better(
        utility: Int,
        transfer: Long,
        resident: Long,
        selected: List<Selection>,
        best: PlanningResult.Plan?,
    ): Boolean {
        if (best == null) return true
        if (utility != best.totalUtility) return utility > best.totalUtility
        if (transfer != best.transferBytes) return transfer < best.transferBytes
        if (resident != best.peakResidentBytes) return resident < best.peakResidentBytes
        for (index in selected.indices) {
            // Null skip sorts before every permitted nonempty variant ID.
            val order = (selected[index].variantId ?: "")
                .compareTo(best.selections[index].variantId ?: "")
            if (order != 0) return order < 0
        }
        return false
    }
}
