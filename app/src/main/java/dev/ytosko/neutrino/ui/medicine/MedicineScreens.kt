package dev.ytosko.neutrino.ui.medicine

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.medicine.DoseEntity
import dev.ytosko.neutrino.data.medicine.DoseUnit
import dev.ytosko.neutrino.data.medicine.InsulinType
import dev.ytosko.neutrino.data.medicine.MedexSuggestion
import dev.ytosko.neutrino.data.medicine.MedicineEntity
import dev.ytosko.neutrino.data.medicine.MedicineKind
import dev.ytosko.neutrino.data.medicine.MedicineRepository
import dev.ytosko.neutrino.data.reminders.plural
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.IosAlert
import dev.ytosko.neutrino.ui.components.SegmentedControl
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import dev.ytosko.neutrino.ui.theme.Tint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** MedEx suggestions while typing a name: nothing yet, loading, results, or unreachable. */
data class SuggestState(val loading: Boolean = false, val results: List<MedexSuggestion> = emptyList(), val offline: Boolean = false)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MedicinesViewModel(private val repository: MedicineRepository, settings: SettingsRepository) : ViewModel() {

    val medicines: StateFlow<List<MedicineEntity>> =
        repository.medicines.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val query = MutableStateFlow("")

    /** Asks MedEx once typing pauses and at least 3 letters are in. */
    val suggestions: StateFlow<SuggestState> = query
        .debounce(350)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.trim().length < 3) {
                    emit(SuggestState())
                    return@flow
                }
                emit(SuggestState(loading = true))
                emit(
                    runCatching { SuggestState(results = repository.medex.search(q)) }
                        .getOrElse { SuggestState(offline = true) },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SuggestState())

    fun search(text: String) {
        query.value = text
    }

    suspend fun genericOf(suggestion: MedexSuggestion): String? = runCatching { repository.medex.generic(suggestion) }.getOrNull()

    fun save(medicine: MedicineEntity) {
        viewModelScope.launch { repository.save(medicine) }
    }

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }
}

/** The kinds the user turned on in Settings, medicines first. */
fun AppSettings.medicineKinds(): List<MedicineKind> = buildList {
    if (takesMedicine) add(MedicineKind.Medicine)
    if (usesInsulin) add(MedicineKind.Insulin)
}

/** "Medicines and insulin", "Medicines" or "Insulin", for what's turned on. */
fun dosesTitle(settings: AppSettings): Int = when {
    settings.takesMedicine && settings.usesInsulin -> R.string.doses_title_both
    settings.usesInsulin -> R.string.doses_title_insulin
    else -> R.string.doses_title_medicine
}

@Composable
fun doseAmountText(amount: Double, unit: DoseUnit): String {
    val number = if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(Locale.US, "%.1f", amount).removeSuffix(".0")
    return pluralStringResource(unit.plural, if (amount == 1.0) 1 else 2, number)
}

@Composable
fun unitLabel(unit: DoseUnit): String = stringResource(
    when (unit) {
        DoseUnit.Tablet -> R.string.dose_unit_label_tablet
        DoseUnit.Capsule -> R.string.dose_unit_label_capsule
        DoseUnit.Ml -> R.string.dose_unit_label_ml
        DoseUnit.Mg -> R.string.dose_unit_label_mg
        DoseUnit.Units -> R.string.dose_unit_label_units
        DoseUnit.Puff -> R.string.dose_unit_label_puff
        DoseUnit.Drop -> R.string.dose_unit_label_drop
    },
)

@Composable
fun insulinLabel(type: InsulinType): String = stringResource(
    when (type) {
        InsulinType.Rapid -> R.string.insulin_rapid
        InsulinType.Short -> R.string.insulin_short
        InsulinType.Intermediate -> R.string.insulin_intermediate
        InsulinType.Long -> R.string.insulin_long
        InsulinType.Mixed -> R.string.insulin_mixed
    },
)

@Composable
fun kindTint(kind: MedicineKind): Tint = if (kind == MedicineKind.Insulin) NeutrinoTheme.colors.cyan else NeutrinoTheme.colors.violet

fun kindIcon(kind: MedicineKind): Int = if (kind == MedicineKind.Insulin) R.drawable.ic_syringe else R.drawable.ic_pill

private val shortTime: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Reminder shortcuts: morning, afternoon, evening and night at everyday times. */
private val REMINDER_PRESETS = listOf(
    R.string.medicine_time_morning to LocalTime.of(8, 0),
    R.string.medicine_time_afternoon to LocalTime.of(14, 0),
    R.string.medicine_time_evening to LocalTime.of(20, 0),
    R.string.medicine_time_night to LocalTime.of(22, 0),
)

