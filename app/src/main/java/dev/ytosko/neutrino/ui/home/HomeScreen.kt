package dev.ytosko.neutrino.ui.home

import dev.ytosko.neutrino.ui.glucose.GlucoseRow
import dev.ytosko.neutrino.ui.components.MenuAction
import dev.ytosko.neutrino.ui.components.LiftedContextMenu
import dev.ytosko.neutrino.ui.components.PlateIllustration
import dev.ytosko.neutrino.ui.components.WaterGlass
import dev.ytosko.neutrino.ui.components.pressScale
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import dev.ytosko.neutrino.ui.components.NeutrinoSnackbarHost
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.IosAlert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.ButtonDefaults
import dev.ytosko.neutrino.ui.theme.Tint
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
import dev.ytosko.neutrino.ui.glucose.LocalGlucoseUnit
import dev.ytosko.neutrino.domain.insights.MealGlucose
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.widget.LaunchAction
import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.ui.glucose.GlucoseDayCard
import dev.ytosko.neutrino.ui.glucose.GlucoseEditDialog
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
    onOpenGlucoseDay: (LocalDate) -> Unit,
    onOpenHealthConnect: () -> Unit,
    savedResult: Boolean?,
    onSavedResultShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val day by viewModel.day.collectAsStateWithLifecycle()
    val shownDate by viewModel.date.collectAsStateWithLifecycle()
    val isToday by viewModel.isToday.collectAsStateWithLifecycle()
    val mealWindows by viewModel.mealWindows.collectAsStateWithLifecycle()
    val glucoseReadings by viewModel.glucoseReadings.collectAsStateWithLifecycle()
    val glucoseLeftOut by viewModel.glucoseLeftOut.collectAsStateWithLifecycle()
    val glucoseVisible by viewModel.glucoseVisible.collectAsStateWithLifecycle()
    val glucoseRange by viewModel.glucoseRange.collectAsStateWithLifecycle()
    val mealGlucose by viewModel.mealGlucose.collectAsStateWithLifecycle()
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val mealTipVisible by viewModel.mealTipVisible.collectAsStateWithLifecycle()
    var editingGlucose by remember { mutableStateOf<GlucoseEntity?>(null) }
    var addingGlucose by remember { mutableStateOf(false) }
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
    val launchActions = context.appContainer.launchAction

    val noCamera = stringResource(R.string.home_no_camera)
    val aiNeeded = stringResource(R.string.home_ai_needed)
    val setUp = stringResource(R.string.home_set_up)
    val waterAdded = stringResource(R.string.home_water_added)
    val mealDeleted = stringResource(R.string.home_meal_deleted)
    val undo = stringResource(R.string.home_undo)

    /** Deletes at once, with Undo in the snackbar instead of a confirmation dialog. */
    fun deleteWithUndo(mealId: String) {
        scope.launch {
            val stored = viewModel.deleteMeal(mealId) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(mealDeleted, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(stored)
        }
    }

    val glucoseDeleted = stringResource(R.string.glucose_deleted)
    fun deleteGlucoseWithUndo(reading: GlucoseEntity) {
        scope.launch {
            val stored = viewModel.deleteGlucose(reading.id) ?: return@launch
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(glucoseDeleted, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undoGlucoseDelete(stored)
        }
    }

    val loggedAgain = stringResource(R.string.home_logged_again)
    val haptics = LocalHapticFeedback.current
    var logAgainChoice by remember { mutableStateOf<LoggedMeal?>(null) }
    /** The meal whose press-and-hold menu is open, and where its card is. */
    var contextMeal by remember { mutableStateOf<Pair<LoggedMeal, Rect>?>(null) }
    var contextReading by remember { mutableStateOf<Pair<GlucoseEntity, Rect>?>(null) }
    /** Logs a copy today (then shows today) or on the meal's own day, with Undo. */
    fun logAgain(meal: LoggedMeal, onItsDay: Boolean) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            val id = viewModel.logAgain(meal, onItsDay, mealWindows) ?: return@launch
            if (!onItsDay) {
                tab = HomeTab.Days
                viewModel.showToday()
            }
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(loggedAgain, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.deleteMeal(id)
        }
    }

    /** A meal from today logs again straight away; one from another day asks which day. */
    fun startLogAgain(meal: LoggedMeal) {
        if (viewModel.isToday(meal)) logAgain(meal, onItsDay = false) else logAgainChoice = meal
    }

    fun openCamera() {
        val uri = newCaptureUri(context)
        pendingCapture = uri.toString()
        try {
            camera.launch(uri)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar(noCamera) }
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

    // The widget's camera and water buttons, and the app shortcuts.
    LaunchedEffect(launchActions) {
        launchActions.collect { action ->
            if (action == null) return@collect
            launchActions.value = null
            tab = HomeTab.Days
            viewModel.showToday()
            when (action) {
                LaunchAction.LOG_MEAL_PHOTO -> withAi { openCamera() }
                LaunchAction.LOG_MEAL -> showSheet = true
                LaunchAction.ADD_WATER -> {
                    viewModel.addWater()
                    snackbar.showSnackbar(waterAdded)
                }
            }
        }
    }

    val menuBlur by animateDpAsState(if (contextMeal != null || contextReading != null) 14.dp else 0.dp, animationSpec = tween(200), label = "blur")
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .blur(menuBlur)
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
                        scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
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
                    scrolledContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
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
                val healthPress = remember { MutableInteractionSource() }
                val logPress = remember { MutableInteractionSource() }
                FloatingActionButton(
                    onClick = { tab = HomeTab.Health },
                    interactionSource = healthPress,
                    modifier = Modifier.pressScale(healthPress, 0.92f),
                    shape = CircleShape,
                    containerColor = NeutrinoTheme.colors.rose.container,
                    contentColor = NeutrinoTheme.colors.rose.content,
                ) {
                    Icon(painterResource(R.drawable.ic_heart), contentDescription = stringResource(R.string.nav_open_health))
                }
                ExtendedFloatingActionButton(
                    onClick = ::startLogging,
                    interactionSource = logPress,
                    modifier = Modifier.pressScale(logPress, 0.95f),
                    icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
                    text = { Text(stringResource(R.string.home_log_meal), style = MaterialTheme.typography.labelLarge) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        snackbarHost = { NeutrinoSnackbarHost(snackbar) },
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
            item {
                TotalsCard(
                    summary?.totals, itemModifier,
                    carbGoalG = goals.carbsG, proteinGoalG = goals.proteinG, fatGoalG = goals.fatG, kcalGoal = goals.kcal,
                )
            }
            item {
                WaterCard(
                    waterMl = summary?.waterMl ?: 0,
                    goalMl = goals.waterMl,
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
            if (mealTipVisible && summary != null && summary.meals.isNotEmpty()) {
                item(key = "meal-tip") { MealTip(onDismiss = viewModel::mealTipDone, modifier = itemModifier.animateItem()) }
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
                            glucose = mealGlucose[meal.id],
                            onOpen = { onOpenMeal(meal.id) },
                            onLogAgain = {
                                viewModel.mealTipDone()
                                startLogAgain(meal)
                            },
                            onDelete = {
                                viewModel.mealTipDone()
                                deleteWithUndo(meal.id)
                            },
                            onLongPress = { bounds ->
                                viewModel.mealTipDone()
                                contextMeal = meal to bounds
                            },
                            hidden = contextMeal?.first?.id == meal.id,
                            modifier = itemModifier.animateItem(),
                        )
                    }
                }
            }
            // Meals first; blood glucose comes after them.
            if (glucoseVisible || glucoseReadings.isNotEmpty()) {
                item(key = "glucose") {
                    GlucoseDayCard(
                        readings = glucoseReadings,
                        range = glucoseRange,
                        isToday = isToday,
                        onOpen = { editingGlucose = it },
                        onAdd = { addingGlucose = true },
                        onSeeAll = { onOpenGlucoseDay(shownDate) },
                        onLongPress = { reading, bounds -> contextReading = reading to bounds },
                        hiddenId = contextReading?.first?.id,
                        onAllowHealthConnect = if (glucoseLeftOut) onOpenHealthConnect else null,
                        modifier = itemModifier.animateItem(),
                    )
                }
            }
        }
        }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.navigationBarsPadding().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(stringResource(R.string.home_log_meal_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SheetTile(R.drawable.ic_camera, stringResource(R.string.home_take_photo), NeutrinoTheme.colors.coral, Modifier.weight(1f)) {
                        withAi { openCamera() }
                    }
                    SheetTile(R.drawable.ic_image, stringResource(R.string.home_choose_photo), NeutrinoTheme.colors.sky, Modifier.weight(1f)) {
                        withAi { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SheetTile(R.drawable.ic_search, stringResource(R.string.home_add_manually), NeutrinoTheme.colors.amber, Modifier.weight(1f)) {
                        showSheet = false
                        onAddManually()
                    }
                    SheetTile(R.drawable.ic_activity, stringResource(R.string.home_add_glucose), NeutrinoTheme.colors.rose, Modifier.weight(1f)) {
                        showSheet = false
                        addingGlucose = true
                    }
                }
            }
        }
    }

    contextMeal?.let { (meal, bounds) ->
        LiftedContextMenu(
            bounds = bounds,
            actions = listOf(
                MenuAction(stringResource(R.string.meal_log_again), R.drawable.ic_refresh) {
                    contextMeal = null
                    startLogAgain(meal)
                },
                MenuAction(stringResource(R.string.home_delete_meal), R.drawable.ic_trash, destructive = true) {
                    contextMeal = null
                    deleteWithUndo(meal.id)
                },
            ),
            onDismiss = { contextMeal = null },
        ) { MealCardContent(meal, mealGlucose[meal.id]) }
    }

    contextReading?.let { (reading, bounds) ->
        LiftedContextMenu(
            bounds = bounds,
            actions = listOf(
                MenuAction(stringResource(R.string.glucose_edit), R.drawable.ic_pencil) {
                    contextReading = null
                    editingGlucose = reading
                },
                MenuAction(stringResource(R.string.glucose_delete), R.drawable.ic_trash, destructive = true) {
                    contextReading = null
                    deleteGlucoseWithUndo(reading)
                },
            ),
            onDismiss = { contextReading = null },
        ) { GlucoseRow(reading, glucoseRange, onClick = {}) }
    }

    logAgainChoice?.let { meal ->
        val day = remember(meal.id) { meal.eatenAt.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("EEE, d MMM")) }
        IosAlert(
            title = stringResource(R.string.log_again_title),
            message = stringResource(R.string.log_again_body, meal.name),
            buttons = listOf(
                AlertButton(stringResource(R.string.log_again_today_full)) {
                    logAgainChoice = null
                    logAgain(meal, onItsDay = false)
                },
                AlertButton(stringResource(R.string.log_again_on_day, day)) {
                    logAgainChoice = null
                    logAgain(meal, onItsDay = true)
                },
                AlertButton(stringResource(R.string.home_cancel), AlertStyle.Cancel) { logAgainChoice = null },
            ),
            onDismiss = { logAgainChoice = null },
        )
    }

    if (addingGlucose) {
        GlucoseEditDialog(
            reading = null,
            range = glucoseRange,
            newReadingTime = remember { viewModel.timeOnShownDay() },
            onSave = { mmol, relation, time ->
                if (mmol != null) viewModel.addGlucose(mmol, relation, time)
                addingGlucose = false
            },
            onDelete = {},
            onDismiss = { addingGlucose = false },
        )
    }

    editingGlucose?.let { reading ->
        GlucoseEditDialog(
            reading = reading,
            range = glucoseRange,
            onSave = { mmol, relation, time ->
                viewModel.editGlucose(reading.id, relation, time, mmol)
                editingGlucose = null
            },
            onDelete = {
                editingGlucose = null
                deleteGlucoseWithUndo(reading)
            },
            onDismiss = { editingGlucose = null },
        )
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
            containerColor = if (problem) MaterialTheme.colorScheme.errorContainer else NeutrinoTheme.colors.sky.container,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            val content = if (problem) MaterialTheme.colorScheme.onErrorContainer else NeutrinoTheme.colors.sky.content
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

/** A big, friendly choice in the "Log a meal" sheet. */
@Composable
private fun SheetTile(icon: Int, label: String, tint: Tint, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(press)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(interactionSource = press, indication = ripple(), role = Role.Button, onClick = onClick)
            .heightIn(min = 112.dp)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        IconBadge(icon = icon, container = tint.container, content = tint.content, size = 44.dp)
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2)
    }
}


