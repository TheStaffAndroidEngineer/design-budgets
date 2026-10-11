package staff.android.design.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

/** Independent contracts: virtual scheduling establishes ordering, not device latency. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryDesignTest {
    private val city = DeliveryKey("camera", "city", 1)
    private val north = DeliveryKey("camera", "north", 2)
    private fun answer(key: DeliveryKey = city, validity: Long = 5_000) =
        EstimateReply.Available(DeliveryEstimate(key, 3, 9_900, validity, 1))
    private class Service : DeliveryService {
        var estimateCalls = 0
        var optionalCalls = 0
        var load: suspend (DeliveryKey, String, TimeBudget) -> EstimateReply = { key, _, _ ->
            EstimateReply.Available(DeliveryEstimate(key, 3, 9_900, 5_000, 1))
        }
        var extra: suspend (DeliveryKey, String, TimeBudget) -> List<String> = { _, _, _ -> listOf("tea") }
        override suspend fun estimate(key: DeliveryKey, attempt: String, budget: TimeBudget): EstimateReply {
            estimateCalls++; return load(key, attempt, budget)
        }
        override suspend fun recommendations(key: DeliveryKey, attempt: String, budget: TimeBudget): List<String> {
            optionalCalls++; return extra(key, attempt, budget)
        }
    }
    private class Rig(val test: TestScope, joined: Boolean = false) {
        val service = Service()
        var offset = 0L
        var sequence = 0L
        val events = mutableListOf<DesignEvent>()
        val clock = MonotonicClock { test.testScheduler.currentTime + offset }
        val controller = DeliveryController(service, clock, test.backgroundScope,
            { (++sequence).toString(16).padStart(32, '0') }, events::add, joined)
        val state get() = controller.state.value
    }

    @Test fun budgetUsesExclusiveBoundaryAndFailsClosedOnReversedTime() {
        val budget = TimeBudget(1_000, 1_500)
        assertEquals(1_500L, budget.remainingMillis(1_000))
        assertEquals(1L, budget.remainingMillis(2_499))
        assertEquals(0L, budget.remainingMillis(2_500))
        assertEquals(0L, budget.remainingMillis(999))
        assertEquals(0L, budget.remainingMillis(Long.MIN_VALUE))
    }
    @Test fun elapsedSubtractionDoesNotOverflowAtTheLargestValidClock() {
        val budget = TimeBudget(Long.MAX_VALUE - 10, 60_000)
        assertEquals(59_990L, budget.remainingMillis(Long.MAX_VALUE))
        assertEquals(0L, TimeBudget(0, 1).remainingMillis(Long.MAX_VALUE))
    }
    @Test fun invalidBudgetInputsAreRejectedInsteadOfBecomingUnlimited() {
        for (duration in listOf(-1L, 0L, 60_001L, Long.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { TimeBudget(0, duration) }
        assertThrows(IllegalArgumentException::class.java) { TimeBudget(-1, 1) }
    }
    @Test fun requestKeyHasClosedZoneQuantityAndIdentifierRules() {
        for (zone in listOf("", "City", "city ", "postal-12345"))
            assertThrows(IllegalArgumentException::class.java) { DeliveryKey("camera", zone, 1) }
        for (quantity in listOf(0, 4, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { DeliveryKey("camera", "city", quantity) }
        for (id in listOf("", "Camera", "a/b", "x".repeat(33)))
            assertThrows(IllegalArgumentException::class.java) { DeliveryKey(id, "city", 1) }
        assertEquals("x".repeat(32), DeliveryKey("x".repeat(32), "north", 3).productId)
    }
    @Test fun estimateRejectsImpossiblePriceWindowLeaseOrRevision() {
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 0, 0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 31, 0, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 1, -1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 1, 1_000_001, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 1, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 1, 0, 60_001, 1) }
        assertThrows(IllegalArgumentException::class.java) { DeliveryEstimate(city, 1, 0, 1, 0) }
        assertEquals(0L, DeliveryEstimate(city, 1, 0, 1, 1).feeMinor)
    }
    @Test fun eventShapeAcceptsOnlyOpaqueBoundedCorrelationAndNonnegativeElapsed() {
        assertThrows(IllegalArgumentException::class.java) { DesignEvent("camera", DesignEventKind.Started, 0) }
        assertThrows(IllegalArgumentException::class.java) { DesignEvent("A".repeat(32), DesignEventKind.Started, 0) }
        assertThrows(IllegalArgumentException::class.java) { DesignEvent("a".repeat(32), DesignEventKind.Started, -1) }
        assertEquals(0L, DesignEvent("a".repeat(32), DesignEventKind.Started, 0).elapsedMillis)
    }
    @Test fun criticalEstimatePublishesWithoutWaitingForSlowOptionalWork() = runTest {
        val r = Rig(this)
        r.service.load = { key, _, _ -> delay(300); answer(key) }
        r.service.extra = { _, _, _ -> delay(2_500); listOf("tea") }
        r.controller.check(); runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(EstimatePhase.Ready, r.state.phase)
        assertEquals(OptionalPhase.Loading, r.state.optionalPhase)
        advanceTimeBy(1_200); runCurrent()
        assertEquals(EstimatePhase.Ready, r.state.phase)
        assertEquals(OptionalPhase.Loading, r.state.optionalPhase)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("tea"), r.state.recommendedIds)
        assertEquals(listOf(DesignEventKind.Started, DesignEventKind.Ready, DesignEventKind.OptionalReady), r.events.map { it.kind })
    }
    @Test fun joinedOptionalWorkConsumesTheCriticalDeadlineDespiteEarlyEstimate() = runTest {
        val r = Rig(this, joined = true)
        r.service.load = { key, _, _ -> delay(300); answer(key) }
        r.service.extra = { _, _, _ -> delay(2_500); listOf("tea") }
        r.controller.check(); runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(EstimatePhase.Loading, r.state.phase)
        advanceTimeBy(1_200); runCurrent()
        assertEquals(EstimatePhase.Deadline, r.state.phase)
        assertNull(r.state.estimate)
        assertEquals(0, r.events.count { it.kind == DesignEventKind.Ready })
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Deadline })
    }
    @Test fun optionalFailureNeverReplacesTheMatchingSuccessfulEstimate() = runTest {
        val r = Rig(this)
        r.service.extra = { _, _, _ -> delay(400); throw IOException("optional unavailable") }
        r.controller.check(); runCurrent()
        val accepted = r.state.estimate
        advanceTimeBy(400); runCurrent()
        assertSame(accepted, r.state.estimate)
        assertEquals(EstimatePhase.Ready, r.state.phase)
        assertEquals(OptionalPhase.Unavailable, r.state.optionalPhase)
    }
    @Test fun oneDeadlineIncludesTimeAlreadySpentBeforeDispatch() = runTest {
        val r = Rig(this)
        var seenRemaining = -1L
        r.service.load = { key, _, budget ->
            seenRemaining = budget.remainingMillis(r.clock.nowMillis())
            assertEquals(0L, budget.startedAtMillis)
            delay(1_101); answer(key)
        }
        r.controller.check(); r.offset = 400; runCurrent()
        assertEquals(1_100L, seenRemaining)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(EstimatePhase.Deadline, r.state.phase)
    }
    @Test fun exhaustedQueueBudgetDoesNotStartEitherServiceCall() = runTest {
        val r = Rig(this)
        r.controller.check(); r.offset = 3_000; runCurrent()
        assertEquals(0, r.service.estimateCalls)
        assertEquals(0, r.service.optionalCalls)
        assertEquals(EstimatePhase.Deadline, r.state.phase)
        assertEquals(OptionalPhase.Unavailable, r.state.optionalPhase)
    }
    @Test fun synchronousResultAtAdmissionBoundaryCannotBecomeReady() = runTest {
        val r = Rig(this)
        r.service.load = { key, _, _ -> r.offset = 1_500; answer(key) }
        r.controller.check(); runCurrent()
        assertEquals(EstimatePhase.Deadline, r.state.phase)
        assertNull(r.state.estimate)
        assertFalse(r.events.any { it.kind == DesignEventKind.Ready })
    }
    @Test fun transportFailureAfterBudgetIsDeadlineNotOrdinaryUnavailable() = runTest {
        val r = Rig(this)
        r.service.load = { _, _, _ -> r.offset = 1_500; throw IOException("late socket failure") }
        r.controller.check(); runCurrent()
        assertEquals(EstimatePhase.Deadline, r.state.phase)
    }
    @Test fun productZoneAndQuantityMismatchesAreAllRejected() = runTest {
        for (wrong in listOf(city.copy(productId = "tea"), city.copy(zone = "north"), city.copy(quantity = 2))) {
            val r = Rig(this); r.service.load = { _, _, _ -> answer(wrong) }
            r.controller.check(); runCurrent()
            assertEquals(EstimatePhase.Unavailable, r.state.phase)
            assertNull(r.state.estimate)
        }
    }
    @Test fun unserviceableIsAValidatedBusinessResultRatherThanNetworkFailure() = runTest {
        val r = Rig(this); r.service.load = { key, _, _ -> EstimateReply.Unserviceable(key) }
        r.controller.check(); runCurrent()
        assertEquals(EstimatePhase.Unserviceable, r.state.phase)
        assertNull(r.state.estimate)
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Unserviceable })
    }
    @Test fun replacedInputRejectsLateNoncooperativeEssentialAndOptionalResults() = runTest {
        val r = Rig(this); val held = CompletableDeferred<Unit>()
        r.service.load = { key, _, _ ->
            if (key == city) withContext(NonCancellable) { held.await() }
            answer(key)
        }
        r.service.extra = { key, _, _ ->
            if (key == city) withContext(NonCancellable) { held.await() }
            if (key == city) listOf("tea") else listOf("lamp")
        }
        r.controller.check(); runCurrent(); r.controller.select(north); r.controller.check(); runCurrent()
        val acceptedBeforeOldCompletion = r.state.estimate?.key
        held.complete(Unit); runCurrent()
        assertEquals(north, acceptedBeforeOldCompletion)
        assertEquals(north, r.state.key); assertEquals(north, r.state.estimate?.key)
        assertEquals(listOf("lamp"), r.state.recommendedIds)
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Ready })
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Superseded })
    }
    @Test fun retryOfSameKeyHasNewIdentityAndRejectsItsEarlierCompletion() = runTest {
        val r = Rig(this); val held = CompletableDeferred<Unit>()
        r.service.load = { key, attempt, _ ->
            if (attempt.endsWith("1")) withContext(NonCancellable) { held.await() }
            val revision = if (attempt.endsWith("1")) 1 else 2
            EstimateReply.Available(DeliveryEstimate(key, 3, 0, 5_000, revision))
        }
        r.controller.check(); runCurrent(); r.controller.check(); runCurrent()
        val acceptedBeforeOldCompletion = r.state.estimate?.revision
        held.complete(Unit); runCurrent()
        assertEquals(2, acceptedBeforeOldCompletion)
        assertEquals(2, r.state.estimate?.revision)
        assertEquals(2, r.events.filter { it.kind == DesignEventKind.Started }.map { it.attempt }.distinct().size)
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Ready })
    }
    @Test fun leavingResetsStateAndCannotBeUndoneByLateCompletion() = runTest {
        val r = Rig(this); val held = CompletableDeferred<Unit>()
        r.service.load = { key, _, _ -> withContext(NonCancellable) { held.await() }; answer(key) }
        r.controller.check(); runCurrent(); r.controller.leave(); held.complete(Unit); runCurrent()
        assertEquals(EstimatePhase.Idle, r.state.phase); assertNull(r.state.estimate)
        assertEquals(OptionalPhase.Idle, r.state.optionalPhase)
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Superseded })
    }
    @Test fun validityStartsAtRequestAdmissionAndIncludesResponseTransit() = runTest {
        val r = Rig(this); r.service.load = { key, _, _ -> delay(500); answer(key, 1_000) }
        r.controller.check(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(EstimatePhase.Ready, r.state.phase)
        advanceTimeBy(499); runCurrent(); assertEquals(EstimatePhase.Ready, r.state.phase)
        advanceTimeBy(1); runCurrent(); assertEquals(EstimatePhase.Expired, r.state.phase)
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Expired })
    }
    @Test fun estimateWhoseAdvisoryValidityElapsedInTransitIsNotPublishedAsReady() = runTest {
        val r = Rig(this); r.service.load = { key, _, _ -> delay(500); answer(key, 500) }
        r.controller.check(); runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(EstimatePhase.Expired, r.state.phase); assertNull(r.state.estimate)
        assertEquals(0, r.events.count { it.kind == DesignEventKind.Ready })
    }
    @Test fun resumedOwnerRechecksExpiryOnceAfterAClockAdvance() = runTest {
        val r = Rig(this); r.controller.check(); runCurrent()
        r.offset = 5_000; r.controller.refreshExpiry(); r.controller.refreshExpiry()
        assertEquals(EstimatePhase.Expired, r.state.phase)
        assertEquals(1, r.events.count { it.kind == DesignEventKind.Expired })
    }
    @Test fun backwardsClockCannotKeepAnAcceptedEstimateCurrent() = runTest {
        val r = Rig(this); r.offset = 100; r.controller.check(); runCurrent()
        r.offset = 99; r.controller.refreshExpiry()
        assertEquals(EstimatePhase.Expired, r.state.phase)
    }
    @Test fun oldExpiryTaskCannotExpireAReplacementEstimate() = runTest {
        val r = Rig(this)
        r.service.load = { key, _, _ -> answer(key, if (key == city) 1_000 else 5_000) }
        r.controller.check(); runCurrent(); advanceTimeBy(100)
        r.controller.select(north); r.controller.check(); runCurrent()
        advanceTimeBy(900); runCurrent()
        assertEquals(EstimatePhase.Ready, r.state.phase); assertEquals(north, r.state.estimate?.key)
    }
    @Test fun recommendationsDetachFromTheServicesMutableList() = runTest {
        val r = Rig(this); val ids = mutableListOf("tea", "lamp")
        r.service.extra = { _, _, _ -> ids }; r.controller.check(); runCurrent(); ids.clear()
        assertEquals(listOf("tea", "lamp"), r.state.recommendedIds)
    }
    @Test fun selectingTheSameKeyDoesNotInventASupersedingOperation() = runTest {
        val r = Rig(this); r.service.load = { key, _, _ -> delay(100); answer(key) }
        r.controller.check(); runCurrent(); r.controller.select(city)
        advanceTimeBy(100); runCurrent()
        assertEquals(EstimatePhase.Ready, r.state.phase); assertEquals(1, r.service.estimateCalls)
        assertFalse(r.events.any { it.kind == DesignEventKind.Superseded })
    }
    @Test fun diagnosticFailuresCannotPreventOrReplaceSuccessfulProductState() = runTest {
        for (failure in listOf(IOException("disk full"), IllegalStateException("observer defect"))) {
            val service = Service()
            val controller = DeliveryController(service, MonotonicClock { testScheduler.currentTime },
                backgroundScope, { "a".repeat(32) }, { throw failure })
            controller.check(); runCurrent()
            assertEquals(EstimatePhase.Ready, controller.state.value.phase)
            assertEquals(OptionalPhase.Ready, controller.state.value.optionalPhase)
            assertEquals(1, service.estimateCalls)
            controller.leave()
        }
    }

}
