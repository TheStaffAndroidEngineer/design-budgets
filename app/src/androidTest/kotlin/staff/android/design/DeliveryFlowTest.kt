package staff.android.design

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import staff.android.design.core.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Fixture control is an instrumentation seam, never part of the shopper interface. */
internal object DesignFixture {
    fun request(path: String, body: JSONObject? = null): JSONObject {
        val c = URL("http://127.0.0.1:8877$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000; c.readTimeout = 5_000; c.instanceFollowRedirects = false
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            assertEquals("Fixture administration", 200, c.responseCode)
            return JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }
    fun reset() { request("/admin/reset", JSONObject()); AppEvidence.reset() }
    fun configure(vararg fields: Pair<String, Any>) {
        request("/admin/config", JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } })
    }
    fun events(): JSONArray = request("/admin/events").getJSONArray("events")
    fun retain(name: String, observations: JSONObject) {
        val design = AppEvidence.designs().map { JSONObject().put("attempt", it.attempt).put("kind", it.kind.name).put("elapsedMillis", it.elapsedMillis) }
        val network = AppEvidence.transports().map { JSONObject().put("attempt", it.attempt).put("route", it.route).put("kind", it.kind).put("elapsedMillis", it.elapsedMillis).put("bodyBytes", it.bodyBytes).put("status", it.status) }
        val record = JSONObject().put("case", name).put("version", BuildConfig.VERSION_CODE)
            .put("joined", BuildConfig.JOIN_OPTIONAL).put("observations", observations)
            .put("design", JSONArray(design)).put("transport", JSONArray(network)).put("server", events())
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "design-$name.json").writeText(record.toString(2))
    }
    fun settle() { Thread.sleep(2_700) } // Fixture requests may outlive client cancellation; isolate later resets.
}

