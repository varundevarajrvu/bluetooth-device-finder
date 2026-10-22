package app.locate.ui.zones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.locate.ui.common.BackTopBar
import app.locate.ui.common.InfoCard
import app.locate.ui.zones.SafeZonesViewModel.Companion.MAX_RADIUS_M
import app.locate.ui.zones.SafeZonesViewModel.Companion.MIN_RADIUS_M
import kotlin.math.roundToInt

@Composable
fun SafeZonesScreen(onBack: () -> Unit, vm: SafeZonesViewModel = hiltViewModel()) {
    val zones by vm.zones.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar("Safe zones", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add zone") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoCard(
                    "No alert fires for a device left inside a safe zone, like a speaker at home. Locate judges by where your " +
                        "phone was when the device disconnected, not where you are now.",
                )
            }
            if (zones.isEmpty()) item { Text("No safe zones yet. Add home first.") }
            items(zones, key = { it.id }) { z ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(z.name, style = MaterialTheme.typography.titleMedium)
                            Text("%.5f, %.5f · %d m radius".format(z.lat, z.lng, z.radiusM.roundToInt()), style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { vm.delete(z.id) }) { Icon(Icons.Default.Delete, "Delete ${z.name}") }
                    }
                }
            }
        }
    }

    if (adding) AddZoneDialog(vm, onDismiss = { adding = false })
}

@Composable
private fun AddZoneDialog(vm: SafeZonesViewModel, onDismiss: () -> Unit) {
    val locating by vm.locating.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("Home") }
    var lat by remember { mutableStateOf("") }
    var lng by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(150f) }
    var note by remember { mutableStateOf<String?>(null) }
    val latValue = lat.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lngValue = lng.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add safe zone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedButton(
                    onClick = {
                        vm.currentLocation { fix ->
                            if (fix == null) {
                                note = "Couldn't get a location. Is location on?"
                            } else {
                                lat = "%.6f".format(java.util.Locale.US, fix.lat)
                                lng = "%.6f".format(java.util.Locale.US, fix.lng)
                                note = "Accuracy ±${fix.accuracyM.roundToInt()} m"
                            }
                        }
                    },
                    enabled = !locating,
                ) { Text(if (locating) "Locating…" else "Use my current location") }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, singleLine = true, keyboardOptions = decimal)
                OutlinedTextField(value = lng, onValueChange = { lng = it }, label = { Text("Longitude") }, singleLine = true, keyboardOptions = decimal)
                Text("Radius: ${radius.roundToInt()} m")
                Slider(value = radius, onValueChange = { radius = (it / 50).roundToInt() * 50f }, valueRange = MIN_RADIUS_M..MAX_RADIUS_M)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.add(name, latValue!!, lngValue!!, radius.toDouble())
                    onDismiss()
                },
                enabled = name.isNotBlank() && latValue != null && lngValue != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
