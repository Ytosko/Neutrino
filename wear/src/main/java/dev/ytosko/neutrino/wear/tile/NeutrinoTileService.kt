package dev.ytosko.neutrino.wear.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.degrees
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.ytosko.neutrino.wear.MainActivity
import dev.ytosko.neutrino.wear.R
import dev.ytosko.neutrino.wear.WATER_GLASS_ML
import dev.ytosko.neutrino.wear.data.PhoneLink
import dev.ytosko.neutrino.wear.data.SnapshotStore
import dev.ytosko.neutrino.wear.protocol.WearAction
import dev.ytosko.neutrino.wear.protocol.WearGlucose
import dev.ytosko.neutrino.wear.protocol.WearMacro
import dev.ytosko.neutrino.wear.protocol.WearSnapshot
import dev.ytosko.neutrino.wear.ui.WearColors
import dev.ytosko.neutrino.wear.ui.spoken
import dev.ytosko.neutrino.wear.ui.timeText

/**
 * The Neutrino tile: the latest glucose reading, today's four macro rings, water and a +250 ml
 * button. Redrawn whenever the phone sends a new snapshot.
 */
class NeutrinoTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            val clicked = requestParams.currentState.lastClickableId
            if (clicked.startsWith(WATER_CLICK_PREFIX) && SnapshotStore.claimTileClick(this@NeutrinoTileService, clicked)) {
                // The phone adds the water and sends a new snapshot, which redraws this tile.
                PhoneLink.send(this@NeutrinoTileService, WearAction.AddWater(WATER_GLASS_ML))
            }
            val snapshot = SnapshotStore.current(this@NeutrinoTileService)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // Again after a while, so the totals start from zero after midnight.
                .setFreshnessIntervalMillis(FRESHNESS_MS)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this@NeutrinoTileService, snapshot)))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val FRESHNESS_MS = 30 * 60 * 1000L
        const val WATER_CLICK_PREFIX = "water@"
        const val RING = 38f

        fun layout(context: Context, snapshot: WearSnapshot?): LayoutElementBuilders.LayoutElement {
            val openApp = ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(context.packageName)
                                .setClassName(MainActivity::class.java.name)
                                .build(),
                        )
                        .build(),
                )
                .build()
            val column = LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            if (snapshot == null) {
                column.addContent(
                    text(context.getString(R.string.wear_open_phone), 14f, WearColors.ON_SURFACE, bold = false, maxLines = 3),
                )
            } else {
                column.addContent(glucose(context, snapshot.glucose))
                column.addContent(spacer(6f))
                column.addContent(rings(context, snapshot))
                column.addContent(spacer(6f))
                column.addContent(
                    text(context.getString(R.string.home_water) + " · " + snapshot.waterText, 13f, WearColors.WATER, bold = true),
                )
                if (snapshot.canAddWater) {
                    column.addContent(spacer(4f))
                    column.addContent(waterButton(context, snapshot))
                }
            }
            return LayoutElementBuilders.Box.Builder()
                .setWidth(expand())
                .setHeight(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
                .addContent(column.build())
                .build()
        }

        fun glucose(context: Context, glucose: WearGlucose?): LayoutElementBuilders.LayoutElement {
            if (glucose == null) return text(context.getString(R.string.glucose_widget_none), 12f, WearColors.MUTED, bold = false)
            val line = LayoutElementBuilders.Spannable.Builder()
                .addSpan(span(glucose.value, 18f, WearColors.band(glucose.band), bold = true))
                .addSpan(span(" " + glucose.unit + " · " + glucose.timeText(), 11f, WearColors.MUTED, bold = false))
                .setMaxLines(1)
                .setModifiers(semantics(glucose.spoken(context)))
                .build()
            return line
        }

        fun rings(context: Context, snapshot: WearSnapshot): LayoutElementBuilders.LayoutElement {
            val row = LayoutElementBuilders.Row.Builder()
            val macros = listOf(
                Triple(snapshot.carbs, R.string.macro_carbs, WearColors.CARBS),
                Triple(snapshot.protein, R.string.macro_protein, WearColors.PROTEIN),
                Triple(snapshot.fat, R.string.macro_fat, WearColors.FAT),
                Triple(snapshot.kcal, R.string.macro_energy, WearColors.KCAL),
            )
            macros.forEachIndexed { index, (macro, label, color) ->
                if (index > 0) row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(3f)).build())
                row.addContent(ring(context, macro, context.getString(label), color))
            }
            return row.build()
        }

        /** A macro's ring toward its goal (only the track without one), the amount inside and its name below. */
        fun ring(context: Context, macro: WearMacro, label: String, color: Int): LayoutElementBuilders.LayoutElement {
            val progress = (macro.progress ?: 0f).coerceIn(0f, 1f)
            val thickness = dp(3.5f)
            val arcs = LayoutElementBuilders.Box.Builder()
                .setWidth(dp(RING))
                .setHeight(dp(RING))
                .addContent(
                    LayoutElementBuilders.Arc.Builder()
                        .setAnchorAngle(degrees(0f))
                        .addContent(
                            LayoutElementBuilders.ArcLine.Builder()
                                .setLength(degrees(360f))
                                .setThickness(thickness)
                                .setColor(argb(WearColors.faint(color)))
                                .build(),
                        )
                        .build(),
                )
            if (progress > 0f) {
                arcs.addContent(
                    LayoutElementBuilders.Arc.Builder()
                        .setAnchorAngle(degrees(0f))
                        .setAnchorType(LayoutElementBuilders.ARC_ANCHOR_START)
                        .addContent(
                            LayoutElementBuilders.ArcLine.Builder()
                                .setLength(degrees(360f * progress))
                                .setThickness(thickness)
                                .setColor(argb(color))
                                .build(),
                        )
                        .build(),
                )
            }
            arcs.addContent(text(macro.text, 10f, color, bold = true))
            val spoken = "$label " + (macro.goalText?.let { context.getString(R.string.widget_of_goal, macro.text, it) } ?: macro.text)
            return LayoutElementBuilders.Column.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setModifiers(semantics(spoken))
                .addContent(arcs.build())
                .addContent(text(label, 8f, WearColors.MUTED, bold = false))
                .build()
        }

        fun waterButton(context: Context, snapshot: WearSnapshot): LayoutElementBuilders.LayoutElement {
            // A new id for each snapshot, so one tap adds one glass even if the tile is asked again.
            val click = ModifiersBuilders.Clickable.Builder()
                .setId("$WATER_CLICK_PREFIX${snapshot.publishedAtEpochMs}:${snapshot.waterMl}")
                .setOnClick(ActionBuilders.LoadAction.Builder().build())
                .build()
            return LayoutElementBuilders.Box.Builder()
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .setHeight(dp(32f))
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(click)
                        .setBackground(
                            ModifiersBuilders.Background.Builder()
                                .setColor(argb(WearColors.WATER_CONTAINER))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(16f)).build())
                                .build(),
                        )
                        .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(14f)).setEnd(dp(14f)).build())
                        .setSemantics(
                            ModifiersBuilders.Semantics.Builder()
                                .setContentDescription(context.getString(R.string.wear_add_water_description))
                                .setRole(ModifiersBuilders.SEMANTICS_ROLE_BUTTON)
                                .build(),
                        )
                        .build(),
                )
                .addContent(text(context.getString(R.string.home_add_glass), 13f, WearColors.WATER, bold = true))
                .build()
        }

        fun text(value: String, size: Float, color: Int, bold: Boolean, maxLines: Int = 1): LayoutElementBuilders.LayoutElement =
            LayoutElementBuilders.Text.Builder()
                .setText(value)
                .setMaxLines(maxLines)
                .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                .setFontStyle(font(size, color, bold))
                .build()

        fun span(value: String, size: Float, color: Int, bold: Boolean): LayoutElementBuilders.Span =
            LayoutElementBuilders.SpanText.Builder().setText(value).setFontStyle(font(size, color, bold)).build()

        fun font(size: Float, color: Int, bold: Boolean): LayoutElementBuilders.FontStyle =
            LayoutElementBuilders.FontStyle.Builder()
                .setSize(sp(size))
                .setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build()

        fun spacer(height: Float): LayoutElementBuilders.LayoutElement =
            LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

        fun semantics(description: String): ModifiersBuilders.Modifiers =
            ModifiersBuilders.Modifiers.Builder()
                .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(description).build())
                .build()
    }
}
