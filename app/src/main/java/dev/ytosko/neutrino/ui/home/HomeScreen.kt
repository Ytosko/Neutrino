package dev.ytosko.neutrino.ui.home

import dev.ytosko.neutrino.domain.insights.compactNumber
import dev.ytosko.neutrino.domain.insights.formatWater
import dev.ytosko.neutrino.ui.components.mealTypeIcon
import dev.ytosko.neutrino.ui.components.mealTypeColors
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import android.os.Build
import android.Manifest
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import dev.ytosko.neutrino.ui.insights.HealthViewModel
import dev.ytosko.neutrino.ui.insights.HealthContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.activity.compose.BackHandler
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
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
import dev.ytosko.neutrino.ui.components.TotalsCard
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
    healthViewModel: HealthViewModel,
    onOpenSettings: () -> Unit,
    onOpenAiSettings: () -> Unit,
    backup: BackupState?,
    onOpenBackup: () -> Unit,
    onPhotoSelected: (uri: Uri, fromCamera: Boolean) -> Unit,
    onAddManually: () -> Unit,
    onOpenMeal: (id: String) -> Unit,
    savedResult: Boolean?,
    onSavedResultShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val day by viewModel.day.collectAsStateWithLifecycle()
    val shownDate by viewModel.date.collectAsStateWithLifecycle()
    val isToday by viewModel.isToday.collectAsStateWithLifecycle()
    val mealWindows by viewModel.mealWindows.collectAsStateWithLifecycle()
    var pickingDate by remember { mutableStateOf(false) }
    val aiReady by viewModel.aiReady.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Days) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDate()
        healthViewModel.refreshDate()
        onPauseOrDispose { }
    }
    // Back from Health returns to the day view before leaving the app.
    BackHandler(enabled = tab == HomeTab.Health) { tab = HomeTab.Days }

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
    val askForNotifications by viewModel.askForNotifications.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(askForNotifications) {
        if (askForNotifications) {
            viewModel.notificationsAsked()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onPhotoSelected(uri, false)
    }

    val noCamera = stringResource(R.string.home_no_camera)
    val aiNeeded = stringResource(R.string.home_ai_needed)
    val setUp = stringResource(R.string.home_set_up)
    val waterAdded = stringResource(R.string.home_water_added)
    val mealDeleted = stringResource(R.string.home_meal_deleted)
    val undo = stringResource(R.string.home_undo)

    /** Deletes at once, with Undo in the snackbar instead of a confirmation dialog. */
    fun deleteWithUndo(meal: LoggedMeal) {
        scope.launch {
            val stored = viewModel.deleteMeal(meal.id) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(mealDeleted, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(stored)
        }
    }

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
            if (tab == HomeTab.Health) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.health_title),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                    actions = {
                        IconButton(onClick = { tab = HomeTab.Days }) {
                            Icon(painterResource(R.drawable.ic_calendar_days), contentDescription = stringResource(R.string.nav_open_days))
                        }
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
                return@Scaffold
            }
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Tap the day to jump to any date; the arrows step one day at a time.
                        Column(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(
                                    onClickLabel = stringResource(R.string.home_pick_date),
                                    role = Role.Button,
                                    onClick = { pickingDate = true },
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    dayTitle(shownDate),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    modifier = Modifier.semantics { heading() },
                                )
                                Icon(
                                    painterResource(R.drawable.ic_chevron_down),
                                    contentDescription = null,
                                    modifier = Modifier.padding(start = 2.dp).size(18.dp),
                                )
                            }
                            Text(
                                shownDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::previousDay) {
                        Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.home_previous_day))
                    }
                    IconButton(onClick = viewModel::nextDay, enabled = !isToday) {
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.home_next_day))
                    }
                    if (isToday) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.home_settings))
                        }
                    } else {
                        IconButton(onClick = viewModel::showToday) {
                            Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.home_back_to_today))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        // Days only: Health on the left, Log a meal on the right. Health goes back via its top bar.
        floatingActionButton = {
            if (tab == HomeTab.Health) return@Scaffold
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingActionButton(
                    onClick = { tab = HomeTab.Health },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(painterResource(R.drawable.ic_heart), contentDescription = stringResource(R.string.nav_open_health))
                }
                ExtendedFloatingActionButton(
                    onClick = ::startLogging,
                    icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                    text = { Text(stringResource(R.string.home_log_meal), style = MaterialTheme.typography.labelLarge) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Crossfade(targetState = tab, animationSpec = tween(220), label = "tab") { shownTab ->
        if (shownTab == HomeTab.Health) {
            HealthContent(
                viewModel = healthViewModel,
                contentPadding = padding,
                onOpenDay = { date ->
                    viewModel.showDate(date)
                    tab = HomeTab.Days
                },
            )
            return@Crossfade
        }
        // Wait for the new day's data before sliding, so the old day's numbers never show under the new title.
        AnimatedContent(
            targetState = day,
            contentKey = { it?.date },
            transitionSpec = {
                val forward = (targetState?.date ?: shownDate) > (initialState?.date ?: shownDate)
                val direction = if (forward) 1 else -1
                (slideInHorizontally(tween(260)) { it / 5 * direction } + fadeIn(tween(260))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 5 * direction } + fadeOut(tween(180)))
            },
            label = "day",
        ) { summary ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.gutter,
                end = Spacing.gutter,
                top = padding.calculateTopPadding() + Spacing.xs,
                bottom = padding.calculateBottomPadding() + 96.dp, // keep clear of the buttons
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val itemModifier = Modifier.widthIn(max = 600.dp)
            if (backup != null && backup.needsAttention) {
                item(key = "backup") { BackupReminder(backup, onOpenBackup, itemModifier) }
            }
            item { TotalsCard(summary?.totals, itemModifier) }
            item {
                WaterCard(
                    waterMl = summary?.waterMl ?: 0,
                    isToday = isToday,
                    onRemove = viewModel::removeLastWater,
                    onAdd = {
                        viewModel.addWater()
                        scope.launch { snackbar.showSnackbar(waterAdded) }
                    },
                    modifier = itemModifier,
                )
            }
            if (summary != null && summary.meals.isEmpty()) {
                if (isToday) item { NextMealHint(mealWindows.nextMainMeal(LocalTime.now()), itemModifier) }
                item { EmptyMeals(isToday, itemModifier) }
            }
            summary?.meals?.groupBy { it.mealType }?.let { groups ->
                MEAL_ORDER.filter { it in groups }.forEach { type ->
                    val meals = groups.getValue(type)
                    item(key = "header-$type") {
                        MealGroupHeader(type, meals.sumOf { it.nutrition.calories }, itemModifier.animateItem())
                    }
                    items(meals, key = { it.id }) { meal ->
                        MealRow(
                            meal,
                            onOpen = { onOpenMeal(meal.id) },
                            onDelete = { deleteWithUndo(meal) },
                            modifier = itemModifier.animateItem(),
                        )
                    }
                }
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

    if (pickingDate) {
        DayPickerDialog(
            selected = shownDate,
            onPick = {
                viewModel.showDate(it)
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
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
private fun WaterCard(waterMl: Int, isToday: Boolean, onRemove: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
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
                    stringResource(if (isToday) R.string.home_water_amount else R.string.home_water_amount_day, formatWater(waterMl)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (waterMl > 0) {
                IconButton(onClick = onRemove) {
                    Icon(
                        painterResource(R.drawable.ic_minus),
                        contentDescription = stringResource(R.string.home_remove_glass),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(
                onClick = onAdd,
                enabled = waterMl + HomeViewModel.GLASS_ML <= HomeViewModel.MAX_WATER_ML,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.home_add_glass), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MealRow(meal: LoggedMeal, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NeutrinoTheme.colors
    val time = remember(meal.eatenAt) {
        meal.eatenAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
    Card(
        onClick = onOpen,
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
private fun EmptyMeals(isToday: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xl, horizontal = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon = R.drawable.ic_utensils, size = 64.dp)
        Spacer(Modifier.height(Spacing.md))
        Text(
            stringResource(if (isToday) R.string.home_empty_title else R.string.home_empty_day_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(if (isToday) R.string.home_empty_body else R.string.home_empty_day_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** "Today", "Yesterday", the weekday for the last week, then the date. */
@Composable
private fun dayTitle(date: LocalDate): String {
    val daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now())
    return when {
        daysAgo <= 0L -> stringResource(R.string.home_title)
        daysAgo == 1L -> stringResource(R.string.home_yesterday)
        daysAgo < 7L -> date.format(DateTimeFormatter.ofPattern("EEEE"))
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

/** Calendar to jump to any past day (future days can't be picked). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(selected: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayUtcMs = remember { LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMs
            override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) else onDismiss()
            }) { Text(stringResource(R.string.home_show_day)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

enum class HomeTab { Days, Health }


/** "🌅 Breakfast ········ 520 kcal": the meal's icon, name and subtotal. */
@Composable
private fun MealGroupHeader(type: MealType, kcal: Double, modifier: Modifier = Modifier) {
    val (container, content) = mealTypeColors(type)
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.xs).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        IconBadge(mealTypeIcon(type), container = container, content = content, size = 28.dp)
        Text(mealTypeLabel(type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            "${compactNumber(kcal)} ${stringResource(R.string.macro_energy)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
