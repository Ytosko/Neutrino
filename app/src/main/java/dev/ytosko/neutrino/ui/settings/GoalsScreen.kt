package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.insights.formatWater
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/** Which goal is being edited, with everything the editor needs to know about it. */
private enum class Goal(val default: Int, val step: Int, val range: IntRange, val presets: List<Int>) {
    Carbs(150, 10, 10..1_000, listOf(100, 130, 150, 200, 250)),
    Protein(60, 5, 10..500, listOf(50, 60, 80, 100, 120)),
    Fat(60, 5, 10..500, listOf(40, 50, 60, 70, 80)),
    Calories(2_000, 50, 500..10_000, listOf(1_500, 1_800, 2_000, 2_200, 2_500)),
    Water(2_000, 250, 250..10_000, listOf(1_500, 2_000, 2_500, 3_000)),
}

private fun AppSettings.valueOf(goal: Goal): Int? = when (goal) {
    Goal.Carbs -> carbGoalG
    Goal.Protein -> proteinGoalG
    Goal.Fat -> fatGoalG
    Goal.Calories -> kcalGoal
    Goal.Water -> waterGoalMl
}

/**
 * Settings → Daily goals: a summary of the day's targets on top (how the macros split the day's
 * energy and how that compares with the calorie goal), then each goal as a row; tapping one opens
 * a sheet to set it with a big number, hold-to-repeat − and +, and common values.
 */
@Composable
fun GoalsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val s by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Goal?>(null) }
    fun save(goal: Goal, value: Int?) = scope.launch {
        settings.setGoals(
            if (goal == Goal.Carbs) value else s.carbGoalG,
            if (goal == Goal.Protein) value else s.proteinGoalG,
            if (goal == Goal.Fat) value else s.fatGoalG,
            if (goal == Goal.Calories) value else s.kcalGoal,
            if (goal == Goal.Water) value else s.waterGoalMl,
        )
    }

    SetupScaffold(
        title = stringResource(R.string.goals_title),
        subtitle = stringResource(R.string.goals_body),
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            DaySummary(s)
            GoalGroup(stringResource(R.string.goals_section_nutrients)) {
                GoalRow(Goal.Carbs, s, onClick = { editing = Goal.Carbs })
                GoalDivider()
                GoalRow(Goal.Protein, s, onClick = { editing = Goal.Protein })
                GoalDivider()
                GoalRow(Goal.Fat, s, onClick = { editing = Goal.Fat })
            }
            GoalGroup(stringResource(R.string.goals_section_energy)) {
                GoalRow(Goal.Calories, s, onClick = { editing = Goal.Calories })
                GoalDivider()
                GoalRow(Goal.Water, s, onClick = { editing = Goal.Water })
            }
            Text(
                stringResource(R.string.goals_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ROW_SIDE),
            )
        }
    }

    editing?.let { goal ->
        GoalSheet(goal, s.valueOf(goal), onChange = { save(goal, it) }, onDismiss = { editing = null })
    }
}

@Composable
private fun goalTitle(goal: Goal): String = stringResource(
    when (goal) {
        Goal.Carbs -> R.string.macro_carbs
        Goal.Protein -> R.string.macro_protein
        Goal.Fat -> R.string.macro_fat
        Goal.Calories -> R.string.goals_calories
        Goal.Water -> R.string.home_water
    },
)

private fun goalIcon(goal: Goal): Int = when (goal) {
    Goal.Carbs -> R.drawable.ic_wheat
    Goal.Protein -> R.drawable.ic_drumstick
    Goal.Fat -> R.drawable.ic_nut
    Goal.Calories -> R.drawable.ic_target
    Goal.Water -> R.drawable.ic_droplet
}

@Composable
private fun goalColor(goal: Goal): Color = when (goal) {
    Goal.Carbs -> NeutrinoTheme.colors.carbs
    Goal.Protein -> NeutrinoTheme.colors.protein
    Goal.Fat -> NeutrinoTheme.colors.fat
    Goal.Calories -> MaterialTheme.colorScheme.primary
    Goal.Water -> NeutrinoTheme.colors.water
}

private fun goalText(goal: Goal, value: Int): String = when (goal) {
    Goal.Carbs, Goal.Protein, Goal.Fat -> "$value g"
    Goal.Calories -> "${NumberFormat.getIntegerInstance().format(value)} kcal"
    Goal.Water -> formatWater(value)
}

/**
 * The day at a glance: the calorie goal large, and a bar of how the macro goals share the day's
 * energy (4 kcal per gram of carbs or protein, 9 per gram of fat), with how that sits against
 * the calorie goal.
 */
