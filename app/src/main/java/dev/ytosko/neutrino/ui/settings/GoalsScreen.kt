package dev.ytosko.neutrino.ui.settings

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.insights.formatWater
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Settings → Daily goals: optional carbs, calories and water targets shown on each day. */
@Composable
fun GoalsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val s by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val colors = NeutrinoTheme.colors
    fun save(
        carbs: Int? = s.carbGoalG,
        protein: Int? = s.proteinGoalG,
        fat: Int? = s.fatGoalG,
        kcal: Int? = s.kcalGoal,
        water: Int? = s.waterGoalMl,
    ) = scope.launch { settings.setGoals(carbs, protein, fat, kcal, water) }

    SetupScaffold(
        title = stringResource(R.string.goals_title),
        subtitle = stringResource(R.string.goals_body),
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GoalCard(
                title = stringResource(R.string.macro_carbs),
                value = s.carbGoalG,
                text = { "$it g" },
                color = colors.carbs,
                default = 150, step = 10, range = 10..1_000,
                onChange = { save(carbs = it) },
            )
            GoalCard(
                title = stringResource(R.string.macro_protein),
                value = s.proteinGoalG,
                text = { "$it g" },
                color = colors.protein,
                default = 60, step = 5, range = 10..500,
                onChange = { save(protein = it) },
            )
            GoalCard(
                title = stringResource(R.string.macro_fat),
                value = s.fatGoalG,
                text = { "$it g" },
                color = colors.fat,
                default = 60, step = 5, range = 10..500,
                onChange = { save(fat = it) },
            )
            GoalCard(
                title = stringResource(R.string.goals_calories),
                value = s.kcalGoal,
                text = { "$it kcal" },
                color = MaterialTheme.colorScheme.onSurface,
                default = 2_000, step = 50, range = 500..10_000,
                onChange = { save(kcal = it) },
            )
            GoalCard(
                title = stringResource(R.string.home_water),
                value = s.waterGoalMl,
                text = { formatWater(it) },
                color = colors.water,
                default = 2_000, step = 250, range = 250..10_000,
                onChange = { save(water = it) },
            )
            Text(
                stringResource(R.string.goals_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun GoalCard(
    title: String,
    value: Int?,
    text: (Int) -> String,
    color: Color,
    default: Int,
    step: Int,
    range: IntRange,
    onChange: (Int?) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .toggleable(value = value != null, role = Role.Switch) { on ->
                        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        onChange(if (on) default else null)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = value != null, onCheckedChange = null)
            }
            if (value != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.goals_per_day), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value - step >= range.first) {
                        Icon(painterResource(R.drawable.ic_minus), contentDescription = stringResource(R.string.review_less))
                    }
                    Text(
                        text(value),
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 88.dp),
                    )
                    FilledTonalIconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value + step <= range.last) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.review_more))
                    }
                }
            }
        }
    }
}
