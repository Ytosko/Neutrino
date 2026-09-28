package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.goals.Conditions
import dev.ytosko.neutrino.domain.goals.HormoneUnit
import dev.ytosko.neutrino.domain.goals.Workout
import dev.ytosko.neutrino.domain.goals.WorkoutType
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.glucose.LocalGlucoseUnit
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.text.NumberFormat

internal fun workoutIcon(type: WorkoutType): Int = when (type) {
    WorkoutType.Walking -> R.drawable.ic_footprints
    WorkoutType.Running -> R.drawable.ic_activity
    WorkoutType.Cycling -> R.drawable.ic_bike
}

@Composable
internal fun workoutColor(type: WorkoutType): Color = when (type) {
    WorkoutType.Walking -> NeutrinoTheme.colors.green.solid
    WorkoutType.Running -> NeutrinoTheme.colors.coral.solid
    WorkoutType.Cycling -> NeutrinoTheme.colors.sky.solid
}

@Composable
internal fun workoutName(type: WorkoutType): String = stringResource(
    when (type) {
        WorkoutType.Walking -> R.string.workout_walking
        WorkoutType.Running -> R.string.workout_running
        WorkoutType.Cycling -> R.string.workout_cycling
    },
)

/** "1 h 30 min" or "45 min". */
internal fun minutesText(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60} h" + (if (minutes % 60 > 0) " ${minutes % 60} min" else "") else "$minutes min"

@Composable
private fun workoutSummary(w: Workout): String {
    val parts = listOfNotNull(
        w.steps?.let { stringResource(R.string.workout_steps_value, NumberFormat.getIntegerInstance().format(it)) },
        w.distanceKm?.let { "${formatNumber(it)} km" },
        w.minutes?.let { minutesText(it) },
        pluralStringResource(R.plurals.workout_days_week, w.daysPerWeek, w.daysPerWeek),
    )
    return parts.joinToString(" · ")
}

/** Settings → Daily goals → Workouts: the person's regular workouts, used for the day's energy. */
@Composable
fun WorkoutsScreen(viewModel: GoalsViewModel, onBack: () -> Unit) {
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Workout?>(null) }

    SetupScaffold(
        title = stringResource(R.string.workouts_title),
        subtitle = stringResource(R.string.workouts_body),
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            if (workouts.isNotEmpty()) {
                GoalGroup(stringResource(R.string.workouts_section)) {
                    workouts.forEachIndexed { index, w ->
                        if (index > 0) GoalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 60.dp)
                                .clickable(role = Role.Button) { editing = w }
                                .padding(start = 14.dp, end = Spacing.sm, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ColorIcon(workoutIcon(w.type), workoutColor(w.type))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(workoutName(w.type), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    workoutSummary(w),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.workouts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            Button(
                onClick = { editing = Workout(viewModel.newWorkoutId(), WorkoutType.Walking, daysPerWeek = 5) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.workouts_add), modifier = Modifier.padding(start = Spacing.sm))
            }
        }
    }

    editing?.let { w ->
        WorkoutSheet(
            start = w,
            isNew = workouts.none { it.id == w.id },
            onSave = {
                viewModel.saveWorkout(it)
                editing = null
            },
            onDelete = {
                viewModel.removeWorkout(w.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
internal fun ColorIcon(icon: Int, color: Color, size: androidx.compose.ui.unit.Dp = 29.dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkoutSheet(start: Workout, isNew: Boolean, onSave: (Workout) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var type by remember { mutableStateOf(start.type) }
    var steps by remember { mutableStateOf(start.steps?.toString().orEmpty()) }
    var hours by remember { mutableStateOf(start.minutes?.let { (it / 60).toString() }.orEmpty()) }
    var mins by remember { mutableStateOf(start.minutes?.let { (it % 60).toString() }.orEmpty()) }
    var km by remember { mutableStateOf(start.distanceKm?.let(::formatNumber).orEmpty()) }
    var days by remember { mutableStateOf(start.daysPerWeek) }
    val totalMinutes = ((hours.toIntOrNull() ?: 0) * 60 + (mins.toIntOrNull() ?: 0)).takeIf { it > 0 }
    val workout = Workout(
        id = start.id,
        type = type,
        steps = if (type == WorkoutType.Walking) steps.toIntOrNull()?.takeIf { it > 0 } else null,
        minutes = totalMinutes,
        distanceKm = if (type != WorkoutType.Cycling) parseNumber(km)?.takeIf { it > 0 } else null,
        daysPerWeek = days,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(if (isNew) R.string.workouts_add else R.string.workouts_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                WorkoutType.entries.forEachIndexed { index, t ->
                    SegmentedButton(
                        selected = t == type,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(index, WorkoutType.entries.size),
                    ) { Text(workoutName(t), maxLines = 1) }
                }
            }
            if (type == WorkoutType.Walking) {
                Field(steps, { steps = it.filter(Char::isDigit).take(6) }, stringResource(R.string.workout_steps), stringResource(R.string.workout_steps_unit))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Field(hours, { hours = it.filter(Char::isDigit).take(2) }, stringResource(R.string.workout_hours), "h", Modifier.weight(1f))
                Field(mins, { mins = it.filter(Char::isDigit).take(3) }, stringResource(R.string.workout_minutes), "min", Modifier.weight(1f))
            }
            if (type != WorkoutType.Cycling) {
                Field(
                    km,
                    { km = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(5) },
                    stringResource(R.string.workout_distance),
                    "km",
                    decimal = true,
                )
            }
            Text(stringResource(R.string.workout_how_often), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    FilterChip(
                        selected = d == days,
                        onClick = { days = d },
                        label = { Text(if (d == 7) stringResource(R.string.workout_every_day) else "$d") },
                    )
                }
            }
            Text(
                pluralStringResource(R.plurals.workout_days_week, days, days),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onSave(workout) },
                enabled = workout.filled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.goals_save)) }
            if (!isNew) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.workouts_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}
