package dev.ytosko.neutrino.metersim

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ytosko.neutrino.glucose.MealFlag
import dev.ytosko.neutrino.glucose.SampleType
import dev.ytosko.neutrino.glucose.SensorStatus
import dev.ytosko.neutrino.glucose.SimulatedMeter
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Test-only virtual meter. Every reading here is made up. */
class MainActivity : ComponentActivity() {

    private lateinit var gatt: MeterGattServer

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }

    @SuppressLint("MissingPermission")
    private fun start() {
        runCatching { getSystemService(BluetoothManager::class.java).adapter.name = "CONTOUR PLUS ELITE" }
        gatt.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gatt = MeterGattServer(applicationContext, SimulatedMeter())
        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0B63B6))) {
                Surface(Modifier.fillMaxSize()) { SimulatorScreen(gatt) }
            }
        }
    }

    override fun onDestroy() {
        gatt.stop()
        super.onDestroy()
    }
}

private enum class Result(val label: String) { Normal("Normal"), High("HI"), Low("LO"), Control("Control solution"), StripError("Strip error") }

@Composable
private fun SimulatorScreen(gatt: MeterGattServer) {
    val meter = gatt.meter
    val status by gatt.status.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    var value by remember { mutableStateOf("7.2") }
    var meal by remember { mutableStateOf(MealFlag.None) }
    var result by remember { mutableStateOf(Result.Normal) }
    var writable by remember { mutableStateOf(meter.clockWritable) }
    var filter by remember { mutableStateOf(meter.supportsSequenceFilter) }
    var kgPerL by remember { mutableStateOf(meter.reportsInKgPerL) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Meter Simulator", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
            Text("${meter.model} · ${meter.serial} · test data only", style = MaterialTheme.typography.bodySmall)
        }
        item {
            tick.let { }
            val now = System.currentTimeMillis()
            val left = ((status.advertisingUntil - now) / 1_000).coerceAtLeast(0)
            Text(
                buildString {
                    append(if (left > 0) "Bluetooth on (advertising ${left}s)" else "Bluetooth asleep")
                    append(" · ${if (status.connected.isEmpty()) "no phone connected" else "phone connected"}")
                    append("\nMeter clock ${meter.meterNow().format(CLOCK)} (${skew(meter.clockSkewSeconds)})")
                    append(" · ${meter.stored.size} readings stored")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { gatt.advertise(120, "pairing mode") }, modifier = Modifier.padding(top = 6.dp)) {
                Text("Pairing mode (2 min)")
            }
        }

        item { HorizontalDivider() }
        item {
            Text("Take a test", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value, { value = it }, label = { Text("mmol/L") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Chips("Meal mark", MealFlag.entries.filter { it != MealFlag.Casual }, meal, { it.name }) { meal = it }
            Chips("Result", Result.entries, result, { it.label }) { result = it }
            Button(
                onClick = {
                    val mmol = value.replace(',', '.').toDoubleOrNull()
                    when (result) {
                        Result.Normal -> meter.takeTest(mmol, meal)
                        Result.High -> meter.takeTest(null, meal, SensorStatus.RESULT_TOO_HIGH)
                        Result.Low -> meter.takeTest(null, meal, SensorStatus.RESULT_TOO_LOW)
                        Result.Control -> meter.takeTest(mmol, sampleType = SampleType.ControlSolution)
                        Result.StripError -> meter.takeTest(mmol, sensorStatus = SensorStatus.SAMPLE_INSUFFICIENT)
                    }
                    gatt.log("Test taken: ${if (result == Result.Normal) "$mmol mmol/L" else result.label}, ${meal.name}")
                    // Like a real meter: Bluetooth wakes up for a short while to send the result.
                    gatt.advertise(45, "after test")
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("Take test") }
            OutlinedButton(
                onClick = { meter.takeTest(value.replace(',', '.').toDoubleOrNull() ?: 6.0, meal); gatt.log("Test taken quietly (phone away)") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Take test without Bluetooth (phone away)") }
        }

        item { HorizontalDivider() }
        item {
            Text("Meter clock", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { meter.clockSkewSeconds -= 3600 }) { Text("-1 h") }
                OutlinedButton(onClick = { meter.clockSkewSeconds -= 15 * 60 }) { Text("-15 min") }
                OutlinedButton(onClick = { meter.clockSkewSeconds += 15 * 60 }) { Text("+15 min") }
                OutlinedButton(onClick = { meter.clockSkewSeconds += 3600 }) { Text("+1 h") }
                OutlinedButton(onClick = { meter.clockSkewSeconds = 0 }) { Text("Correct") }
                OutlinedButton(onClick = {
                    meter.setMeterClock(LocalDateTime.of(2020, 1, 1, 0, 0))
                    gatt.log("Clock reset to 2020-01-01 (battery swap)")
                }) { Text("Battery swap reset") }
            }
            Toggle("Phone may set the clock", writable) {
                writable = it
                meter.clockWritable = it
                gatt.refreshClockService()
            }
            Toggle("Supports \"records from sequence\" filter", filter) { filter = it; meter.supportsSequenceFilter = it }
            Toggle("Report in mg/dL units (kg/L)", kgPerL) { kgPerL = it; meter.reportsInKgPerL = it }
        }

        item { HorizontalDivider() }
        item { Text("Bluetooth log", style = MaterialTheme.typography.titleMedium) }
        items(status.log) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun skew(seconds: Long): String = when {
    seconds == 0L -> "correct"
    seconds > 0 -> "${fmt(seconds)} fast"
    else -> "${fmt(-seconds)} slow"
}

private fun fmt(s: Long): String = if (s >= 3600) "${s / 3600} h ${s % 3600 / 60} min" else "${s / 60} min"

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss")
