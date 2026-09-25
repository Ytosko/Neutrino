package dev.ytosko.neutrino.ui.insights

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.domain.insights.compactNumber
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Bar chart you can read with your thumb: tap a bar, or press and slide across, and [onSelect]
 * reports the bar under the finger (the caller shows its numbers). Horizontal drags only, so the
 * page still scrolls vertically. A dashed line marks [average].
 */
@Composable
fun BarChart(
    values: List<Double>,
    color: Color,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    average: Double? = null,
    xLabel: (Int) -> String? = { null },
    height: Dp = 180.dp,
    /** Text for the bubble over the touched bar, e.g. "1.2k kcal"; no bubble when null. */
    bubble: ((Int) -> String)? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val bubbleColor = MaterialTheme.colorScheme.inverseSurface
    val bubbleStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface)
    val averageColor = MaterialTheme.colorScheme.onSurfaceVariant
    val currentSelect = rememberUpdatedState(onSelect)
    val currentSelected = rememberUpdatedState(selected)
    val count = values.size.coerceAtLeast(1)
    val top = niceMax(max(values.maxOrNull() ?: 0.0, average ?: 0.0))

    fun indexAt(x: Float, width: Float, plotWidth: Float): Int? {
        if (x < 0 || x > plotWidth || width <= 0) return null
        return floor(x / (plotWidth / count)).toInt().coerceIn(0, count - 1)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(count) {
                detectTapGestures { offset ->
                    val plot = size.width - AXIS_GUTTER.toPx()
                    val index = indexAt(offset.x, size.width.toFloat(), plot)
                    currentSelect.value(if (index == currentSelected.value) null else index)
                }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val plot = size.width - AXIS_GUTTER.toPx()
                        currentSelect.value(indexAt(offset.x, size.width.toFloat(), plot))
                    },
                ) { change, _ ->
                    val plot = size.width - AXIS_GUTTER.toPx()
                    indexAt(change.position.x.coerceIn(0f, plot - 1f), size.width.toFloat(), plot)?.let { currentSelect.value(it) }
                    change.consume()
                }
            },
    ) {
        val gutter = AXIS_GUTTER.toPx()
        val labelSpace = 18.dp.toPx()
        val plotWidth = size.width - gutter
        val plotHeight = size.height - labelSpace
        val slot = plotWidth / count
        val barWidth = (slot * if (count > 14) 0.62f else 0.5f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius(min(barWidth / 2, 6.dp.toPx()))

        // Grid lines at 0, half and top, with values on the right.
        for (fraction in listOf(0f, 0.5f, 1f)) {
            val y = plotHeight - plotHeight * fraction
            drawLine(grid, Offset(0f, y), Offset(plotWidth, y), strokeWidth = 1f)
            if (fraction > 0f && top > 0) {
                val text = measurer.measure(compactNumber(top * fraction), labelStyle)
                drawText(text, topLeft = Offset(plotWidth + 6.dp.toPx(), y - text.size.height / 2f))
            }
        }

        values.forEachIndexed { index, value ->
            val h = if (top > 0) (value / top * plotHeight).toFloat() else 0f
            val x = slot * index + (slot - barWidth) / 2
            val alpha = if (selected == null || selected == index) 1f else 0.3f
            if (h > 0f) {
                // Soft vertical gradient, like Apple Health.
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.55f)),
                        startY = plotHeight - h,
                        endY = plotHeight,
                    ),
                    topLeft = Offset(x, plotHeight - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            }
            xLabel(index)?.let { label ->
                val text = measurer.measure(label, labelStyle)
                val left = (x + barWidth / 2 - text.size.width / 2f).coerceIn(0f, plotWidth - text.size.width)
                drawText(text, topLeft = Offset(left, plotHeight + 4.dp.toPx()))
            }
        }

        if (average != null && average > 0 && top > 0) {
            val y = plotHeight - (average / top * plotHeight).toFloat()
            drawLine(
                averageColor,
                Offset(0f, y),
                Offset(plotWidth, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
            )
        }

        // Marker line through the selected bar, and its value in a floating bubble.
        selected?.takeIf { it in values.indices }?.let { index ->
            val cx = slot * index + slot / 2
            drawLine(color.copy(alpha = 0.4f), Offset(cx, 0f), Offset(cx, plotHeight), strokeWidth = 1.dp.toPx())
            bubble?.invoke(index)?.let { label ->
                val text = measurer.measure(label, bubbleStyle)
                val padX = 8.dp.toPx()
                val padY = 4.dp.toPx()
                val bw = text.size.width + padX * 2
                val bh = text.size.height + padY * 2
                val barTop = plotHeight - (if (top > 0) (values[index] / top * plotHeight).toFloat() else 0f)
                val left = (cx - bw / 2).coerceIn(0f, plotWidth - bw)
                val topY = (barTop - bh - 6.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(bubbleColor, topLeft = Offset(left, topY), size = Size(bw, bh), cornerRadius = CornerRadius(bh / 2))
                drawText(text, topLeft = Offset(left + padX, topY + padY))
            }
        }
    }
}

