package dev.ytosko.neutrino.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.meal.DaySummary
import dev.ytosko.neutrino.data.meal.LoggedMeal
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.domain.roundGrams
import dev.ytosko.neutrino.domain.roundKcal
import dev.ytosko.neutrino.data.backup.BackupState
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.food.FoodIcon
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.ui.components.MacroStat
import dev.ytosko.neutrino.ui.components.NeutrinoLogo
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Meal groups in the order they happen in a day. */
private val MEAL_ORDER = listOf(MealType.Breakfast, MealType.Lunch, MealType.Snack, MealType.Dinner)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
    backup: BackupState?,
    onOpenBackup: () -> Unit,
    onPhotoSelected: (uri: Uri, fromCamera: Boolean) -> Unit,
    onAddManually: () -> Unit,
    savedResult: Boolean?,
    onSavedResultShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val day by viewModel.day.collectAsStateWithLifecycle()
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var mealToDelete by remember { mutableStateOf<LoggedMeal?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDate()
        onPauseOrDispose { }
    }

    val savedSynced = stringResource(R.string.home_saved_synced)
    val savedLocal = stringResource(R.string.home_saved_local)
    LaunchedEffect(savedResult) {
        if (savedResult != null) {
            onSavedResultShown()
            snackbar.showSnackbar(if (savedResult) savedSynced else savedLocal)
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCapture?.let(Uri::parse)
        pendingCapture = null
        if (success && uri != null) onPhotoSelected(uri, true)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhotoSelected(uri, false)
    }

    val noCamera = stringResource(R.string.home_no_camera)
    val aiNeeded = stringResource(R.string.home_ai_needed)
    val setUp = stringResource(R.string.home_set_up)
    val waterAdded = stringResource(R.string.home_water_added)

    fun startLogging() {
        showSheet = true
    }

    /** Photo analysis needs an AI provider; adding foods by hand does not. */
    fun withAi(action: () -> Unit) {
        showSheet = false
        if (aiReady) {
            action()
        } else {
            scope.launch {
                val result = snackbar.showSnackbar(aiNeeded, actionLabel = setUp, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) onOpenAiSettings()
            }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NeutrinoLogo(size = 32.dp)
                        Spacer(Modifier.size(Spacing.sm))
                        Column {
                            Text(
                                stringResource(R.string.home_title),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.home_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = ::startLogging,
                icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                text = { Text(stringResource(R.string.home_log_meal), style = MaterialTheme.typography.labelLarge) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val summary = day
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = padding.calculateTopPadding() + Spacing.xs,
                bottom = padding.calculateBottomPadding() + 96.dp, // keep clear of the FAB
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val itemModifier = Modifier.widthIn(max = 600.dp)
            if (backup != null && backup.needsAttention) {
                item(key = "backup") { BackupReminder(backup, onOpenBackup, itemModifier) }
            }
            item { DailyTotalsCard(summary, itemModifier) }
            item {
                WaterCard(
                    waterMl = summary?.waterMl ?: 0,
                    onAdd = {
                        viewModel.addWater()
                        scope.launch { snackbar.showSnackbar(waterAdded) }
                    },
                    modifier = itemModifier,
                )
            }
            if (summary != null && summary.meals.isEmpty()) {
                item { NextMealHint(MealWindows().nextMainMeal(LocalTime.now()), itemModifier) }
                item { EmptyMeals(itemModifier) }
            }
            summary?.meals?.groupBy { it.mealType }?.let { groups ->
                MEAL_ORDER.filter { it in groups }.forEach { type ->
                    item(key = "header-$type") {
                        Text(
                            mealTypeLabel(type),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = itemModifier.fillMaxWidth().padding(top = Spacing.xs).semantics { heading() },
                        )
                    }
                    items(groups.getValue(type), key = { it.id }) { meal ->
                        MealRow(meal, onDelete = { mealToDelete = meal }, modifier = itemModifier)
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(modifier = Modifier.navigationBarsPadding().padding(bottom = Spacing.lg)) {
                Text(
                    stringResource(R.string.home_log_meal_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                SheetOption(R.drawable.ic_camera, stringResource(R.string.home_take_photo)) {
                    withAi {
                        val uri = newCaptureUri(context)
                        pendingCapture = uri.toString()
                        try {
                            camera.launch(uri)
                        } catch (_: ActivityNotFoundException) {
                            scope.launch { snackbar.showSnackbar(noCamera) }
                        }
                    }
                }
                SheetOption(R.drawable.ic_image, stringResource(R.string.home_choose_photo)) {
                    withAi { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
                SheetOption(R.drawable.ic_search, stringResource(R.string.home_add_manually)) {
                    showSheet = false
                    onAddManually()
                }
            }
        }
    }

    mealToDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { mealToDelete = null },
            title = { Text(stringResource(R.string.home_delete_title)) },
            text = { Text(stringResource(R.string.home_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMeal(meal.id)
                    mealToDelete = null
                }) { Text(stringResource(R.string.home_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { mealToDelete = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

/** Nudges towards a backup until one exists, and flags backups that stopped working. */
@Composable
private fun BackupReminder(backup: BackupState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val problem = backup.configured
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val content = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            Icon(painterResource(if (problem) R.drawable.ic_circle_alert else R.drawable.ic_shield_check), contentDescription = null, tint = content)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.home_backup_title), style = MaterialTheme.typography.titleSmall, color = content)
                Text(
                    stringResource(if (problem) R.string.home_backup_problem else R.string.home_backup_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
    }
}

/** A fresh file in the app cache for the camera app to write into. */
private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "meal-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

@Composable
private fun SheetOption(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconBadge(icon = icon)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DailyTotalsCard(summary: DaySummary?, modifier: Modifier = Modifier) {
    val colors = NeutrinoTheme.colors
    val totals = summary?.totals
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            MacroStat(grams(totals?.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f))
            MacroStat(grams(totals?.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f))
            MacroStat(grams(totals?.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f))
            MacroStat("${(totals?.calories ?: 0.0).roundKcal()}", stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        }
    }
}

private fun grams(value: Double?): String = "${(value ?: 0.0).roundGrams().let { if (it >= 100) it.toInt().toString() else it.toString().removeSuffix(".0") }}g"

@Composable
private fun WaterCard(waterMl: Int, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NeutrinoTheme.colors
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IconBadge(R.drawable.ic_droplet, container = colors.waterContainer, content = colors.water)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.home_water), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.home_water_amount, waterMl),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_add_glass))
            }
        }
    }
}

@Composable
private fun MealRow(meal: LoggedMeal, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NeutrinoTheme.colors
    val time = remember(meal.eatenAt) {
        meal.eatenAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Thumbnail(meal.thumbnailPath, meal.category)
            Column(modifier = Modifier.weight(1f)) {
                Text(meal.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.home_meal_line, time, meal.nutrition.calories.roundKcal()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = 2.dp)) {
                    MacroText("C", meal.nutrition.carbsG, colors.carbs)
                    MacroText("P", meal.nutrition.proteinG, colors.protein)
                    MacroText("F", meal.nutrition.fatG, colors.fat)
                    if (!meal.syncedToHealthConnect) {
                        Text(stringResource(R.string.home_not_synced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.home_delete_meal),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MacroText(letter: String, grams: Double, color: androidx.compose.ui.graphics.Color) {
    Text("$letter ${grams.roundGrams().toString().removeSuffix(".0")}g", style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun Thumbnail(path: String?, category: FoodCategory?) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    if (path == null && category != null) {
        FoodIcon(category, size = 56.dp)
        return
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_utensils), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun NextMealHint(meal: MealType, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(painterResource(R.drawable.ic_clock), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.home_next_meal, mealTypeLabel(meal)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyMeals(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xl, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = R.drawable.ic_utensils, size = 64.dp)
        Spacer(Modifier.height(Spacing.md))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
