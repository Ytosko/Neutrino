package dev.ytosko.neutrino.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.goals.LiverGrades
import dev.ytosko.neutrino.domain.goals.LiverLog
import dev.ytosko.neutrino.domain.goals.LiverTest
import dev.ytosko.neutrino.ui.components.LiftedContextMenu
import dev.ytosko.neutrino.ui.components.MenuAction
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/** What the trend chart shows. */
private enum class LiverMetric { Cap, Kpa, Alt }

private fun LiverTest.value(m: LiverMetric): Double? = when (m) {
    LiverMetric.Cap -> cap?.toDouble()
    LiverMetric.Kpa -> kpa
    LiverMetric.Alt -> alt?.toDouble()
}

/** "this month", "4 months ago", "1 year ago". */
@Composable
private fun ago(date: LocalDate): String {
    val months = Period.between(date, LocalDate.now()).toTotalMonths().toInt()
    return when {
        months < 1 -> stringResource(R.string.liver_this_month)
        months < 12 -> androidx.compose.ui.res.pluralStringResource(R.plurals.liver_months_ago, months, months)
        else -> androidx.compose.ui.res.pluralStringResource(R.plurals.liver_years_ago, months / 12, months / 12)
    }
}

/** One line for a result: "CAP 286 (S2) · 8.5 kPa (F2) · ALT 39 · AST 30". */
@Composable
private fun resultLine(t: LiverTest): String = listOfNotNull(
    t.cap?.let { "CAP $it (S${t.steatosis})" } ?: t.steatosisGrade?.let { "S$it" },
    t.kpa?.let { "${formatNumber(it)} kPa (${fibrosisLabel(t.fibrosis!!)})" } ?: t.fibrosisGrade?.let(::fibrosisLabel),
    t.alt?.let { "ALT $it" },
    t.ast?.let { "AST $it" },
).joinToString(" · ")

/**
 * Settings → Daily goals → My conditions → Fatty liver: every FibroScan and blood result by date,
 * newest first, with the latest of each on top and a trend chart. The whole log goes to the AI
 * with goal suggestions, so it can see how the liver is doing.
 */
