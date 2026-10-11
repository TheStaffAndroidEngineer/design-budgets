package staff.android.design.planning

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MediaPlannerTest {
    private val planner: MediaPlanner = when (System.getProperty("plan.solution", "reference")) {
        "reference" -> ReferencePlanner
        "learner" -> LearnerPlanner
        else -> error("Use -PplanSolution=learner or reference")
    }
    private fun variant(id: String = "v", transfer: Long = 1, resident: Long = 1, utility: Int = 1) =
        MediaVariant(id, transfer, resident, utility)
    private fun item(id: String = "cover", required: Boolean = true, vararg variants: MediaVariant) =
        MediaItem(id, required, variants.toList().ifEmpty { listOf(variant()) })
    private fun plan(budget: ResourceBudget, vararg items: MediaItem): PlanningResult.Plan =
        assertInstanceOf(PlanningResult.Plan::class.java, planner.plan(budget, items.toList()))
    private fun chosen(result: PlanningResult.Plan) = result.selections.associate { it.itemId to it.variantId }
    private val roomy = ResourceBudget(100, 100, 0)
    private val example = listOf(
        MediaItem("cover", true, listOf(variant("compact", 2_000, 4_000, 4), variant("detailed", 5_000, 7_000, 7))),
        item("preview", false, variant("compact", 4_000, 4_000, 6)),
    )

    @Test fun lowerCoverQualityCanEnableTheBetterWholePlan() {
        assertEquals(PlanningResult.Plan(listOf(Selection("cover", "compact"), Selection("preview", "compact")),
            10, 6_000, 10_000), planner.plan(ResourceBudget(6_000, 12_000, 2_000), example))
    }
    @Test fun changedMemoryBudgetChangesTheDefensibleChoice() {
        assertEquals(PlanningResult.Plan(listOf(Selection("cover", "detailed"), Selection("preview", null)),
            7, 5_000, 9_000), planner.plan(ResourceBudget(6_000, 9_000, 2_000), example))
    }
    @Test fun transferBudgetIsNotInterchangeableWithResidentMemory() {
        assertEquals(7, (planner.plan(ResourceBudget(5_000, 12_000, 2_000), example) as PlanningResult.Plan).totalUtility)
    }
    @Test fun individuallyFeasibleRequiredItemsCanBeJointlyInfeasible() {
        assertEquals(PlanningResult.Infeasible(NoPlanReason.NoFeasibleSelection),
            planner.plan(ResourceBudget(10, 10, 0), listOf(item("a", true, variant(transfer = 6)), item("b", true, variant(transfer = 6)))))
    }
    @Test fun existingResidentBytesCanAlreadyExceedTheLimit() {
        assertEquals(PlanningResult.Infeasible(NoPlanReason.BaselineOverBudget), planner.plan(ResourceBudget(0, 4, 5), emptyList()))
    }
    @Test fun emptyRequestStillAccountsForExistingResidentBytes() {
        assertEquals(PlanningResult.Plan(emptyList(), 0, 0, 5), planner.plan(ResourceBudget(10, 10, 5), emptyList()))
    }
    @Test fun emptyZeroBudgetHasAnEmptyFeasiblePlan() {
        assertEquals(PlanningResult.Plan(emptyList(), 0, 0, 0), planner.plan(ResourceBudget(0, 0, 0), emptyList()))
    }
    @Test fun optionalItemMayBeSkippedWhenNothingFits() {
        val result = plan(ResourceBudget(0, 0, 0), item("preview", false))
        assertEquals(listOf(Selection("preview", null)), result.selections)
        assertEquals(0, result.totalUtility)
    }
    @Test fun requiredZeroCostZeroUtilityItemStillMustBeSelected() {
        assertEquals(mapOf("cover" to "v"), chosen(plan(ResourceBudget(0, 0, 0), item("cover", true, variant(transfer = 0, resident = 0, utility = 0)))))
    }
    @Test fun equalityAtBothBudgetsIsAllowed() {
        val result = plan(ResourceBudget(6, 10, 3), item("cover", true, variant(transfer = 6, resident = 7)))
        assertEquals(6L, result.transferBytes); assertEquals(10L, result.peakResidentBytes)
    }
    @Test fun positiveUtilityZeroCostOptionalItemIsUsefulInThisModel() {
        assertEquals(mapOf("cover" to "v"), chosen(plan(ResourceBudget(0, 0, 0), item("cover", false, variant(transfer = 0, resident = 0, utility = 8)))))
    }
    @Test fun equalUtilityPrefersFewerTransferBytes() {
        assertEquals("small", chosen(plan(roomy, item("cover", true, variant("large", 8, 1, 4), variant("small", 2, 9, 4))))["cover"])
    }
    @Test fun equalUtilityAndTransferPreferLessResidentMemory() {
        assertEquals("small", chosen(plan(roomy, item("cover", true, variant("large", 2, 9, 4), variant("small", 2, 1, 4))))["cover"])
    }
    @Test fun remainingTieUsesVariantIdsRatherThanInputOrder() {
        assertEquals("a", chosen(plan(roomy, item("cover", true, variant("z"), variant("a"))))["cover"])
    }
    @Test fun skipSortsBeforeAnIdInTheCanonicalSelectionVector() {
        val result = plan(ResourceBudget(1, 1, 0), item("b", false), item("a", false))
        assertEquals(listOf(Selection("a", null), Selection("b", "v")), result.selections)
    }
    @Test fun outputItemsAreSortedIndependentlyOfCallerOrder() {
        assertEquals(listOf("a", "z"), plan(roomy, item("z"), item("a")).selections.map { it.itemId })
    }
    @Test fun transferSumAboveLongMaxIsInfeasibleNotWrapped() {
        assertEquals(PlanningResult.Infeasible(NoPlanReason.NoFeasibleSelection), planner.plan(
            ResourceBudget(Long.MAX_VALUE, 100, 0), listOf(item("a", true, variant(transfer = Long.MAX_VALUE)), item("b"))))
    }
    @Test fun residentSumAboveLongMaxIsInfeasibleNotWrapped() {
        assertEquals(PlanningResult.Infeasible(NoPlanReason.NoFeasibleSelection), planner.plan(
            ResourceBudget(100, Long.MAX_VALUE, 1), listOf(item("a", true, variant(resident = Long.MAX_VALUE)))))
    }
    @Test fun largestResidentBudgetCanBeUsedExactly() {
        assertEquals(Long.MAX_VALUE, plan(ResourceBudget(1, Long.MAX_VALUE, Long.MAX_VALUE - 1), item()).peakResidentBytes)
    }
    @Test fun largestTransferCostCanBeUsedExactly() {
        assertEquals(Long.MAX_VALUE, plan(ResourceBudget(Long.MAX_VALUE, 1, 0), item("cover", true, variant(transfer = Long.MAX_VALUE))).transferBytes)
    }
    @Test fun zeroUtilityZeroCostOptionalVariantLosesTieToSkip() {
        assertEquals(listOf(Selection("cover", null)), plan(roomy, item("cover", false, variant(transfer = 0, resident = 0, utility = 0))).selections)
    }
    @Test fun resultListRejectsMutation() {
        val result = plan(roomy, item())
        assertThrows(UnsupportedOperationException::class.java) {
            (result.selections as MutableList<Selection>).add(Selection("other", null))
        }
    }
    @Test fun laterInputListChangesDoNotRewriteThePlan() {
        val variants = mutableListOf(variant())
        val items = mutableListOf(MediaItem("cover", true, variants))
        val result = planner.plan(roomy, items) as PlanningResult.Plan
        variants.clear(); items.clear()
        assertEquals(listOf(Selection("cover", "v")), result.selections)
        assertEquals(1, result.totalUtility)
    }
    @Test fun negativeBudgetsAreInvalidNotInfeasible() {
        for (budget in listOf(ResourceBudget(-1, 1, 0), ResourceBudget(1, -1, 0), ResourceBudget(1, 1, -1)))
            assertThrows(IllegalArgumentException::class.java) { planner.plan(budget, emptyList()) }
    }
    @Test fun ninthItemExceedsTheDeclaredSearchBound() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, (0..8).map { item("item$it") }) }
    }
    @Test fun everyItemNeedsAtLeastOneVariantEvenIfOptional() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(MediaItem("a", false, emptyList()))) }
    }
    @Test fun fourthVariantExceedsTheDeclaredSearchBound() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(MediaItem("a", false, (0..3).map { variant("v$it") }))) }
    }
    @Test fun duplicateItemIdsAreInvalid() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(item(), item())) }
    }
    @Test fun duplicateVariantIdsWithinAnItemAreInvalid() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(item("a", false, variant(), variant()))) }
    }
    @Test fun variantIdsMayRepeatAcrossDifferentItems() {
        assertEquals(listOf(Selection("a", "v"), Selection("b", "v")), plan(roomy, item("a"), item("b")).selections)
    }
    @Test fun identifiersHaveAnExactCaseSensitiveGrammar() {
        for (id in listOf("", "A", "a b", "a".repeat(33))) {
            assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(item(id))) }
            assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(item("a", false, variant(id)))) }
        }
    }
    @Test fun negativeVariantCostsAreInvalid() {
        for (v in listOf(variant(transfer = -1), variant(resident = -1)))
            assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(item("a", false, v))) }
    }
    @Test fun utilityIsBoundedPolicyInput() {
        for (u in listOf(-1, 1_001))
            assertThrows(IllegalArgumentException::class.java) { planner.plan(roomy, listOf(item("a", false, variant(utility = u)))) }
    }
    @Test fun invalidUnaffordableOptionalVariantIsStillRejected() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(ResourceBudget(0, 0, 0), listOf(item("a", false, variant(utility = -1)))) }
    }
    @Test fun validationPrecedesTheBaselineOverBudgetDecision() {
        assertThrows(IllegalArgumentException::class.java) { planner.plan(ResourceBudget(0, 0, 1), listOf(item("a", false, variant(utility = -1)))) }
    }
    @Test fun maximumSearchShapeStillFindsAllBestVariants() {
        val items = (0..7).map { item("item$it", false, variant("a", 0, 0, 1), variant("b", 0, 0, 2), variant("c", 0, 0, 1_000)) }
        val result = planner.plan(ResourceBudget(0, 0, 0), items) as PlanningResult.Plan
        assertEquals(8_000, result.totalUtility)
        assertTrue(result.selections.all { it.variantId == "c" })
        assertEquals(8, result.selections.size)
    }
}
