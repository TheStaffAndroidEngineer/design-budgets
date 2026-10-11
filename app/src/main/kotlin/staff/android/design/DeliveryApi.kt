package staff.android.design

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import staff.android.design.core.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TransportRecord(val attempt: String, val route: String, val kind: String,
    val elapsedMillis: Long, val bodyBytes: Int = 0, val status: Int = 0)

class DeliveryApi(private val clock: MonotonicClock,
    private val record: (TransportRecord) -> Unit = {},
    private val origin: String = "http://127.0.0.1:8877") : DeliveryService {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        // Recover a stale pooled connection for these safe reads within the same call and original budget.
        .retryOnConnectionFailure(true).cache(null).build()
    private val json = Json { ignoreUnknownKeys = true }
    private fun observe(row: TransportRecord) { try { record(row) } catch (_: Exception) { } }
    override suspend fun estimate(key: DeliveryKey, attempt: String, budget: TimeBudget): EstimateReply {
        val payload = get("estimate", "/v1/delivery-estimate?product=${key.productId}&zone=${key.zone}&quantity=${key.quantity}", attempt, budget)
        try {
            require(payload.int("schema") == 1)
            val echoed = DeliveryKey(payload.text("productId"), payload.text("zone"), payload.int("quantity"))
            require(echoed == key)
            return when (payload.text("status")) {
                "available" -> EstimateReply.Available(DeliveryEstimate(echoed, payload.int("arrivalDays"),
                    payload.long("feeMinor"), payload.long("validForMillis"), payload.int("revision")))
                "unserviceable" -> EstimateReply.Unserviceable(echoed)
                else -> throw IOException("Unsupported estimate status")
            }
        } catch (invalid: IllegalArgumentException) { throw IOException("Invalid estimate", invalid) }
    }
    override suspend fun recommendations(key: DeliveryKey, attempt: String, budget: TimeBudget): List<String> {
        val payload = get("related", "/v1/related?product=${key.productId}", attempt, budget)
        try {
            require(payload.int("schema") == 1)
            val values = payload["productIds"] as? JsonArray ?: throw IOException("Invalid product list")
            require(values.size <= 4)
            val ids = values.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IOException("Invalid product id") }
            require(ids.distinct().size == ids.size && ids.all { it.matches(Regex("[a-z][a-z0-9_-]{0,31}")) })
            return ids.filter { id -> Catalog.featured.any { it.id == id } }
        } catch (invalid: IllegalArgumentException) { throw IOException("Invalid related products", invalid) }
    }
    private suspend fun get(route: String, path: String, attempt: String, budget: TimeBudget): JsonObject {
        val remaining = budget.remainingMillis(clock.nowMillis())
        if (remaining == 0L) throw IOException("No request budget")
        val call = client.newCall(Request.Builder().url(origin + path).header("X-Journey-ID", attempt)
            .header("Cache-Control", "no-store").build())
        // Relative call timeout supplements the outer operation timeout; it does not cover dispatcher queueing by itself.
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        observe(TransportRecord(attempt, route, "enqueued", clock.nowMillis() - budget.startedAtMillis))
        val raw: String = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
                observe(TransportRecord(attempt, route, "cancelled", (clock.nowMillis() - budget.startedAtMillis).coerceAtLeast(0)))
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    observe(TransportRecord(attempt, route, "failed", (clock.nowMillis() - budget.startedAtMillis).coerceAtLeast(0)))
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val text = try {
                        response.use {
                            observe(TransportRecord(attempt, route, "headers", (clock.nowMillis() - budget.startedAtMillis).coerceAtLeast(0), status = response.code))
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body
                            if (body.contentLength() > 16_384) throw IOException("Body exceeds limit")
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4_096)
                            val stream = body.byteStream()
                            while (true) {
                                val count = stream.read(buffer)
                                if (count == -1) break
                                if (count > 16_384 - output.size()) throw IOException("Body exceeds limit")
                                output.write(buffer, 0, count)
                            }
                            val bytes = output.toByteArray()
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                            val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (bad: java.nio.charset.CharacterCodingException) { throw IOException("Invalid UTF-8", bad) }
                            observe(TransportRecord(attempt, route, "received", (clock.nowMillis() - budget.startedAtMillis).coerceAtLeast(0), bytes.size, response.code))
                            text
                        }
                    } catch (failure: IOException) { continuation.resumeWithException(failure); return }
                    continuation.resume(text)
                }
            })
        }
        if (budget.remainingMillis(clock.nowMillis()) == 0L) throw IOException("Response arrived after budget")
        return try { json.parseToJsonElement(raw) as? JsonObject ?: throw IOException("Expected object") }
        catch (invalid: IllegalArgumentException) { throw IOException("Invalid JSON", invalid) }
    }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IOException("Invalid $name")
    private fun JsonObject.long(name: String) = (get(name) as? JsonPrimitive)?.takeIf { !it.isString && it.content.matches(Regex("0|[1-9][0-9]*")) }?.longOrNull ?: throw IOException("Invalid $name")
    private fun JsonObject.int(name: String): Int { val n = long(name); if (n > Int.MAX_VALUE) throw IOException("Invalid $name"); return n.toInt() }
}