/** Settings → My medicines: the list, grouped into insulin and medicines, with + to add. */
@Composable
fun MedicinesScreen(viewModel: MedicinesViewModel, onBack: () -> Unit) {
    val medicines by viewModel.medicines.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val kinds = settings?.medicineKinds().orEmpty()
    var editing by remember { mutableStateOf<MedicineEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    SetupScaffold(
        title = stringResource(R.string.medicines_title),
        onBack = onBack,
        actions = {
            if (kinds.isNotEmpty()) {
                IconButton(onClick = { adding = true }) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.medicines_add))
                }
            }
        },
    ) {
        val shown = medicines.filter { it.kindEnum in kinds }
        if (shown.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IconBadge(R.drawable.ic_pill, container = NeutrinoTheme.colors.violet.container, content = NeutrinoTheme.colors.violet.content, size = 64.dp)
                Text(stringResource(R.string.medicines_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.medicines_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (kinds.isNotEmpty()) {
                    Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.medicines_add)) }
                }
            }
        }
        kinds.forEach { kind ->
            val group = shown.filter { it.kindEnum == kind }
            if (group.isEmpty()) return@forEach
            Text(
                stringResource(if (kind == MedicineKind.Insulin) R.string.medicines_section_insulin else R.string.medicines_section_medicine).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.xs, top = Spacing.md, bottom = Spacing.xs),
            )
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                group.forEachIndexed { index, medicine ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = Spacing.md + 40.dp + Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                    MedicineRow(medicine) { editing = medicine }
                }
            }
        }
        Text(
            stringResource(R.string.medicine_not_advice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }

    if (adding || editing != null) {
        MedicineEditorSheet(
            viewModel = viewModel,
            existing = editing,
            kinds = kinds,
            onDismiss = { adding = false; editing = null },
        )
    }
}

