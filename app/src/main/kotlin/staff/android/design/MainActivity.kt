package staff.android.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import staff.android.design.core.*
import java.util.UUID

/** Bounded in-process diagnostic recorder, read only by companion instrumentation. */
object AppEvidence {
    private val designRows = ArrayList<DesignEvent>()
    private val networkRows = ArrayList<TransportRecord>()
    @Synchronized fun design(row: DesignEvent) { if (designRows.size == 128) designRows.removeAt(0); designRows.add(row) }
    @Synchronized fun transport(row: TransportRecord) { if (networkRows.size == 256) networkRows.removeAt(0); networkRows.add(row) }
    @Synchronized fun designs(): List<DesignEvent> = designRows.toList()
    @Synchronized fun transports(): List<TransportRecord> = networkRows.toList()
    @Synchronized fun reset() { designRows.clear(); networkRows.clear() }
}
class DesignModel : ViewModel() {
    private val clock = MonotonicClock { android.os.SystemClock.elapsedRealtime() }
    private val service = DeliveryApi(clock, AppEvidence::transport)
    val controller = DeliveryController(service, clock, viewModelScope,
        { UUID.randomUUID().toString().replace("-", "") }, AppEvidence::design, BuildConfig.JOIN_OPTIONAL)
    val state = controller.state
}
class MainActivity : ComponentActivity() {
    lateinit var model: DesignModel
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        model = ViewModelProvider(this)[DesignModel::class.java]
        setContent { ShoppingApp(model, intent.getBooleanExtra("open_delivery", false)) }
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.controller.refreshExpiry() }
}