@Composable
private fun DaySummary(s: AppSettings) {
    val colors = NeutrinoTheme.colors
    val number = NumberFormat.getIntegerInstance()
    val parts = listOfNotNull(
        s.carbGoalG?.let { Triple(Goal.Carbs, it, it * 4) },
        s.proteinGoalG?.let { Triple(Goal.Protein, it, it * 4) },
        s.fatGoalG?.let { Triple(Goal.Fat, it, it * 9) },
    )
    val macroKcal = parts.sumOf { it.third }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GROUP_CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            stringResource(R.string.goals_summary_title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val headline = s.kcalGoal ?: macroKcal.takeIf { it > 0 }
        if (headline == null && s.waterGoalMl == null) {
            Text(
                stringResource(R.string.goals_summary_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (headline != null) {
                Text(
                    number.format(headline),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.goals_summary_kcal_day),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            s.waterGoalMl?.let { water ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_droplet), contentDescription = null, tint = colors.water, modifier = Modifier.size(16.dp))
                    Text(formatWater(water), style = MaterialTheme.typography.titleSmall, color = colors.water)
                }
            }
        }
        if (parts.isNotEmpty()) {
            // The split bar: each macro's share of the energy from the macro goals.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                parts.forEach { (goal, _, kcal) ->
                    Box(
                        Modifier
                            .weight(kcal.toFloat().coerceAtLeast(1f))
                            .height(10.dp)
                            .background(goalColor(goal)),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                parts.forEach { (goal, grams, kcal) ->
                    val share = (kcal * 100f / macroKcal).roundToInt()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(goalColor(goal)))
                        Text(
                            "${goalTitle(goal)} $grams g · $share%",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            val kcalGoal = s.kcalGoal
            val gap = if (kcalGoal == null) null else macroKcal - kcalGoal
            Text(
                when {
                    gap == null -> stringResource(R.string.goals_summary_macros, number.format(macroKcal))
                    kotlin.math.abs(gap) <= 50 -> stringResource(R.string.goals_summary_match, number.format(macroKcal))
                    gap > 0 -> stringResource(R.string.goals_summary_over, number.format(macroKcal), number.format(gap))
                    else -> stringResource(R.string.goals_summary_under, number.format(macroKcal), number.format(-gap))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GoalGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = ROW_SIDE),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(GROUP_CORNER))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) { content() }
    }
}

@Composable
private fun GoalRow(goal: Goal, s: AppSettings, onClick: () -> Unit) {
    val value = s.valueOf(goal)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = ROW_SIDE, end = Spacing.sm, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_GAP),
    ) {
        Box(
            modifier = Modifier.size(ICON_BOX).clip(RoundedCornerShape(7.dp)).background(goalColor(goal)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(goalIcon(goal)), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(goalTitle(goal), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (value == null) stringResource(R.string.goals_off) else goalText(goal, value),
            style = if (value == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else goalColor(goal),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun GoalDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = ROW_SIDE + ICON_BOX + ICON_GAP),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Sets one goal: on or off, a big number with − and + (hold to go faster), and common values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(goal: Goal, initial: Int?, onChange: (Int?) -> Unit, onDismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(initial) }
    val color = goalColor(goal)
    fun set(new: Int?) {
        value = new
        onChange(new)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(goalIcon(goal)), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text(goalTitle(goal), style = MaterialTheme.typography.titleLarge)
            }

            // On or off.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(GROUP_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .toggleable(value = value != null, role = Role.Switch) { on ->
                        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        set(if (on) goal.default else null)
                    }
                    .padding(horizontal = ROW_SIDE, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.goals_track), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = value != null, onCheckedChange = null, modifier = Modifier.scale(0.85f))
            }

            val current = value
            if (current != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StepButton(
                        icon = R.drawable.ic_minus,
                        label = stringResource(R.string.review_less),
                        color = color,
                        enabled = current - goal.step >= goal.range.first,
                        onStep = { value?.let { v -> if (v - goal.step >= goal.range.first) set(v - goal.step) } },
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        val (big, unit) = when (goal) {
                            Goal.Carbs, Goal.Protein, Goal.Fat -> current.toString() to "g"
                            Goal.Calories -> NumberFormat.getIntegerInstance().format(current) to "kcal"
                            Goal.Water -> formatWater(current) to ""
                        }
                        Text(big, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
                        Text(
                            listOf(unit, stringResource(R.string.goals_per_day).lowercase()).filter { it.isNotEmpty() }.joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StepButton(
                        icon = R.drawable.ic_plus,
                        label = stringResource(R.string.review_more),
                        color = color,
                        enabled = current + goal.step <= goal.range.last,
                        onStep = { value?.let { v -> if (v + goal.step <= goal.range.last) set(v + goal.step) } },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                ) {
                    goal.presets.forEach { preset ->
                        FilterChip(
                            selected = preset == current,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                set(preset)
                            },
                            label = { Text(goalText(goal, preset)) },
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.goals_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.goals_done)) }
        }
    }
}

/** A round − or + that steps once on tap and keeps stepping while held. */
@Composable
private fun StepButton(icon: Int, label: String, color: Color, enabled: Boolean, onStep: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        delay(450)
        while (true) {
            step()
            delay(90)
        }
    }
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            step()
        },
        enabled = enabled,
        shape = CircleShape,
        color = color.copy(alpha = if (enabled) 0.14f else 0.06f),
        contentColor = if (enabled) color else color.copy(alpha = 0.38f),
        interactionSource = interaction,
        modifier = Modifier.size(64.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

private val ROW_SIDE = 14.dp
private val ICON_BOX = 29.dp
private val ICON_GAP = 14.dp
private val GROUP_CORNER = 12.dp