@Composable
private fun MedicineRow(medicine: MedicineEntity, onClick: () -> Unit) {
    val tint = kindTint(medicine.kindEnum)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconBadge(kindIcon(medicine.kindEnum), container = tint.container, content = tint.content, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(medicine.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                medicine.generic?.takeIf { it.isNotBlank() },
                medicine.insulinTypeEnum?.let { insulinLabel(it) },
                medicine.usualDose?.let { doseAmountText(it, medicine.unitEnum) },
                medicine.reminders.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.format(shortTime) },
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

/** A guess at the unit from MedEx's form, e.g. "Syrup" → ml. */
private fun unitFor(form: String?, kind: MedicineKind): DoseUnit {
    if (kind == MedicineKind.Insulin) return DoseUnit.Units
    val f = form?.lowercase().orEmpty()
    return when {
        "capsule" in f -> DoseUnit.Capsule
        "drop" in f -> DoseUnit.Drop
        "inhaler" in f || "puff" in f -> DoseUnit.Puff
        "syrup" in f || "suspension" in f || "solution" in f || "liquid" in f -> DoseUnit.Ml
        "injection" in f || "infusion" in f -> DoseUnit.Ml
        else -> DoseUnit.Tablet
    }
}

private fun parseAmount(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 10_000 }

private fun amountText(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> value.toString()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MedicineEditorSheet(viewModel: MedicinesViewModel, existing: MedicineEntity?, kinds: List<MedicineKind>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    var kind by remember { mutableStateOf(existing?.kindEnum ?: kinds.first()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var generic by remember { mutableStateOf(existing?.generic.orEmpty()) }
    var strength by remember { mutableStateOf(existing?.strength.orEmpty()) }
    var form by remember { mutableStateOf(existing?.form.orEmpty()) }
    var insulinType by remember { mutableStateOf(existing?.insulinTypeEnum) }
    var unit by remember { mutableStateOf(existing?.unitEnum ?: unitFor(null, kind)) }
    var dose by remember { mutableStateOf(amountText(existing?.usualDose)) }
    var times by remember { mutableStateOf(existing?.reminders.orEmpty()) }
    var picked by remember { mutableStateOf(existing != null) }
    var addingTime by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.search("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                stringResource(
                    when {
                        existing != null -> R.string.medicine_edit_title
                        kind == MedicineKind.Insulin -> R.string.medicine_add_insulin_title
                        else -> R.string.medicine_add_title
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (existing == null && kinds.size > 1) {
                SegmentedControl(
                    options = kinds.map { stringResource(if (it == MedicineKind.Insulin) R.string.medicine_kind_insulin else R.string.medicine_kind_medicine) },
                    selected = kinds.indexOf(kind),
                    onSelect = {
                        kind = kinds[it]
                        unit = unitFor(form, kind)
                    },
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    picked = false
                    viewModel.search(it)
                },
                label = { Text(stringResource(R.string.medicine_name)) },
                placeholder = { Text(stringResource(R.string.medicine_name_hint)) },
                singleLine = true,
                trailingIcon = if (suggestions.loading) {
                    { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!picked && suggestions.results.isNotEmpty()) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    suggestions.results.forEachIndexed { index, s ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    name = s.name
                                    strength = s.strength.orEmpty()
                                    form = s.form.orEmpty()
                                    unit = unitFor(s.form, kind)
                                    picked = true
                                    viewModel.search("")
                                    scope.launch { viewModel.genericOf(s)?.let { g -> if (generic.isBlank()) generic = g } }
                                }
                                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(listOfNotNull(s.name, s.strength).joinToString(" "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            s.form?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            Text(
                stringResource(if (suggestions.offline && !picked) R.string.medicine_medex_offline else R.string.medicine_medex_note),
                style = MaterialTheme.typography.bodySmall,
                color = if (suggestions.offline && !picked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = generic,
                onValueChange = { generic = it },
                label = { Text(stringResource(R.string.medicine_group)) },
                placeholder = { Text(stringResource(R.string.medicine_group_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = strength,
                    onValueChange = { strength = it },
                    label = { Text(stringResource(R.string.medicine_strength)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form,
                    onValueChange = { form = it },
                    label = { Text(stringResource(R.string.medicine_form)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            if (kind == MedicineKind.Insulin) {
                Text(stringResource(R.string.medicine_insulin_type), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    InsulinType.entries.forEach { type ->
                        FilterChip(selected = insulinType == type, onClick = { insulinType = if (insulinType == type) null else type }, label = { Text(insulinLabel(type)) })
                    }
                }
            }
            Text(stringResource(R.string.medicine_usual_dose), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    placeholder = { Text(stringResource(R.string.medicine_usual_dose_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val options = if (kind == MedicineKind.Insulin) listOf(DoseUnit.Units, DoseUnit.Ml) else DoseUnit.entries.filter { it != DoseUnit.Units }
                options.forEach { option ->
                    FilterChip(selected = unit == option, onClick = { unit = option }, label = { Text(unitLabel(option)) })
                }
            }
            Text(stringResource(R.string.medicine_reminders), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
            Text(stringResource(R.string.medicine_reminders_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // One tap for the usual times of day; any other time with the picker.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                REMINDER_PRESETS.forEach { (label, time) ->
                    val on = time in times
                    FilterChip(
                        selected = on,
                        onClick = { times = if (on) times - time else (times + time).sorted() },
                        label = { Text("${stringResource(label)} · ${time.format(shortTime)}") },
                    )
                }
                times.filter { t -> REMINDER_PRESETS.none { it.second == t } }.forEach { time ->
                    val label = time.format(shortTime)
                    val removeLabel = stringResource(R.string.medicine_remove_time, label)
                    InputChip(
                        selected = true,
                        onClick = { times = times - time },
                        label = { Text(label) },
                        trailingIcon = { Icon(painterResource(R.drawable.ic_x), contentDescription = removeLabel, modifier = Modifier.size(16.dp)) },
                    )
                }
                TextButton(onClick = { addingTime = true }) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.medicine_time_other))
                }
            }
            Button(
                onClick = {
                    val base = existing ?: MedicineEntity(id = "", name = "")
                    viewModel.save(
                        base.copy(
                            name = name.trim(),
                            generic = generic.trim().takeIf { it.isNotEmpty() },
                            strength = strength.trim().takeIf { it.isNotEmpty() },
                            form = form.trim().takeIf { it.isNotEmpty() },
                            kind = kind.name,
                            insulinType = if (kind == MedicineKind.Insulin) insulinType?.name else null,
                            usualDose = parseAmount(dose),
                            doseUnit = unit.name,
                            reminderTimes = times.sorted().joinToString(",") { it.format(DateTimeFormatter.ofPattern("HH:mm")) },
                        ),
                    )
                    onDismiss()
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = Spacing.sm),
            ) { Text(stringResource(R.string.medicine_save)) }
            if (existing != null) {
                OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.medicine_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // Inside the sheet, so they open on top of it.
        if (addingTime) {
            TimeDialog(LocalTime.of(12, 0), onDismiss = { addingTime = false }) { time ->
                if (time !in times) times = (times + time).sorted()
                addingTime = false
            }
        }
        if (confirmRemove && existing != null) {
            IosAlert(
                title = stringResource(R.string.medicine_remove_title, existing.displayName),
                message = stringResource(R.string.medicine_remove_body),
                buttons = listOf(
                    AlertButton(stringResource(R.string.home_cancel), AlertStyle.Cancel) { confirmRemove = false },
                    AlertButton(stringResource(R.string.medicine_remove), AlertStyle.Destructive) {
                        confirmRemove = false
                        viewModel.remove(existing.id)
                        onDismiss()
                    },
                ),
                onDismiss = { confirmRemove = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.review_time_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) } },
    )
}

/**
 * Log a dose (or edit one): which medicine, how much (the usual dose to start with), and when.
 * [date] is the day on screen; a dose on a past day starts at the current time of day.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DoseSheet(
    medicines: List<MedicineEntity>,
    dose: DoseEntity?,
    date: LocalDate,
    onSave: (medicine: MedicineEntity?, amount: Double, at: Instant) -> Unit,
    onDelete: () -> Unit,
    onAddMedicines: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var selected by remember { mutableStateOf(dose?.let { d -> medicines.firstOrNull { it.id == d.medicineId } } ?: medicines.firstOrNull()) }
    var amount by remember { mutableStateOf(amountText(dose?.amount ?: selected?.usualDose)) }
    var at by remember {
        mutableStateOf(
            dose?.let { Instant.ofEpochMilli(it.takenAtEpochMs).atZone(zone) }
                ?: ZonedDateTime.of(date, LocalTime.now(zone).withSecond(0).withNano(0), zone),
        )
    }
    var pickTime by remember { mutableStateOf(false) }
    val unit = dose?.unitEnum ?: selected?.unitEnum ?: DoseUnit.Tablet

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(if (dose == null) R.string.dose_log_title else R.string.dose_edit_title), style = MaterialTheme.typography.titleLarge)
            if (dose == null && medicines.isEmpty()) {
                Text(stringResource(R.string.dose_no_medicines), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAddMedicines, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.dose_add_medicines)) }
                return@Column
            }
            if (dose != null && selected == null) {
                Text(dose.medicineName, style = MaterialTheme.typography.titleMedium)
            } else {
                Text(stringResource(R.string.dose_which), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    medicines.forEach { m ->
                        FilterChip(
                            selected = selected?.id == m.id,
                            onClick = {
                                selected = m
                                if (dose == null) amount = amountText(m.usualDose)
                            },
                            label = { Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(painterResource(kindIcon(m.kindEnum)), contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                    label = { Text(stringResource(R.string.dose_amount)) },
                    suffix = { Text(unitLabel(unit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Icon(painterResource(R.drawable.ic_clock), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(at.format(shortTime))
                }
            }
            val value = parseAmount(amount)
            Button(
                onClick = { if (value != null) onSave(selected, value, at.toInstant()) },
                enabled = value != null && (selected != null || dose != null) && !at.isAfter(ZonedDateTime.now(zone).plusMinutes(1)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.dose_save)) }
            if (dose != null) {
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.dose_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Text(stringResource(R.string.medicine_not_advice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pickTime) {
            TimeDialog(at.toLocalTime(), onDismiss = { pickTime = false }) { time ->
                at = ZonedDateTime.of(at.toLocalDate(), time, zone)
                pickTime = false
            }
        }
    }
}

/** The day's doses on Home: newest last, tap one to change it, + to log another. */
@Composable
fun DosesDayCard(
    doses: List<DoseEntity>,
    title: String,
    isToday: Boolean,
    onAdd: () -> Unit,
    onOpen: (DoseEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = NeutrinoTheme.colors.violet
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconBadge(R.drawable.ic_pill, container = tint.container, content = tint.content)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (doses.isEmpty()) {
                        stringResource(if (isToday) R.string.doses_none_today else R.string.doses_none_day)
                    } else {
                        pluralStringResource(R.plurals.doses_count, doses.size, doses.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.home_log_dose))
            }
        }
        doses.forEachIndexed { index, dose ->
            if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
            val time = Instant.ofEpochMilli(dose.takenAtEpochMs).atZone(ZoneId.systemDefault()).format(shortTime)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { onOpen(dose) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val kindTint = kindTint(dose.kindEnum)
                Icon(painterResource(kindIcon(dose.kindEnum)), contentDescription = null, tint = kindTint.content, modifier = Modifier.size(18.dp))
                Text(dose.medicineName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(doseAmountText(dose.amount, dose.unitEnum), style = MaterialTheme.typography.labelLarge)
                Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (doses.isNotEmpty()) Spacer(Modifier.size(Spacing.xs))
    }
}