@RunWith(AndroidJUnit4::class)
class DeliveryFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun reset() {
        assertFalse("Run this repaired suite against resilientDebug", BuildConfig.JOIN_OPTIONAL)
        DesignFixture.reset()
        DesignFixture.configure("cityDelayMs" to 100, "northDelayMs" to 100, "relatedDelayMs" to 2_500)
    }
    @After fun leave() { ui.runOnIdle { ui.activity.model.controller.leave() }; DesignFixture.settle() }
    private fun openDelivery() {
        ui.onNodeWithTag("explore_camera").performScrollTo().performClick()
        ui.onNodeWithTag("open_delivery").performScrollTo().performClick()
        ui.onNodeWithTag("check_delivery").performScrollTo().assertIsDisplayed()
    }
    private fun check() { ui.onNodeWithTag("check_delivery").performScrollTo().performClick() }
    private fun phase(expected: EstimatePhase) {
        ui.waitUntil(15_000) { ui.activity.model.state.value.phase == expected }
        ui.onNodeWithTag("delivery_screen").performScrollToNode(hasTestTag("estimate_${expected.name.lowercase()}"))
        ui.onNodeWithTag("estimate_${expected.name.lowercase()}").assertIsDisplayed()
    }
    @Test fun bundledProductAndInputsNeedNoEstimateRequest() {
        openDelivery()
        ui.onNodeWithTag("selected_scope").assertTextEquals("1 Camera · City centre")
        ui.onNodeWithTag("delivery_product_price").performScrollTo().assertTextEquals("₹12,999")
        assertEquals(0, DesignFixture.events().length())
        DesignFixture.retain("local-content", JSONObject().put("requests", 0))
    }
    @Test fun essentialEstimateAppearsWhileOptionalRequestIsStillPending() {
        openDelivery(); check(); phase(EstimatePhase.Ready)
        assertEquals(OptionalPhase.Loading, ui.activity.model.state.value.optionalPhase)
        ui.onNodeWithTag("estimate_scope").assertTextEquals("1 Camera · City centre")
        ui.onNodeWithTag("delivery_fee").assertTextEquals("Delivery: Free")
        assertTrue(AppEvidence.transports().any { it.route == "estimate" && it.kind == "received" })
        assertFalse(AppEvidence.designs().any { it.kind == DesignEventKind.OptionalReady })
        DesignFixture.retain("independent-ready", JSONObject().put("readyWhileOptionalLoading", true))
    }
    @Test fun optionalHttpFailurePreservesSuccessfulEstimate() {
        DesignFixture.configure("relatedDelayMs" to 200, "relatedHttp" to 503)
        openDelivery(); check(); phase(EstimatePhase.Ready)
        ui.waitUntil(10_000) { ui.activity.model.state.value.optionalPhase == OptionalPhase.Unavailable }
        assertEquals(EstimatePhase.Ready, ui.activity.model.state.value.phase)
        DesignFixture.retain("optional-failure", JSONObject().put("estimatePreserved", true))
    }
    @Test fun changedInputsSupersedeAnInflightCityEstimate() {
        DesignFixture.configure("cityDelayMs" to 2_000, "relatedDelayMs" to 100)
        openDelivery(); check()
        ui.waitUntil(10_000) { AppEvidence.transports().any { it.route == "estimate" && it.kind == "enqueued" } }
        ui.onNodeWithTag("zone_north").performScrollTo().performClick()
        ui.onNodeWithTag("quantity_2").performClick(); check(); phase(EstimatePhase.Ready)
        ui.onNodeWithTag("estimate_scope").assertTextEquals("2 Cameras · North district")
        ui.onNodeWithTag("delivery_fee").assertTextEquals("Delivery: ₹99")
        Thread.sleep(2_100)
        assertEquals(DeliveryKey("camera", "north", 2), ui.activity.model.state.value.estimate?.key)
        assertEquals(1, AppEvidence.designs().count { it.kind == DesignEventKind.Ready })
        assertEquals(1, AppEvidence.designs().count { it.kind == DesignEventKind.Superseded })
        DesignFixture.retain("changed-inputs", JSONObject().put("finalZone", "north").put("finalQuantity", 2))
    }
    @Test fun everyAcceptedMinorUnitIsPreservedInTheDisplayedFee() {
        DesignFixture.configure("feeMinorOverride" to 9_901, "relatedDelayMs" to 100)
        openDelivery(); check(); phase(EstimatePhase.Ready)
        ui.onNodeWithTag("delivery_fee").assertTextEquals("Delivery: ₹99.01")
        assertEquals(9_901L, ui.activity.model.state.value.estimate?.feeMinor)
        DesignFixture.retain("fractional-fee", JSONObject().put("feeMinor", 9_901).put("displayedFee", "Delivery: ₹99.01"))
    }
    @Test fun unserviceableIsShownAsADomainOutcome() {
        DesignFixture.configure("estimateMode" to "unserviceable", "relatedDelayMs" to 100)
        openDelivery(); check(); phase(EstimatePhase.Unserviceable)
        assertNull(ui.activity.model.state.value.estimate)
        DesignFixture.retain("unserviceable", JSONObject().put("phase", "Unserviceable"))
    }
    @Test fun slowEssentialDeadlineCanRecoverThroughAnExplicitNewAttempt() {
        DesignFixture.configure("cityDelayMs" to 2_500, "relatedDelayMs" to 100)
        openDelivery(); check(); phase(EstimatePhase.Deadline)
        assertNull(ui.activity.model.state.value.estimate)
        DesignFixture.configure("cityDelayMs" to 100)
        check(); phase(EstimatePhase.Ready)
        assertEquals(2, AppEvidence.designs().filter { it.kind == DesignEventKind.Started }.map { it.attempt }.distinct().size)
        DesignFixture.retain("deadline-retry", JSONObject().put("recovered", true))
    }
    @Test fun acceptedEstimateExpiresWithoutAnotherUserAction() {
        DesignFixture.configure("validForMillis" to 2_000, "relatedDelayMs" to 100)
        openDelivery(); check(); phase(EstimatePhase.Ready); phase(EstimatePhase.Expired)
        assertEquals(1, AppEvidence.designs().count { it.kind == DesignEventKind.Expired })
        DesignFixture.retain("active-expiry", JSONObject().put("phase", "Expired"))
    }
}

/** Run only this class against joinedDebug, preserving the defect as a separate observation. */
@RunWith(AndroidJUnit4::class)
class JoinedWaitTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Before fun reset() {
        assertTrue("Run this class against joinedDebug", BuildConfig.JOIN_OPTIONAL)
        DesignFixture.reset(); DesignFixture.configure("cityDelayMs" to 100, "relatedDelayMs" to 2_500)
    }
    @After fun leave() { ui.runOnIdle { ui.activity.model.controller.leave() }; DesignFixture.settle() }
    @Test fun receivedEstimateIsWithheldUntilTheOptionalJoinMissesTheDeadline() {
        ui.onNodeWithTag("explore_camera").performScrollTo().performClick()
        ui.onNodeWithTag("open_delivery").performScrollTo().performClick()
        ui.onNodeWithTag("check_delivery").performScrollTo().performClick()
        ui.waitUntil(15_000) { ui.activity.model.state.value.phase == EstimatePhase.Deadline }
        assertTrue("The early estimate must actually arrive for this causal demonstration", AppEvidence.transports().any { it.route == "estimate" && it.kind == "received" })
        assertFalse(AppEvidence.designs().any { it.kind == DesignEventKind.Ready })
        assertNull(ui.activity.model.state.value.estimate)
        ui.onNodeWithTag("delivery_screen").performScrollToNode(hasTestTag("estimate_deadline"))
        ui.onNodeWithTag("estimate_deadline").assertIsDisplayed()
        DesignFixture.retain("joined-deadline", JSONObject().put("estimateReceivedButNotPublished", true))
    }
}

