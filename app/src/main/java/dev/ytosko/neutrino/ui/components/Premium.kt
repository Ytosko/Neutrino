package dev.ytosko.neutrino.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlin.math.PI
import kotlin.math.sin

// ---- Press feedback --------------------------------------------------------------------------

/** Shrinks slightly while pressed and springs back, like iPhone buttons and cards. */
fun Modifier.pressScale(interactionSource: MutableInteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

// ---- Alerts ------------------------------------------------------------------------------------

enum class AlertStyle { Default, Cancel, Destructive }

data class AlertButton(val text: String, val style: AlertStyle = AlertStyle.Default, val onClick: () -> Unit)

/**
 * An iPhone-style alert: centred title and message, then full-width buttons separated by thin
 * lines (side by side when there are two). The cancel button is bold; destructive ones are red.
 */
@Composable
fun IosAlert(
    title: String,
    message: String?,
    buttons: List<AlertButton>,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(290.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 12.dp,
        ) {
            Column {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    if (message != null) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (buttons.size == 2) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        AlertAction(buttons[0], Modifier.weight(1f))
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AlertAction(buttons[1], Modifier.weight(1f))
                    }
                } else {
                    buttons.forEachIndexed { i, b ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        AlertAction(b, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertAction(button: AlertButton, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .heightIn(min = 50.dp)
            .clickable(role = Role.Button) {
                if (button.style == AlertStyle.Destructive) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                button.onClick()
            }
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            button.text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (button.style == AlertStyle.Cancel) FontWeight.SemiBold else FontWeight.Normal,
            color = when (button.style) {
                AlertStyle.Destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Toasts ------------------------------------------------------------------------------------

/** Messages as a floating rounded "toast" instead of a full-width bar; the action stays tappable. */
@Composable
fun NeutrinoSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> Toast(data) }
}

@Composable
private fun Toast(data: SnackbarData) {
    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).padding(start = 18.dp, end = if (data.visuals.actionLabel != null) 6.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                data.visuals.actionLabel?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.inversePrimary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { data.performAction() }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

// ---- Segmented control -----------------------------------------------------------------------------

/** An iPhone-style segmented control: a soft track with a white thumb that slides to the choice. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, spring(dampingRatio = 0.8f, stiffness = 600f), label = "thumb")
        Surface(
            modifier = Modifier.offset(x = offset).width(segment).fillMaxHeight(),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shadowElevation = 3.dp,
        ) {}
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = i == selected, role = Role.Tab, indication = null, interactionSource = null) {
                            if (i != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---- Lifted context menu -------------------------------------------------------------------------

data class MenuAction(val label: String, val icon: Int, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * iPhone-style context menu: the screen behind dims (the caller blurs it), [lifted] is drawn again
 * exactly over the pressed item at [bounds], slightly raised, and a rounded menu opens under it
 * (or above it near the bottom of the screen).
 */
@Composable
fun LiftedContextMenu(
    bounds: Rect,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    lifted: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val lift by animateFloatAsState(if (shown) 1.03f else 1f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f), label = "lift")
    val appear by animateFloatAsState(if (shown) 1f else 0f, animationSpec = tween(180), label = "appear")
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f * appear))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            val screenH = with(density) { maxHeight.toPx() }
            val menuH = with(density) { (56.dp * actions.size + 1.dp).toPx() }
            val gap = with(density) { 10.dp.toPx() }
            val below = bounds.bottom + gap + menuH < screenH - with(density) { 24.dp.toPx() }
            Surface(
                modifier = Modifier
                    .offset { IntOffset(bounds.left.toInt(), bounds.top.toInt()) }
                    .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
                    .graphicsLayer { scaleX = lift; scaleY = lift },
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shadowElevation = 16.dp,
            ) { lifted() }
            Surface(
                modifier = Modifier
                    .offset {
                        val y = if (below) bounds.bottom + gap else bounds.top - gap - menuH
                        IntOffset(bounds.left.toInt(), y.toInt())
                    }
                    .width(250.dp)
                    .graphicsLayer {
                        alpha = appear
                        scaleX = 0.9f + 0.1f * appear
                        scaleY = 0.9f + 0.1f * appear
                        transformOrigin = TransformOrigin(0f, if (below) 0f else 1f)
                    },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shadowElevation = 12.dp,
            ) {
                Column {
                    actions.forEachIndexed { i, action ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(role = Role.Button, onClick = action.onClick)
                                .padding(horizontal = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(action.label, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
                            Icon(painterResource(action.icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---- Water glass ---------------------------------------------------------------------------------

/**
 * A small glass that fills to [fraction] with a gently moving wave, in place of a progress bar.
 * The wave stops when the glass is empty.
 */
@Composable
fun WaterGlass(fraction: Float, water: Color, container: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val level by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = spring(dampingRatio = 0.8f, stiffness = 120f), label = "level")
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        // A tumbler: slightly narrower at the bottom.
        val inset = w * 0.08f
        val glass = Path().apply {
            moveTo(w * 0.14f, h * 0.10f)
            lineTo(w * 0.86f, h * 0.10f)
            lineTo(w * 0.78f, h * 0.92f)
            quadraticTo(w * 0.77f, h * 0.97f, w * 0.72f, h * 0.97f)
            lineTo(w * 0.28f, h * 0.97f)
            quadraticTo(w * 0.23f, h * 0.97f, w * 0.22f, h * 0.92f)
            close()
        }
        drawRoundRect(container, cornerRadius = CornerRadius(w * 0.28f))
        clipPath(glass) {
            if (level > 0f) {
                val top = h * 0.97f - (h * 0.87f) * level
                val amp = if (level in 0.02f..0.98f) h * 0.035f else 0f
                val wave = Path().apply {
                    moveTo(0f, top)
                    var x = 0f
                    while (x <= w) {
                        lineTo(x, top + amp * sin((x / w) * 2 * PI.toFloat() * 1.5f + phase))
                        x += w / 24
                    }
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(wave, water.copy(alpha = 0.85f))
            }
        }
        drawPath(glass, water, style = Stroke(width = w * 0.05f))
        // A small shine on the glass.
        drawLine(Color.White.copy(alpha = 0.6f), Offset(w * 0.28f + inset * 0f, h * 0.22f), Offset(w * 0.32f, h * 0.55f), strokeWidth = w * 0.04f)
    }
}

// ---- Empty-state illustrations -------------------------------------------------------------------

/** A friendly empty plate with fork and knife, for days with no meals yet. */
@Composable
fun PlateIllustration(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val rim = MaterialTheme.colorScheme.surfaceContainerLowest
    val shade = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = MaterialTheme.colorScheme.primary
    val steel = MaterialTheme.colorScheme.outline
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension * 0.34f
        drawCircle(accent.copy(alpha = 0.10f), radius = r * 1.38f, center = c)
        drawCircle(rim, radius = r, center = c)
        drawCircle(shade, radius = r * 0.72f, center = c)
        drawCircle(rim, radius = r * 0.62f, center = c)
        // Fork (left) and knife (right).
        val stroke = this.size.width * 0.035f
        val top = c.y - r * 0.95f
        val bottom = c.y + r * 0.95f
        val fx = c.x - r * 1.18f
        drawLine(steel, Offset(fx, top + r * 0.55f), Offset(fx, bottom), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        listOf(-1f, 0f, 1f).forEach { t ->
            drawLine(steel, Offset(fx + t * stroke * 1.4f, top), Offset(fx + t * stroke * 1.4f, top + r * 0.45f), strokeWidth = stroke * 0.7f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        val kx = c.x + r * 1.18f
        drawLine(steel, Offset(kx, top), Offset(kx, bottom), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(steel, topLeft = Offset(kx - stroke * 0.5f, top), size = Size(stroke * 1.6f, r * 0.8f), cornerRadius = CornerRadius(stroke))
    }
}

/** Soft rising bars, for an empty Health page. */
@Composable
fun ChartIllustration(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    val colors = listOf(Color(0xFFB45309), Color(0xFF0F766E), Color(0xFF6D28D9), Color(0xFF0369A1))
    val back = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(back, radius = w * 0.47f, center = Offset(w / 2, h / 2))
        val barW = w * 0.11f
        val gap = w * 0.06f
        val heights = listOf(0.28f, 0.44f, 0.36f, 0.56f)
        val start = (w - (barW * 4 + gap * 3)) / 2
        heights.forEachIndexed { i, f ->
            val x = start + i * (barW + gap)
            val bh = h * f
            drawRoundRect(colors[i].copy(alpha = 0.85f), topLeft = Offset(x, h * 0.74f - bh), size = Size(barW, bh), cornerRadius = CornerRadius(barW / 2))
        }
    }
}
