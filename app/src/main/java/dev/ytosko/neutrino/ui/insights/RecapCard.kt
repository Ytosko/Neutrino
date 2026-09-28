package dev.ytosko.neutrino.ui.insights

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiChain
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.insights.GlucoseWeek
import dev.ytosko.neutrino.domain.insights.WeeklyRecap
import dev.ytosko.neutrino.ui.ai.aiErrorMessage
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class RecapUiState(
    val writing: Boolean = false,
    val error: AiException? = null,
    val empty: Boolean = false,
    val needsAi: Boolean = false,
)

/**
 * "Your week in words": the last 7 days as plain facts (see [WeeklyRecap]), put into a few friendly
 * sentences by the user's AI. Facts only, never advice. Glucose goes along only when the user ticks
 * it. The text is kept on the phone until rewritten.
 */
class RecapViewModel(
    private val meals: MealRepository,
    private val glucose: GlucoseRepository,
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow(RecapUiState())
    val state: StateFlow<RecapUiState> = _state.asStateFlow()

    val saved = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setIncludeGlucose(on: Boolean) {
        viewModelScope.launch { settings.setRecapGlucose(on) }
    }

    fun write() {
        if (_state.value.writing) return
        viewModelScope.launch {
            val current = settings.settings.first()
            val chain = settings.aiChain()
            if (chain.isEmpty()) {
                _state.update { it.copy(needsAi = true) }
                return@launch
            }
            _state.update { RecapUiState(writing = true) }
            val to = LocalDate.now(zone)
            val from = to.minusDays(6)
            val range = meals.observeRange(from, to, zone).first()
            val glucoseWeek = if (current.recapGlucose) glucoseWeek(from, to, current.glucoseLow, current.glucoseHigh, current.glucoseUnit) else null
            val facts = WeeklyRecap.facts(from, to, range.meals, range.water, range.topFoods, current.kcalGoal, current.waterGoalMl, glucoseWeek)
            if (facts.isEmpty()) {
                _state.update { RecapUiState(empty = true) }
                return@launch
            }
            val bangla = java.util.Locale.getDefault().language == "bn"
            try {
                val (_, text) = AiChain.run(chain, clients) { client, key, config ->
                    val reply = client.generateJson(key, config.model, AnalysisPrompt.weeklyRecap(facts, bangla), AnalysisPrompt.RECAP_SCHEMA)
                    AnalysisPrompt.parseRecap(reply.text) ?: throw AiException.NoResult()
                } ?: return@launch
                settings.saveRecap(text, to)
                _state.update { RecapUiState() }
            } catch (e: AiException) {
                _state.update { RecapUiState(error = e) }
            }
        }
    }

    private suspend fun glucoseWeek(from: LocalDate, to: LocalDate, low: Double, high: Double, unit: dev.ytosko.neutrino.domain.GlucoseUnit): GlucoseWeek? {
        val readings = glucose.observeBetween(from, to, zone).first()
        if (readings.isEmpty()) return null
        val average = readings.sumOf { it.mmolPerL } / readings.size
        val inRange = readings.count { it.mmolPerL in low..high } * 100 / readings.size
        return GlucoseWeek(unit.format(average), unit.label, inRange, readings.size)
    }
}

@Composable
fun RecapCard(viewModel: RecapViewModel, online: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.saved.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val offline = stringResource(R.string.offline_needs_internet_message)
    val text = settings?.recapText.orEmpty()
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.recap_title), style = MaterialTheme.typography.titleMedium)
            }
            if (text.isNotBlank()) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                settings?.recapDate?.let { date ->
                    Text(
                        stringResource(R.string.recap_written, date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(stringResource(R.string.recap_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let { Text(aiErrorMessage(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (state.empty) Text(stringResource(R.string.recap_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (state.needsAi) Text(stringResource(R.string.food_custom_needs_ai), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(value = settings?.recapGlucose == true, role = Role.Checkbox, onValueChange = viewModel::setIncludeGlucose),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = settings?.recapGlucose == true, onCheckedChange = null)
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.recap_include_glucose), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = { if (online) viewModel.write() else Toast.makeText(context, offline, Toast.LENGTH_LONG).show() },
                enabled = !state.writing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (state.writing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.recap_writing))
                } else {
                    Text(stringResource(if (text.isBlank()) R.string.recap_write else R.string.recap_rewrite))
                }
            }
        }
    }
}