/** One donut slice. */
data class Slice(val value: Double, val color: Color)

/**
 * Donut showing a split (e.g. calories from carbs, protein and fat). Tap a slice to select it;
 * [center] draws the middle, typically the selected slice's numbers.
 */
@Composable
fun DonutChart(
    slices: List<Slice>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
    center: @Composable () -> Unit,
) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val total = slices.sumOf { it.value }
    val currentSelect = rememberUpdatedState(onSelect)
    val currentSelected = rememberUpdatedState(selected)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(size)
                .semantics { contentDescription = description }
                .pointerInput(slices) {
                    detectTapGestures { offset ->
                        val cx = this.size.width / 2f
                        val cy = this.size.height / 2f
                        val distance = hypot(offset.x - cx, offset.y - cy)
                        val outer = min(cx, cy)
                        if (total <= 0 || distance < outer * 0.55f || distance > outer) {
                            currentSelect.value(null)
                            return@detectTapGestures
                        }
                        // Angle from 12 o'clock, clockwise.
                        var angle = Math.toDegrees(atan2((offset.y - cy).toDouble(), (offset.x - cx).toDouble())) + 90
                        if (angle < 0) angle += 360
                        var start = 0.0
                        slices.forEachIndexed { index, slice ->
                            val sweep = slice.value / total * 360
                            if (angle >= start && angle < start + sweep) {
                                currentSelect.value(if (index == currentSelected.value) null else index)
                                return@detectTapGestures
                            }
                            start += sweep
                        }
                    }
                },
        ) {
            val stroke = this.size.minDimension * 0.16f
            val inset = stroke / 2 + (if (selected != null) 3.dp.toPx() else 0f)
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            if (total <= 0) {
                drawArc(empty, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                return@Canvas
            }
            val gap = if (slices.count { it.value > 0 } > 1) 2f else 0f
            var start = -90f
            slices.forEachIndexed { index, slice ->
                val sweep = (slice.value / total * 360).toFloat()
                if (sweep > 0f) {
                    val isSelected = selected == index
                    drawArc(
                        color = slice.color.copy(alpha = if (selected == null || isSelected) 1f else 0.35f),
                        startAngle = start + gap / 2,
                        sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(if (isSelected) stroke * 1.25f else stroke, cap = StrokeCap.Butt),
                    )
                }
                start += sweep
            }
        }
        center()
    }
}

/** Horizontal bar for a share of a whole, e.g. carbs at breakfast vs the day. */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(10.dp)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
    }
}

/** Space on the right of a bar chart for axis values. */
private val AXIS_GUTTER = 36.dp

/** Rounds a chart's top up to 1, 2, 2.5 or 5 × 10ⁿ so grid values read cleanly. */
internal fun niceMax(value: Double): Double {
    if (value <= 0) return 0.0
    val magnitude = 10.0.pow(floor(log10(value)))
    val normalized = value / magnitude
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it >= normalized - 1e-9 }
    return ceil(step * magnitude)
}
