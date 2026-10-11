package staff.android.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import staff.android.design.core.*

@Composable private fun Brand(action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_common_goods), null, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp)); Text("Common Goods", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable private fun Photo(product: Product, modifier: Modifier) {
    val context = LocalContext.current
    val images = remember(context.applicationContext) { ProductImages.get(context) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, product.imageKey) { value = images.load(product.imageKey) }
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), product.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
@Composable fun ShoppingApp(model: DesignModel, openDelivery: Boolean) {
    val state by model.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (openDelivery) "delivery" else "shop") }
    var selected by rememberSaveable { mutableStateOf("camera") }
    val back = { model.controller.leave(); page = if (page == "delivery") "detail" else "shop" }
    BackHandler(page != "shop") { back() }
    CommonTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxWidth().semantics { testTagsAsResourceId = true }) {
                    when (page) {
                        "shop" -> Shop { selected = it; page = "detail" }
                        "detail" -> Detail(Catalog.featured.first { it.id == selected }, back) { page = "delivery" }
                        else -> Delivery(state, back, model.controller::select, model.controller::check)
                    }
                }
            }
        }
    }
}
@Composable private fun Shop(select: (String) -> Unit) {
    val config = LocalConfiguration.current
    val columns = if (config.fontScale >= 1.5f) 1 else if (config.screenWidthDp >= 700) 3 else 2
    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("shop"), contentPadding = PaddingValues(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Brand("Explore") { select("camera") } }
        item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Good things,\nevery day.", style = MaterialTheme.typography.displaySmall)
            Text("A considered collection for everyday living.", style = MaterialTheme.typography.bodyLarge)
        } }
        items(Catalog.featured, key = { it.id }) { product -> Column(Modifier.padding(horizontal = 12.dp)) {
            Photo(product, Modifier.fillMaxWidth().aspectRatio(1f)); Text(product.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            Text(product.price, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { select(product.id) }, modifier = Modifier.testTag("explore_${product.id}"), contentPadding = PaddingValues(0.dp)) { Text("Explore ${product.title}") }
        } }
    }
}
@Composable private fun Detail(product: Product, back: () -> Unit, delivery: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("product_detail"), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Brand("Shop", back) }
        item { Photo(product, Modifier.fillMaxWidth().padding(horizontal = 20.dp).aspectRatio(1.25f)) }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(product.title, style = MaterialTheme.typography.displaySmall)
            Text(product.price, Modifier.testTag("product_price"), style = MaterialTheme.typography.headlineSmall)
            Text(product.description, style = MaterialTheme.typography.bodyLarge)
            if (product.id == "camera") Button(onClick = delivery, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_delivery")) { Text("Delivery options") }
            else Text("Browse our Camera to explore delivery options.", style = MaterialTheme.typography.bodyMedium)
        } }
    }
}
private fun zoneLabel(zone: String) = if (zone == "city") "City centre" else "North district"
@Composable private fun Delivery(state: DeliveryState, back: () -> Unit, select: (DeliveryKey) -> Unit, check: () -> Unit) {
    val camera = Catalog.featured.first { it.id == "camera" }
    LazyColumn(Modifier.fillMaxSize().testTag("delivery_screen"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Brand("Camera", back) }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Delivery,\nmade clearer.", style = MaterialTheme.typography.displaySmall)
            Text("Find an estimate for your area before you decide.", style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Photo(camera, Modifier.width(96.dp).height(112.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Camera", style = MaterialTheme.typography.titleLarge)
                    Text("₹12,999", Modifier.testTag("delivery_product_price"), style = MaterialTheme.typography.headlineSmall)
                    Text("From our collection", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Where should it go?", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("city", "north").forEach { zone -> FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary), selected = state.key.zone == zone, onClick = { select(state.key.copy(zone = zone)) }, label = { Text(zoneLabel(zone)) }, modifier = Modifier.testTag("zone_$zone")) }
            }
            Text("How many?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                (1..3).forEach { n -> FilterChip(colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary), selected = state.key.quantity == n, onClick = { select(state.key.copy(quantity = n)) }, label = { Text("$n") }, modifier = Modifier.heightIn(min = 48.dp).testTag("quantity_$n")) }
            }
            Text("${state.key.quantity} Camera${if (state.key.quantity == 1) "" else "s"} · ${zoneLabel(state.key.zone)}", Modifier.testTag("selected_scope"))
            Button(onClick = check, enabled = state.phase != EstimatePhase.Loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("check_delivery")) { Text(if (state.phase == EstimatePhase.Idle) "Check delivery" else "Check again") }
        } }
        item { EstimateCard(state) }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Good together.", style = MaterialTheme.typography.headlineSmall)
            when (state.optionalPhase) {
                OptionalPhase.Idle -> Text("Check delivery to explore a few useful additions.")
                OptionalPhase.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("related_loading")); Text("Finding something to go with it…") }
                OptionalPhase.Unavailable -> Text("Related items aren't available right now. Your delivery result is shown above.", Modifier.testTag("related_unavailable"))
                OptionalPhase.Ready -> {
                    if (state.recommendedIds.isEmpty()) Text("More good things soon.", Modifier.testTag("related_empty"))
                    state.recommendedIds.forEach { id -> Catalog.featured.firstOrNull { it.id == id }?.let { product ->
                        Row(Modifier.fillMaxWidth().testTag("related_$id"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Photo(product, Modifier.size(88.dp)); Column(Modifier.weight(1f)) { Text(product.title, style = MaterialTheme.typography.titleMedium); Text(product.price) }
                        }
                    } }
                }
            }
        } }
        item { OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp).testTag("back_camera")) { Text("Back to Camera") } }
    }
}
@Composable private fun EstimateCard(state: DeliveryState) {
    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val title = when (state.phase) {
                EstimatePhase.Idle -> "An estimate for your plans"
                EstimatePhase.Loading -> "Checking delivery…"
                EstimatePhase.Ready -> "Estimated in ${state.estimate!!.arrivalDays} days"
                EstimatePhase.Unserviceable -> "No delivery option yet"
                EstimatePhase.Deadline -> "Taking longer than expected"
                EstimatePhase.Unavailable -> "Delivery couldn't be checked"
                EstimatePhase.Expired -> "Estimate expired"
            }
            Text(title, Modifier.testTag("estimate_${state.phase.name.lowercase()}"), style = MaterialTheme.typography.headlineSmall)
            if (state.phase == EstimatePhase.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("estimate_loading_progress"))
            if (state.phase == EstimatePhase.Ready) {
                val fee = state.estimate!!.feeMinor
                Text(if (fee == 0L) "Delivery: Free" else "Delivery: ${formatInrMinor(fee)}", Modifier.testTag("delivery_fee"), style = MaterialTheme.typography.titleLarge)
                Text("${state.key.quantity} Camera${if (state.key.quantity == 1) "" else "s"} · ${zoneLabel(state.key.zone)}", Modifier.testTag("estimate_scope"))
            }
            Text(when (state.phase) {
                EstimatePhase.Idle -> "Choose your area and quantity, then check delivery."
                EstimatePhase.Loading -> "Your product details stay available while we check."
                EstimatePhase.Ready -> "An estimate, not a reservation. Stock and final terms are confirmed when you order."
                EstimatePhase.Unserviceable -> "Try another area or a smaller quantity. Your product details are still here."
                EstimatePhase.Deadline -> "The estimate took too long. Your product details are still here; you can try again."
                EstimatePhase.Unavailable -> "We couldn't get a delivery estimate. Please try again."
                EstimatePhase.Expired -> "Check again for a current estimate for your area and quantity."
            }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
