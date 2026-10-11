package staff.android.design.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

fun interface MonotonicClock { fun nowMillis(): Long }

data class TimeBudget(val startedAtMillis: Long, val durationMillis: Long) {
    init { require(startedAtMillis >= 0); require(durationMillis in 1..60_000) }
    fun remainingMillis(nowMillis: Long): Long {
        if (nowMillis < startedAtMillis) return 0
        val elapsed = nowMillis - startedAtMillis
        return if (elapsed >= durationMillis) 0 else durationMillis - elapsed
    }
}

data class DeliveryKey(val productId: String, val zone: String, val quantity: Int) {
    init {
        require(productId.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(zone in setOf("city", "north")); require(quantity in 1..3)
    }
}

data class DeliveryEstimate(val key: DeliveryKey, val arrivalDays: Int,
    val feeMinor: Long, val validForMillis: Long, val revision: Int) {
    init {
        require(arrivalDays in 1..30); require(feeMinor in 0..1_000_000)
        require(validForMillis in 1..60_000); require(revision > 0)
    }
}
sealed interface EstimateReply {
    val key: DeliveryKey
    data class Available(val estimate: DeliveryEstimate) : EstimateReply {
        override val key: DeliveryKey get() = estimate.key
    }
    data class Unserviceable(override val key: DeliveryKey) : EstimateReply
}
interface DeliveryService {
    suspend fun estimate(key: DeliveryKey, attempt: String, budget: TimeBudget): EstimateReply
    suspend fun recommendations(key: DeliveryKey, attempt: String, budget: TimeBudget): List<String>
}
enum class EstimatePhase { Idle, Loading, Ready, Unserviceable, Unavailable, Deadline, Expired }
enum class OptionalPhase { Idle, Loading, Ready, Unavailable }
data class DeliveryState(val key: DeliveryKey = DeliveryKey("camera", "city", 1),
    val phase: EstimatePhase = EstimatePhase.Idle, val estimate: DeliveryEstimate? = null,
    val optionalPhase: OptionalPhase = OptionalPhase.Idle,
    val recommendedIds: List<String> = emptyList())
enum class DesignEventKind { Started, Ready, Unserviceable, Unavailable, Deadline,
    Superseded, Expired, OptionalReady, OptionalUnavailable }
data class DesignEvent(val attempt: String, val kind: DesignEventKind, val elapsedMillis: Long) {
    init { require(attempt.matches(Regex("[a-f0-9]{32}"))); require(elapsedMillis >= 0) }
}

/** All entry points and state publication run on the supplied scope's single owner thread. */
class DeliveryController(private val service: DeliveryService, private val clock: MonotonicClock,
    private val scope: CoroutineScope, private val nextAttempt: () -> String,
    private val observe: (DesignEvent) -> Unit = {}, private val joinOptional: Boolean = false,
    private val estimateBudgetMillis: Long = 1_500, private val optionalBudgetMillis: Long = 3_000) {
    init { require(estimateBudgetMillis in 1..60_000); require(optionalBudgetMillis in 1..60_000) }
    private val mutable = MutableStateFlow(DeliveryState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var estimateJob: Job? = null
    private var optionalJob: Job? = null
    private var expiryJob: Job? = null
    private var activeAttempt: String? = null
    private var activeStart = 0L
    private var validity: TimeBudget? = null
    private fun event(attempt: String, kind: DesignEventKind, start: Long) {
        try { observe(DesignEvent(attempt, kind, (clock.nowMillis() - start).coerceAtLeast(0))) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A failed diagnostic sink does not decide the product result. */ }
    }
    private fun current(token: Long, key: DeliveryKey) = token == generation && key == mutable.value.key
    private fun cancelWork() {
        if (mutable.value.phase == EstimatePhase.Loading) activeAttempt?.let { event(it, DesignEventKind.Superseded, activeStart) }
        generation++
        estimateJob?.cancel(); optionalJob?.cancel(); expiryJob?.cancel(); validity = null
    }
    fun select(key: DeliveryKey) {
        if (key == mutable.value.key) return
        cancelWork(); mutable.value = DeliveryState(key)
    }
    fun leave() { cancelWork(); mutable.value = DeliveryState(mutable.value.key) }
    fun refreshExpiry() {
        if (mutable.value.phase == EstimatePhase.Ready && validity?.remainingMillis(clock.nowMillis()) == 0L) {
            mutable.value = mutable.value.copy(phase = EstimatePhase.Expired)
            activeAttempt?.let { event(it, DesignEventKind.Expired, activeStart) }
        }
    }
    fun check() {
        cancelWork()
        val token = generation; val key = mutable.value.key; val start = clock.nowMillis()
        val budget = TimeBudget(start, estimateBudgetMillis)
        val extraBudget = TimeBudget(start, optionalBudgetMillis)
        val attempt = nextAttempt(); require(attempt.matches(Regex("[a-f0-9]{32}")))
        activeAttempt = attempt; activeStart = start
        mutable.value = DeliveryState(key, EstimatePhase.Loading, optionalPhase = OptionalPhase.Loading)
        event(attempt, DesignEventKind.Started, start)
        if (!joinOptional) optionalJob = scope.launch { loadOptional(token, key, attempt, extraBudget) }
        estimateJob = scope.launch {
            try {
                val remaining = budget.remainingMillis(clock.nowMillis())
                if (remaining == 0L) { terminal(token, key, attempt, start, EstimatePhase.Deadline); return@launch }
                val reply = withTimeout(remaining) {
                    if (joinOptional) coroutineScope {
                        val essential = async { service.estimate(key, attempt, budget) }
                        val extra = async { loadOptional(token, key, attempt, extraBudget) }
                        val answer = essential.await()
                        extra.await() // Deliberate design defect: optional work delays the essential answer.
                        answer
                    } else service.estimate(key, attempt, budget)
                }
                if (!current(token, key)) return@launch
                if (budget.remainingMillis(clock.nowMillis()) == 0L) {
                    terminal(token, key, attempt, start, EstimatePhase.Deadline); return@launch
                }
                if (reply.key != key) throw IOException("Response scope mismatch")
                when (reply) {
                    is EstimateReply.Unserviceable -> terminal(token, key, attempt, start, EstimatePhase.Unserviceable)
                    is EstimateReply.Available -> {
                        val lease = TimeBudget(start, reply.estimate.validForMillis)
                        if (lease.remainingMillis(clock.nowMillis()) == 0L) {
                            terminal(token, key, attempt, start, EstimatePhase.Expired); return@launch
                        }
                        validity = lease
                        mutable.value = mutable.value.copy(phase = EstimatePhase.Ready, estimate = reply.estimate)
                        event(attempt, DesignEventKind.Ready, start)
                        expiryJob = scope.launch { delay(lease.remainingMillis(clock.nowMillis())); refreshExpiry() }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                terminal(token, key, attempt, start, EstimatePhase.Deadline)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { terminal(token, key, attempt, start, EstimatePhase.Unavailable) }
        }
    }
    private fun terminal(token: Long, key: DeliveryKey, attempt: String, start: Long, phase: EstimatePhase) {
        if (!current(token, key)) return
        val finalPhase = if (phase == EstimatePhase.Unavailable && TimeBudget(start, estimateBudgetMillis).remainingMillis(clock.nowMillis()) == 0L) EstimatePhase.Deadline else phase
        mutable.value = mutable.value.copy(phase = finalPhase, estimate = null,
            optionalPhase = if (joinOptional && mutable.value.optionalPhase == OptionalPhase.Loading) OptionalPhase.Unavailable else mutable.value.optionalPhase)
        val kind = when (finalPhase) {
            EstimatePhase.Unserviceable -> DesignEventKind.Unserviceable
            EstimatePhase.Deadline -> DesignEventKind.Deadline
            EstimatePhase.Expired -> DesignEventKind.Expired
            else -> DesignEventKind.Unavailable
        }
        event(attempt, kind, start)
    }
    private suspend fun loadOptional(token: Long, key: DeliveryKey, attempt: String, budget: TimeBudget) {
        try {
            val remaining = budget.remainingMillis(clock.nowMillis())
            if (remaining == 0L) throw IOException("No optional budget")
            val ids = withTimeout(remaining) { service.recommendations(key, attempt, budget) }
            if (!current(token, key)) return
            if (budget.remainingMillis(clock.nowMillis()) == 0L) throw IOException("Optional result late")
            mutable.value = mutable.value.copy(optionalPhase = OptionalPhase.Ready, recommendedIds = ids.toList())
            event(attempt, DesignEventKind.OptionalReady, budget.startedAtMillis)
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            optionalFailure(token, key, attempt, budget)
        } catch (_: IOException) { optionalFailure(token, key, attempt, budget) }
    }
    private fun optionalFailure(token: Long, key: DeliveryKey, attempt: String, budget: TimeBudget) {
        if (!current(token, key)) return
        mutable.value = mutable.value.copy(optionalPhase = OptionalPhase.Unavailable, recommendedIds = emptyList())
        event(attempt, DesignEventKind.OptionalUnavailable, budget.startedAtMillis)
    }
}
