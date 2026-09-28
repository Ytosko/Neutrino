package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.domain.goals.GoalAdvice
import dev.ytosko.neutrino.domain.goals.GoalMath
import dev.ytosko.neutrino.domain.goals.Intakes
import dev.ytosko.neutrino.ui.ai.aiErrorMessage
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs

/**
 * ✨ on Daily goals: shows what the suggestion will use (physique is needed; conditions, medicines
 * and workouts are optional), asks about workouts if none are set, then shows the AI's intakes to
 * edit and confirm. Nothing changes until "Confirm intakes".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalAdvisorSheet(viewModel: GoalsViewModel, onAddWorkouts: () -> Unit, onOpenAi: () -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val physique by viewModel.physique.collectAsStateWithLifecycle()
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    val medicines by viewModel.medicineList.collectAsStateWithLifecycle()
    val advice by viewModel.advice.collectAsStateWithLifecycle()
    var askWorkouts by remember { mutableStateOf(false) }
    val hasWorkouts = workouts.any { it.filled }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.clearAdvice()
            onDismiss()
        },
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter)
                .padding(bottom = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ColorIcon(R.drawable.ic_wand_sparkles, MaterialTheme.colorScheme.primary, 32.dp)
                Text(stringResource(R.string.advisor_title), style = MaterialTheme.typography.titleLarge)
            }
            when (val a = advice) {
                is Advice.Ready -> AdviceResult(
                    advice = a.advice,
                    diabetes = conditions.diabetes,
                    onConfirm = {
                        viewModel.confirm(it)
                        onDismiss()
                    },
                    onCancel = {
                        viewModel.clearAdvice()
                        onDismiss()
                    },
                )

                Advice.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text(stringResource(R.string.advisor_working), modifier = Modifier.padding(start = Spacing.md))
                }

                else -> {
                    Text(
                        stringResource(R.string.advisor_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        CheckRow(
                            done = physique.complete,
                            title = stringResource(R.string.physique_section),
                            detail = stringResource(if (physique.complete) R.string.advisor_physique_done else R.string.advisor_physique_missing),
                            required = true,
                        )
                        GoalDivider()
                        CheckRow(
                            done = conditions.any,
                            title = stringResource(R.string.conditions_title),
                            detail = conditionNames(conditions).ifEmpty { stringResource(R.string.advisor_none_optional) },
                        )
                        GoalDivider()
                        CheckRow(
                            done = medicines.isNotEmpty(),
                            title = stringResource(R.string.advisor_medicines),
                            detail = if (medicines.isEmpty()) {
                                stringResource(R.string.advisor_none_optional)
                            } else {
                                pluralStringResource(R.plurals.advisor_medicines_count, medicines.size, medicines.size)
                            },
                        )
                        GoalDivider()
                        CheckRow(
                            done = hasWorkouts,
                            title = stringResource(R.string.workouts_title),
                            detail = if (!hasWorkouts) {
                                stringResource(R.string.advisor_none_optional)
                            } else {
                                pluralStringResource(R.plurals.advisor_workouts_count, workouts.count { it.filled }, workouts.count { it.filled })
                            },
                        )
                    }
                    GoalMath.paceKgPerWeek(physique)?.takeIf { abs(it) > GoalMath.FAST_PACE_KG_WEEK }?.let { pace ->
                        Warning(stringResource(R.string.advisor_fast_pace, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", abs(pace))))
                    }
                    when (a) {
                        is Advice.Failed -> Warning(aiErrorMessage(a.error))
                        Advice.NeedsAi -> {
                            Warning(stringResource(R.string.advisor_needs_ai))
                            TextButton(onClick = onOpenAi) { Text(stringResource(R.string.advisor_set_up_ai)) }
                        }
                        else -> {}
                    }
                    Button(
                        onClick = { if (hasWorkouts) viewModel.analyse() else askWorkouts = true },
                        enabled = physique.complete,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.advisor_analyse), modifier = Modifier.padding(start = Spacing.sm))
                    }
                    if (!physique.complete) {
                        Text(
                            stringResource(R.string.advisor_physique_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (askWorkouts) {
        AlertDialog(
            onDismissRequest = { askWorkouts = false },
            title = { Text(stringResource(R.string.advisor_no_workouts_title)) },
            text = { Text(stringResource(R.string.advisor_no_workouts_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askWorkouts = false
                    onAddWorkouts()
                }) { Text(stringResource(R.string.advisor_no_workouts_yes)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askWorkouts = false
                    viewModel.analyse()
                }) { Text(stringResource(R.string.advisor_no_workouts_no)) }
            },
        )
    }
}

@Composable
internal fun conditionNames(c: dev.ytosko.neutrino.domain.goals.Conditions): String = listOfNotNull(
    if (c.diabetes) stringResource(R.string.conditions_diabetes) else null,
    if (c.bloodPressure) stringResource(R.string.conditions_bp) else null,
    if (c.thyroid) stringResource(R.string.conditions_thyroid) else null,
).joinToString(", ")

@Composable
private fun CheckRow(done: Boolean, title: String, detail: String, required: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val good = NeutrinoTheme.colors.good
        Box(
            modifier = Modifier
                .size(29.dp)
                .clip(CircleShape)
                .background(if (done) good else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (required) {
                    Text(
                        stringResource(R.string.advisor_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NeutrinoTheme.colors.amber.container)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = NeutrinoTheme.colors.amber.content, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = NeutrinoTheme.colors.amber.content)
    }
}

/** The suggestion: five values to edit, each with its reason, the notes, and Confirm intakes. */
@Composable
private fun AdviceResult(advice: GoalAdvice, diabetes: Boolean, onConfirm: (Intakes) -> Unit, onCancel: () -> Unit) {
    val i = advice.intakes
    var kcal by remember { mutableStateOf(i.kcal.toString()) }
    var carbs by remember { mutableStateOf(i.carbsG.toString()) }
    var protein by remember { mutableStateOf(i.proteinG.toString()) }
    var fat by remember { mutableStateOf(i.fatG.toString()) }
    var water by remember { mutableStateOf(i.waterMl.toString()) }
    val edited = Intakes(
        kcal = kcal.toIntOrNull() ?: 0,
        carbsG = carbs.toIntOrNull() ?: 0,
        proteinG = protein.toIntOrNull() ?: 0,
        fatG = fat.toIntOrNull() ?: 0,
        waterMl = water.toIntOrNull() ?: 0,
    )
    val valid = edited.kcal in 500..10_000 && edited.carbsG in 10..1_000 && edited.proteinG in 10..500 &&
        edited.fatG in 10..500 && edited.waterMl in 250..10_000

    Text(stringResource(R.string.advisor_result_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        ResultRow(R.drawable.ic_target, MaterialTheme.colorScheme.primary, stringResource(R.string.goals_calories), kcal, "kcal", advice.reasons[GoalAdvice.KCAL]) { kcal = it }
        GoalDivider()
        ResultRow(R.drawable.ic_wheat, NeutrinoTheme.colors.carbs, stringResource(R.string.macro_carbs), carbs, "g", advice.reasons[GoalAdvice.CARBS]) { carbs = it }
        GoalDivider()
        ResultRow(R.drawable.ic_drumstick, NeutrinoTheme.colors.protein, stringResource(R.string.macro_protein), protein, "g", advice.reasons[GoalAdvice.PROTEIN]) { protein = it }
        GoalDivider()
        ResultRow(R.drawable.ic_nut, NeutrinoTheme.colors.fat, stringResource(R.string.macro_fat), fat, "g", advice.reasons[GoalAdvice.FAT]) { fat = it }
        GoalDivider()
        ResultRow(R.drawable.ic_droplet, NeutrinoTheme.colors.water, stringResource(R.string.home_water), water, "ml", advice.reasons[GoalAdvice.WATER]) { water = it }
    }
    val notes = advice.notes + listOfNotNull(if (diabetes) stringResource(R.string.advisor_diabetes_note) else null)
    if (notes.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(NeutrinoTheme.colors.amber.container)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.advisor_notes), style = MaterialTheme.typography.labelLarge, color = NeutrinoTheme.colors.amber.content)
            notes.forEach { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", color = NeutrinoTheme.colors.amber.content)
                    Text(note, style = MaterialTheme.typography.bodySmall, color = NeutrinoTheme.colors.amber.content)
                }
            }
        }
    }
    Button(
        onClick = { onConfirm(edited) },
        enabled = valid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text(stringResource(R.string.advisor_confirm)) }
    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.advisor_keep_mine)) }
}

@Composable
private fun ResultRow(icon: Int, color: Color, title: String, value: String, unit: String, reason: String?, onValue: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ColorIcon(icon, color)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (reason != null) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { t -> onValue(t.filter(Char::isDigit).take(5)) },
            suffix = { Text(unit) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(color = color),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(118.dp),
        )
    }
}