@RunWith(AndroidJUnit4::class)
class DeliveryTransportTest {
    private val clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
    private val key = DeliveryKey("camera", "city", 1)
    @Before fun reset() { DesignFixture.reset(); DesignFixture.configure("cityDelayMs" to 0, "relatedDelayMs" to 0) }
    @After fun settle() { DesignFixture.settle() }
    @Test fun schemaScopeStatusSyntaxAndBodyLimitsRejectInvalidReplies() = runBlocking<Unit> {
        val api = DeliveryApi(clock, AppEvidence::transport)
        val rejected = mutableListOf<String>()
        for (mode in listOf("unknown", "mismatch", "malformed", "oversized")) {
            DesignFixture.configure("estimateMode" to mode)
            try { api.estimate(key, "a".repeat(32), TimeBudget(clock.nowMillis(), 5_000)); fail("Accepted $mode") }
            catch (_: IOException) { rejected.add(mode) }
        }
        DesignFixture.configure("estimateMode" to "available", "schema" to 2)
        try { api.estimate(key, "b".repeat(32), TimeBudget(clock.nowMillis(), 5_000)); fail("Accepted schema2") }
        catch (_: IOException) { rejected.add("schema2") }
        DesignFixture.retain("transport-rejections", JSONObject().put("rejected", JSONArray(rejected)))
    }
    @Test fun cancellationReachesTheActualHttpCallAndIsNotReturnedAsData() = runBlocking<Unit> {
        DesignFixture.configure("cityDelayMs" to 2_500)
        val api = DeliveryApi(clock, AppEvidence::transport)
        val job = launch { api.estimate(key, "c".repeat(32), TimeBudget(clock.nowMillis(), 5_000)) }
        withTimeout(5_000) { while (AppEvidence.transports().none { it.kind == "enqueued" }) delay(10) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, AppEvidence.transports().count { it.kind == "cancelled" })
        DesignFixture.retain("transport-cancel", JSONObject().put("cancelled", true))
    }
    @Test fun cancellationAfterHeadersInterruptsTheStillActiveBodyRead() = runBlocking<Unit> {
        DesignFixture.configure("bodyDelayMs" to 2_500)
        val api = DeliveryApi(clock, AppEvidence::transport)
        val job = launch { api.estimate(key, "e".repeat(32), TimeBudget(clock.nowMillis(), 5_000)) }
        withTimeout(5_000) { while (AppEvidence.transports().none { it.kind == "headers" }) delay(10) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, AppEvidence.transports().count { it.kind == "headers" })
        assertEquals(1, AppEvidence.transports().count { it.kind == "cancelled" })
        assertFalse(AppEvidence.transports().any { it.kind == "received" })
        DesignFixture.retain("transport-body-cancel", JSONObject().put("headersObservedBeforeCancel", true).put("bodyNotPublished", true))
    }
    @Test fun safeReadRecoversAfterTheFixturesIdleConnectionTimeout() = runBlocking<Unit> {
        val api = DeliveryApi(clock, AppEvidence::transport)
        val first = api.estimate(key, "f".repeat(32), TimeBudget(clock.nowMillis(), 5_000))
        assertTrue(first is EstimateReply.Available)
        val pauseStart = clock.nowMillis()
        delay(9_000) // The fixture closes an otherwise idle HTTP/1.1 socket after8seconds.
        var secondAccepted = false
        try {
            val second = api.estimate(key, "1".repeat(32), TimeBudget(clock.nowMillis(), 5_000))
            assertEquals(key, second.key)
            assertTrue(second is EstimateReply.Available)
            secondAccepted = true
        } finally {
            DesignFixture.retain("transport-idle-recovery", JSONObject()
                .put("firstAccepted", true).put("secondAccepted", secondAccepted)
                .put("pauseAndSecondCallMillis", clock.nowMillis() - pauseStart)
                .put("operations", 2))
        }
    }
    @Test fun extraSchemaOneFieldsAreToleratedAndDiagnosticFailureCannotStrandReply() = runBlocking<Unit> {
        val api = DeliveryApi(clock, record = { throw IOException("diagnostic recorder unavailable") })
        val result = withTimeout(7_000) { api.estimate(key, "d".repeat(32), TimeBudget(clock.nowMillis(), 5_000)) }
        assertEquals(key, result.key)
        assertTrue(result is EstimateReply.Available)
        DesignFixture.retain("transport-observer", JSONObject().put("acceptedWithUnknownOptionalField", true).put("observerFailureContained", true))
    }
}
