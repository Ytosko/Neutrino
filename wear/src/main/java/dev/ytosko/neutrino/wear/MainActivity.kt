package dev.ytosko.neutrino.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.ytosko.neutrino.wear.data.PhoneLink
import dev.ytosko.neutrino.wear.data.SnapshotStore
import dev.ytosko.neutrino.wear.protocol.WearAction
import dev.ytosko.neutrino.wear.protocol.WearGlucose
import dev.ytosko.neutrino.wear.protocol.WearMacro
import dev.ytosko.neutrino.wear.protocol.WearSnapshot
import dev.ytosko.neutrino.wear.ui.WearColors
import dev.ytosko.neutrino.wear.ui.bandText
import dev.ytosko.neutrino.wear.ui.spoken
import dev.ytosko.neutrino.wear.ui.timeText
import kotlinx.coroutines.launch

/** The watch app: today's macros, water with +250 ml, and the latest glucose reading. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScaffold {
                    TodayScreen()
                }
            }
        }
    }
}

/** Where the last "+250 ml" tap got to. */
private enum class WaterSend { Idle, Sending, Sent, Failed }

@Composable
private fun TodayScreen() {
    val context = LocalContext.current
    val snapshot by remember { SnapshotStore.snapshot(context) }.collectAsStateWithLifecycle(initialValue = null)
    var waterSend by remember { mutableStateOf(WaterSend.Idle) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // Catch up with whatever the phone last published, and ask it for a fresh copy if there's none.
        if (!PhoneLink.pull(context) && SnapshotStore.current(context) == null) {
            PhoneLink.send(context, WearAction.Refresh)
        }
    }

    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ListHeader { Text(stringResource(R.string.home_title)) } }
            val today = snapshot
            if (today == null) {
                item {
                    Text(
                        stringResource(R.string.wear_open_phone),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
                return@TransformingLazyColumn
            }
            item { GlucoseRow(today.glucose) }
            item { MacroRings(today) }
            item {
                Text(
                    stringResource(R.string.home_water) + " · " + today.waterText,
                    color = Color(WearColors.WATER),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (today.canAddWater) {
                item {
                    Button(
                        onClick = {
                            waterSend = WaterSend.Sending
                            scope.launch {
                                val sent = PhoneLink.send(context, WearAction.AddWater(WATER_GLASS_ML))
                                waterSend = if (sent) WaterSend.Sent else WaterSend.Failed
                            }
                        },
                        enabled = waterSend != WaterSend.Sending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(WearColors.WATER_CONTAINER),
                            contentColor = Color(WearColors.WATER),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.home_add_glass)) },
                    )
                }
            }
            when (waterSend) {
                WaterSend.Sent -> item { Status(stringResource(R.string.wear_sent_to_phone)) }
                WaterSend.Failed -> item { Status(stringResource(R.string.wear_phone_unreachable)) }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GlucoseRow(glucose: WearGlucose?) {
    val context = LocalContext.current
    if (glucose == null) {
        Text(
            stringResource(R.string.glucose_widget_none),
            color = Color(WearColors.MUTED),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = glucose.spoken(context) },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(glucose.value, color = Color(WearColors.band(glucose.band)), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(" " + glucose.unit, color = Color(WearColors.MUTED), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(
            glucose.timeText() + " · " + glucose.bandText(context),
            color = Color(WearColors.MUTED),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MacroRings(today: WearSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Ring(today.carbs, stringResource(R.string.macro_carbs), WearColors.CARBS)
            Ring(today.protein, stringResource(R.string.macro_protein), WearColors.PROTEIN)
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            Ring(today.fat, stringResource(R.string.macro_fat), WearColors.FAT)
            Ring(today.kcal, stringResource(R.string.macro_energy), WearColors.KCAL)
        }
    }
}

/** One macro as a ring toward its goal, with the amount and name inside; just the track without a goal. */
@Composable
private fun Ring(macro: WearMacro, label: String, color: Int, diameter: Dp = 64.dp) {
    val ofGoal = macro.goalText?.let { stringResource(R.string.widget_of_goal, macro.text, it) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
            .semantics(mergeDescendants = true) { contentDescription = "$label ${ofGoal ?: macro.text}" },
    ) {
        val progress = (macro.progress ?: 0f).coerceIn(0f, 1f)
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.09f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(WearColors.faint(color)), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (progress > 0f) {
                drawArc(
                    Color(color), -90f, 360f * progress, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = if (progress >= 1f) StrokeCap.Butt else StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(macro.text, color = Color(color), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, color = Color(WearColors.MUTED), fontSize = 10.sp, maxLines = 1)
        }
    }
}

const val WATER_GLASS_ML = 250