@Composable
fun LiverScreen(viewModel: GoalsViewModel, onBack: () -> Unit) {
    val tests by viewModel.liverTests.collectAsStateWithLifecycle()
    val saved by viewModel.savedConditions.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val haptics = LocalHapticFeedback.current
    var logging by remember { mutableStateOf<LiverTest?>(null) }
    var deleting by remember { mutableStateOf<LiverTest?>(null) }
    var removing by remember { mutableStateOf(false) }
    var lifted by remember { mutableStateOf<Pair<LiverTest, Rect>?>(null) }
    val blur by animateDpAsState(if (lifted != null) 14.dp else 0.dp, tween(200), label = "blur")
    var askedFirst by rememberSaveable { mutableStateOf(false) }

    // Just added and nothing logged yet: start with the first result.
    LaunchedEffect(saved, tests.isEmpty()) {
        if (saved != null && tests.isEmpty() && !askedFirst) {
            askedFirst = true
            logging = LiverTest(viewModel.newLiverTestId(), LocalDate.now().toEpochDay())
        }
    }

    SetupScaffold(
        title = stringResource(R.string.conditions_liver),
        subtitle = stringResource(R.string.liver_body),
        onBack = onBack,
        modifier = Modifier.blur(blur),
        bottomBar = {
            Button(
                onClick = { logging = LiverTest(viewModel.newLiverTestId(), LocalDate.now().toEpochDay()) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.liver_log), modifier = Modifier.padding(start = Spacing.sm))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            val scan = LiverLog.latestScan(tests)
            val blood = LiverLog.latestBlood(tests)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                LatestCard(
                    title = stringResource(R.string.liver_latest_scan),
                    big = scan?.let { listOfNotNull(it.steatosis?.let { s -> "S$s" }, it.fibrosis?.let(::fibrosisLabel)).joinToString(" · ") },
                    small = scan?.let { listOfNotNull(it.cap?.let { c -> "CAP $c" }, it.kpa?.let { k -> "${formatNumber(k)} kPa" }).joinToString(" · ") },
                    date = scan?.date,
                    modifier = Modifier.weight(1f),
                )
                LatestCard(
                    title = stringResource(R.string.liver_latest_blood),
                    big = blood?.alt?.let { "ALT $it" } ?: blood?.ast?.let { "AST $it" },
                    small = blood?.let { if (it.alt != null && it.ast != null) "AST ${it.ast} U/L" else "U/L" },
                    date = blood?.date,
                    modifier = Modifier.weight(1f),
                )
            }

            // The trend of one value across results.
            val metrics = LiverMetric.entries.filter { m -> tests.count { it.value(m) != null } >= 2 }
            if (metrics.isNotEmpty()) {
                var chosen by rememberSaveable { mutableStateOf(metrics.first()) }
                val metric = if (chosen in metrics) chosen else metrics.first()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        metrics.forEach { m ->
                            FilterChip(
                                selected = m == metric,
                                onClick = { chosen = m },
                                label = {
                                    Text(
                                        when (m) {
                                            LiverMetric.Cap -> "CAP"
                                            LiverMetric.Kpa -> stringResource(R.string.liver_stiffness)
                                            LiverMetric.Alt -> "ALT"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    val points = tests.filter { it.value(metric) != null }.sortedBy { it.epochDay }.takeLast(12)
                    TrendBars(
                        points = points.map { it.date to it.value(metric)!! },
                        color = NeutrinoTheme.colors.amber.solid,
                        unit = when (metric) {
                            LiverMetric.Cap -> "dB/m"
                            LiverMetric.Kpa -> "kPa"
                            LiverMetric.Alt -> "U/L"
                        },
                        locale = locale,
                    )
                }
            }

            if (tests.isNotEmpty()) {
                GoalGroup(stringResource(R.string.weight_history)) {
                    // Newest at the top, oldest at the bottom.
                    tests.sortedByDescending { it.epochDay }.forEachIndexed { index, t ->
                        if (index > 0) GoalDivider()
                        var bounds by remember { mutableStateOf(Rect.Zero) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { bounds = it.boundsInWindow() }
                                .graphicsLayer { alpha = if (lifted?.first?.id == t.id) 0f else 1f }
                                .combinedClickable(
                                    role = Role.Button,
                                    onClick = { logging = t },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        lifted = t to bounds
                                    },
                                ),
                        ) { ResultRow(t, dates) }
                    }
                }
            }

            TextButton(onClick = { removing = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.conditions_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    lifted?.let { (t, rect) ->
        LiftedContextMenu(
            bounds = rect,
            shape = RoundedCornerShape(12.dp),
            actions = listOf(
                MenuAction(stringResource(R.string.liver_edit), R.drawable.ic_pencil) {
                    lifted = null
                    logging = t
                },
                MenuAction(stringResource(R.string.weight_delete), R.drawable.ic_trash, destructive = true) {
                    lifted = null
                    deleting = t
                },
            ),
            onDismiss = { lifted = null },
        ) { ResultRow(t, dates) }
    }

    logging?.let { t ->
        LiverTestSheet(
            start = t,
            isNew = tests.none { it.id == t.id },
            onSave = {
                viewModel.saveLiverTest(it)
                logging = null
            },
            onDismiss = { logging = null },
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.liver_delete_title)) },
            text = { Text("${t.date.format(dates)} · ${resultLine(t)}") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeLiverTest(t.id)
                    deleting = null
                }) { Text(stringResource(R.string.weight_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.liver_remove_title)) },
            text = { Text(stringResource(R.string.liver_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    saved?.let { viewModel.setConditions(it.copy(liver = false)) }
                    removing = false
                    onBack()
                }) { Text(stringResource(R.string.conditions_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

@Composable
private fun LatestCard(title: String, big: String?, small: String?, date: LocalDate?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (big.isNullOrEmpty() || date == null) {
            Text(stringResource(R.string.liver_none_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(big, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (!small.isNullOrEmpty()) Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ago(date), style = MaterialTheme.typography.bodySmall, color = NeutrinoTheme.colors.amber.content)
        }
    }
}

@Composable
private fun ResultRow(t: LiverTest, dates: DateTimeFormatter) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(t.date.format(dates), style = MaterialTheme.typography.bodyLarge)
        Text(resultLine(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A result's date and values: FibroScan, blood test, or both. At least one value is needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiverTestSheet(start: LiverTest, isNew: Boolean, onSave: (LiverTest) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var t by remember { mutableStateOf(start) }
    var cap by remember { mutableStateOf(start.cap?.toString().orEmpty()) }
    var kpa by remember { mutableStateOf(start.kpa?.let(::formatNumber).orEmpty()) }
    var alt by remember { mutableStateOf(start.alt?.toString().orEmpty()) }
    var ast by remember { mutableStateOf(start.ast?.toString().orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    val digits = { s: String -> s.filter(Char::isDigit).take(4) }
    val decimals = { s: String -> s.filter { it.isDigit() || it == '.' || it == ',' }.take(5) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(if (isNew) R.string.liver_log else R.string.liver_edit), style = MaterialTheme.typography.titleLarge)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { pickingDate = true }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.conditions_test_date), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
                Text(
                    if (t.date == LocalDate.now()) stringResource(R.string.weight_today) else t.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(stringResource(R.string.liver_scan_section), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Field(cap, { s ->
                    cap = digits(s).take(3)
                    t = t.copy(cap = cap.toIntOrNull()?.takeIf { it in 100..400 })
                }, "CAP", "dB/m", Modifier.weight(1f))
                Field(kpa, { s ->
                    kpa = decimals(s)
                    t = t.copy(kpa = parseNumber(kpa)?.takeIf { it in 1.0..75.0 })
                }, stringResource(R.string.liver_stiffness), "kPa", Modifier.weight(1f), decimal = true)
            }
            GradePicker(
                title = stringResource(R.string.liver_fat_grade),
                grades = listOf(0, 1, 2, 3),
                label = { "S$it" },
                describe = { steatosisWord(it) },
                fromNumbers = t.cap?.let(LiverGrades::steatosis),
                picked = t.steatosisGrade,
                onPick = { t = t.copy(steatosisGrade = it) },
            )
            GradePicker(
                title = stringResource(R.string.liver_scar_grade),
                grades = listOf(1, 2, 3, 4),
                label = ::fibrosisLabel,
                describe = { fibrosisWord(it) },
                fromNumbers = t.kpa?.let(LiverGrades::fibrosis),
                picked = t.fibrosisGrade,
                onPick = { t = t.copy(fibrosisGrade = it) },
            )

            Text(stringResource(R.string.liver_blood_section), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Field(alt, { s ->
                    alt = digits(s)
                    t = t.copy(alt = alt.toIntOrNull()?.takeIf { it in 1..3_000 })
                }, "ALT", "U/L", Modifier.weight(1f))
                Field(ast, { s ->
                    ast = digits(s)
                    t = t.copy(ast = ast.toIntOrNull()?.takeIf { it in 1..3_000 })
                }, stringResource(R.string.liver_ast_optional), "U/L", Modifier.weight(1f))
            }
            Note(stringResource(R.string.liver_note))
            Button(
                onClick = { onSave(t) },
                enabled = !t.isEmpty && (isNew || t != start),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.goals_save)) }
        }
    }

    if (pickingDate) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = t.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { t = t.copy(epochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()) }
                    pickingDate = false
                }) { Text(stringResource(R.string.goals_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.backup_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** Bars for one value over the results, scaled to the values; tap a bar to read it. */
@Composable
private fun TrendBars(points: List<Pair<LocalDate, Double>>, color: Color, unit: String, locale: Locale) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val select by rememberUpdatedState<(Int?) -> Unit> { selected = if (it == selected) null else it }
    val count = points.size.coerceAtLeast(1)
    val low = floor((points.minOfOrNull { it.second } ?: 0.0) * 0.9)
    val high = ceil((points.maxOfOrNull { it.second } ?: 1.0) * 1.05)
    // "20 May"; with results over more than a year, "May 2025" so the year is clear.
    val spansYears = points.map { it.first.year }.distinct().size > 1
    val short = DateTimeFormatter.ofPattern(if (spansYears) "MMM yyyy" else "d MMM", locale)
    val gutter = 40.dp

    val pick = selected?.let { points.getOrNull(it) }
    Text(
        pick?.let { "${formatNumber(it.second)} $unit · ${it.first.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))}" }
            ?: stringResource(R.string.liver_chart_hint),
        style = MaterialTheme.typography.labelLarge,
        color = if (pick != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .semantics { contentDescription = unit }
            .pointerInput(count) {
                detectTapGestures { o ->
                    val plot = size.width - gutter.toPx()
                    if (o.x in 0f..plot) select((o.x / (plot / count)).toInt().coerceIn(0, count - 1))
                }
            },
    ) {
        val plotWidth = size.width - gutter.toPx()
        val plotHeight = size.height - 18.dp.toPx()
        val slot = plotWidth / count
        val barWidth = (slot * 0.55f).coerceAtMost(28.dp.toPx())
        fun y(v: Double) = (plotHeight - (v - low) / (high - low).coerceAtLeast(1e-6) * plotHeight).toFloat()
        listOf(low, (low + high) / 2, high).forEach { v ->
            val yy = y(v)
            drawLine(grid, Offset(0f, yy), Offset(plotWidth, yy), strokeWidth = 1f)
            val text = measurer.measure(String.format(Locale.getDefault(), "%.0f", v), labelStyle)
            drawText(text, topLeft = Offset(plotWidth + 6.dp.toPx(), (yy - text.size.height / 2f).coerceIn(0f, plotHeight - text.size.height)))
        }
        points.forEachIndexed { i, (date, v) ->
            val x = slot * i + (slot - barWidth) / 2
            val top = y(v).coerceAtMost(plotHeight - barWidth)
            val alpha = if (selected == null || selected == i) 1f else 0.35f
            drawRoundRect(color.copy(alpha = alpha), topLeft = Offset(x, top), size = Size(barWidth, plotHeight - top), cornerRadius = CornerRadius(barWidth / 2))
            if (count <= 6 || i % 2 == (count - 1) % 2) {
                val text = measurer.measure(date.format(short), labelStyle)
                drawText(text, topLeft = Offset((slot * i + slot / 2 - text.size.width / 2f).coerceIn(0f, plotWidth - text.size.width), plotHeight + 4.dp.toPx()))
            }
        }
    }
}
