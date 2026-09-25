package dev.ytosko.neutrino.ui.glucose

import dev.ytosko.neutrino.ui.components.MenuAction
import dev.ytosko.neutrino.ui.components.LiftedContextMenu
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import dev.ytosko.neutrino.ui.components.NeutrinoSnackbarHost
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One day's glucose readings: all of them, with a chart, for days with more than a few. */
class GlucoseDayViewModel(
    private val glucose: GlucoseRepository,
    settings: SettingsRepository,
    val date: LocalDate,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    val readings: StateFlow<List<GlucoseEntity>?> = glucose.observeBetween(date, date, zone)
        .map { list -> list.sortedBy { it.measuredAtEpochMs } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val range: StateFlow<ClosedFloatingPointRange<Double>> = settings.settings
        .map { it.glucoseLow..it.glucoseHigh }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 4.0..10.0)

    /** Now on today; the current time of day on a past day. */
    fun newReadingTime(): Instant =
        if (date == LocalDate.now(zone)) Instant.now() else date.atTime(LocalTime.now(zone)).atZone(zone).toInstant()

    fun add(mmolPerL: Double, relation: GlucoseRelation, time: Instant) {
        viewModelScope.launch { glucose.addManual(mmolPerL, time, relation, zone) }
    }

    fun edit(id: String, relation: GlucoseRelation, time: Instant, mmolPerL: Double?) {
        viewModelScope.launch { glucose.edit(id, relation, time, mmolPerL) }
    }

    suspend fun delete(id: String): GlucoseEntity? = glucose.delete(id)

    fun restore(reading: GlucoseEntity) {
        viewModelScope.launch { glucose.restore(reading) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlucoseDayScreen(viewModel: GlucoseDayViewModel, onBack: () -> Unit) {
    val readings by viewModel.readings.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<GlucoseEntity?>(null) }
    var lifted by remember { mutableStateOf<Pair<GlucoseEntity, Rect>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val deleted = stringResource(R.string.glucose_deleted)
    val undo = stringResource(R.string.home_undo)
    val unit = LocalGlucoseUnit.current

    val blurRadius by animateDpAsState(if (lifted != null) 14.dp else 0.dp, animationSpec = tween(200), label = "blur")
    Scaffold(
        modifier = Modifier.blur(blurRadius),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { NeutrinoSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.glucose_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(
                            viewModel.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.glucose_add_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        val list = readings ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = padding.calculateTopPadding() + Spacing.xs,
                bottom = padding.calculateBottomPadding() + Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val width = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            if (list.isEmpty()) {
                item { Text(stringResource(R.string.glucose_none_day), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = width) }
                return@LazyColumn
            }
            item(key = "chart") {
                Card(
                    modifier = width,
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                ) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        val values = list.map { it.mmolPerL }
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                            Stat(stringResource(R.string.report_average), "${unit.format(values.average())} ${unit.label}")
                            Stat(stringResource(R.string.report_lowest), unit.format(values.min()))
                            Stat(stringResource(R.string.report_highest), unit.format(values.max()))
                        }
                        DayChart(list, range, Modifier.fillMaxWidth().height(180.dp))
                    }
                }
            }
            item(key = "list") {
                Card(
                    modifier = width,
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                ) {
                    // Newest first, like a list of what just happened.
                    list.asReversed().forEachIndexed { index, reading ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                        GlucoseRow(
                            reading, range,
                            onClick = { editing = reading },
                            onLongPress = { bounds -> lifted = reading to bounds },
                            hidden = lifted?.first?.id == reading.id,
                        )
                    }
                }
            }
        }
    }

    lifted?.let { (reading, bounds) ->
        LiftedContextMenu(
            bounds = bounds,
            actions = listOf(
                MenuAction(stringResource(R.string.glucose_edit), R.drawable.ic_pencil) {
                    lifted = null
                    editing = reading
                },
                MenuAction(stringResource(R.string.glucose_delete), R.drawable.ic_trash, destructive = true) {
                    lifted = null
                    scope.launch {
                        val stored = viewModel.delete(reading.id) ?: return@launch
                        snackbar.currentSnackbarData?.dismiss()
                        if (snackbar.showSnackbar(deleted, actionLabel = undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            viewModel.restore(stored)
                        }
                    }
                },
            ),
            onDismiss = { lifted = null },
        ) { GlucoseRow(reading, range, onClick = {}) }
    }

    if (adding) {
        GlucoseEditDialog(
            reading = null,
            range = range,
            newReadingTime = remember { viewModel.newReadingTime() },
            onSave = { mmol, relation, time ->
                if (mmol != null) viewModel.add(mmol, relation, time)
                adding = false
            },
            onDelete = {},
            onDismiss = { adding = false },
        )
    }
    editing?.let { reading ->
        GlucoseEditDialog(
            reading = reading,
            range = range,
            onSave = { mmol, relation, time ->
                viewModel.edit(reading.id, relation, time, mmol)
                editing = null
            },
            onDelete = {
                editing = null
                scope.launch {
                    val stored = viewModel.delete(reading.id) ?: return@launch
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(deleted, actionLabel = undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        viewModel.restore(stored)
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The day from midnight to midnight: each reading as a dot at its time, coloured low / in range /
 * high, joined by a thin line, over the shaded target range.
 */
@Composable
private fun DayChart(readings: List<GlucoseEntity>, range: ClosedFloatingPointRange<Double>, modifier: Modifier) {
    val unit = LocalGlucoseUnit.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val band = bandColor(GlucoseBand.InRange).copy(alpha = 0.10f)
    val lowColor = bandColor(GlucoseBand.Low)
    val inColor = bandColor(GlucoseBand.InRange)
    val highColor = bandColor(GlucoseBand.High)
    val zone = ZoneId.systemDefault()
    val description = stringResource(R.string.glucose_day_chart_desc, readings.size, unit.format(range.start), unit.format(range.endInclusive), unit.label)
    Canvas(modifier.semantics { contentDescription = description }) {
        val gutter = 30.dp.toPx()
        val bottom = 18.dp.toPx()
        val plotW = size.width - gutter
        val plotH = size.height - bottom
        val top = maxOf(readings.maxOf { it.mmolPerL }, range.endInclusive) * 1.15
        fun y(v: Double) = (plotH - v / top * plotH).toFloat()
        fun x(epochMs: Long): Float {
            val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().toSecondOfDay()
            return (t / 86_400f) * plotW
        }
        // Target range band with its two values on the right.
        drawRect(band, topLeft = Offset(0f, y(range.endInclusive)), size = Size(plotW, y(range.start) - y(range.endInclusive)))
        listOf(range.start, range.endInclusive).forEach { v ->
            drawLine(grid, Offset(0f, y(v)), Offset(plotW, y(v)), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val text = measurer.measure(unit.format(v), labelStyle)
            drawText(text, topLeft = Offset(plotW + 6.dp.toPx(), y(v) - text.size.height / 2f))
        }
        drawLine(grid, Offset(0f, plotH), Offset(plotW, plotH), strokeWidth = 1f)
        // Hours along the bottom.
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val label = if (h == 24) "" else LocalTime.of(h, 0).format(DateTimeFormatter.ofPattern("H:mm"))
            val px = h / 24f * plotW
            drawLine(grid, Offset(px, plotH), Offset(px, plotH + 4.dp.toPx()), strokeWidth = 1f)
            if (label.isNotEmpty()) {
                val text = measurer.measure(label, labelStyle)
                drawText(text, topLeft = Offset((px - text.size.width / 2f).coerceIn(0f, plotW - text.size.width), plotH + 5.dp.toPx()))
            }
        }
        val points = readings.map { Offset(x(it.measuredAtEpochMs), y(it.mmolPerL)) }
        points.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, strokeWidth = 1.5.dp.toPx()) }
        readings.forEachIndexed { i, r ->
            val color = when (band(r.mmolPerL, range.start, range.endInclusive)) {
                GlucoseBand.Low -> lowColor
                GlucoseBand.InRange -> inColor
                GlucoseBand.High -> highColor
            }
            drawCircle(color, radius = 4.5.dp.toPx(), center = points[i])
        }
    }
}