@Composable
private fun WaterCard(waterMl: Int, goalMl: Int?, isToday: Boolean, onRemove: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NeutrinoTheme.colors
    val haptics = LocalHapticFeedback.current
    val addPress = remember { MutableInteractionSource() }
    // The glass fills toward the goal (or 2 L without one); the amount counts up as it changes.
    val shownMl by animateIntAsState(waterMl, animationSpec = tween(400), label = "water")
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
            WaterGlass(waterMl / (goalMl ?: 2_000).toFloat(), water = colors.water, container = colors.waterContainer)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.home_water), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (goalMl != null) {
                        stringResource(R.string.home_water_of_goal, formatWater(shownMl), formatWater(goalMl))
                    } else {
                        stringResource(if (isToday) R.string.home_water_amount else R.string.home_water_amount_day, formatWater(shownMl))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (waterMl > 0) {
                IconButton(onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onRemove()
                }) {
                    Icon(
                        painterResource(R.drawable.ic_minus),
                        contentDescription = stringResource(R.string.home_remove_glass),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onAdd()
                },
                interactionSource = addPress,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.waterContainer, contentColor = colors.water),
                enabled = waterMl + HomeViewModel.GLASS_ML <= HomeViewModel.MAX_WATER_ML,
                modifier = Modifier.heightIn(min = 48.dp).pressScale(addPress, 0.94f),
            ) {
                Text(stringResource(R.string.home_add_glass), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MealRow(
    meal: LoggedMeal,
    glucose: MealGlucose?,
    onOpen: () -> Unit,
    onLogAgain: () -> Unit,
    onDelete: () -> Unit,
    /** Press and hold: where the card is on screen, so the menu can lift it in place. */
    onLongPress: (Rect) -> Unit,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val logAgainLabel = stringResource(R.string.meal_log_again)
    val deleteLabel = stringResource(R.string.home_delete_meal)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val press = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(press)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            // While lifted, the copy above the blur stands in for the card.
            .alpha(if (hidden) 0f else 1f)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(
                interactionSource = press,
                indication = ripple(),
                onClickLabel = stringResource(R.string.meal_open),
                onLongClickLabel = stringResource(R.string.meal_actions),
                onClick = onOpen,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress(bounds)
                },
            )
            .semantics {
                // Screen readers offer both actions directly, without the long-press.
                customActions = listOf(
                    CustomAccessibilityAction(logAgainLabel) { onLogAgain(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        MealCardContent(meal, glucose)
    }
}

/** What a meal card shows: photo or food icon, name, time, calories, macros and glucose. */
@Composable
private fun MealCardContent(meal: LoggedMeal, glucose: MealGlucose?) {
    val colors = NeutrinoTheme.colors
    val time = remember(meal.eatenAt) {
        meal.eatenAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    }
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
                MacroText(stringResource(R.string.macro_letter_carbs), meal.nutrition.carbsG, colors.carbs)
                MacroText(stringResource(R.string.macro_letter_protein), meal.nutrition.proteinG, colors.protein)
                MacroText(stringResource(R.string.macro_letter_fat), meal.nutrition.fatG, colors.fat)
                if (!meal.syncedToHealthConnect) {
                    Text(stringResource(R.string.home_not_synced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (glucose != null) MealGlucoseLine(glucose)
        }
    }
}


@Composable
private fun MacroText(letter: String, grams: Double, color: androidx.compose.ui.graphics.Color) {
    Text("$letter ${grams.roundGrams().toString().removeSuffix(".0")}g", style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun Thumbnail(path: String?, category: FoodCategory?, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    if (path == null && category != null) {
        FoodIcon(category, size = size)
        return
    }
    Box(
        modifier = Modifier
            .size(size)
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
        PlateIllustration()
        Spacer(Modifier.height(Spacing.sm))
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
    val (content, container) = mealTypeColors(type)
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


/** "Glucose 5.8 → 9.4 mmol/L" under a meal: the reading before it and about 2 hours after. */
@Composable
private fun MealGlucoseLine(glucose: MealGlucose) {
    val unit = LocalGlucoseUnit.current
    val text = when {
        glucose.before != null && glucose.after != null ->
            stringResource(R.string.home_meal_glucose_both, unit.format(glucose.before.mmolPerL), unit.format(glucose.after.mmolPerL), unit.label)
        glucose.before != null -> stringResource(R.string.home_meal_glucose_before, unit.format(glucose.before.mmolPerL), unit.label)
        else -> stringResource(R.string.home_meal_glucose_after, unit.format(glucose.after!!.mmolPerL), unit.label)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        Icon(painterResource(R.drawable.ic_activity), contentDescription = null, tint = NeutrinoTheme.colors.glucose, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}



/** One-time hint that meals can be pressed and held. */
@Composable
private fun MealTip(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(NeutrinoTheme.colors.indigo.container)
            .padding(start = Spacing.md, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = NeutrinoTheme.colors.indigo.content, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.meal_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.meal_tip_dismiss), modifier = Modifier.size(18.dp))
        }
    }
}

