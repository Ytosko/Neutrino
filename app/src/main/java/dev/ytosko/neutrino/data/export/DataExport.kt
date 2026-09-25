package dev.ytosko.neutrino.data.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.glucose.isManual
import dev.ytosko.neutrino.data.glucose.relationEnum
import dev.ytosko.neutrino.data.meal.MealDatabase
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.GlucoseUnit
import dev.ytosko.neutrino.domain.insights.formatWater
import dev.ytosko.neutrino.domain.report.Report
import dev.ytosko.neutrino.domain.report.ReportBuilder
import dev.ytosko.neutrino.domain.report.ReportMeal
import dev.ytosko.neutrino.domain.report.ReportReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Makes files the user shares themselves: a PDF report for their doctor and a zip of CSV files
 * with everything they've logged. Built on the phone; nothing is uploaded anywhere.
 */
class DataExport(
    private val context: Context,
    private val db: MealDatabase,
    private val settings: SettingsRepository,
) {
    private val exportDir get() = File(context.cacheDir, "exports")

    /** Report data for the last [days] days, ending today. */
    suspend fun report(days: Int, zone: ZoneId = ZoneId.systemDefault()): Report = withContext(Dispatchers.IO) {
        val to = LocalDate.now(zone)
        val from = to.minusDays(days - 1L)
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val s = settings.settings.first()
        val meals = db.backup().meals().filter { it.eatenAtEpochMs in fromMs until toMs }
            .map { ReportMeal(Instant.ofEpochMilli(it.eatenAtEpochMs), it.carbsG, it.proteinG, it.fatG, it.calories) }
        val water = db.backup().water().filter { it.loggedAtEpochMs in fromMs until toMs }
            .map { Instant.ofEpochMilli(it.loggedAtEpochMs) to it.amountMl }
        val readings = db.glucose().all().filter { it.measuredAtEpochMs in fromMs until toMs }
            .map { ReportReading(Instant.ofEpochMilli(it.measuredAtEpochMs), it.mmolPerL, it.relationEnum, !it.isManual) }
        ReportBuilder.build(from, to, zone, meals, water, readings, s.glucoseLow, s.glucoseHigh)
    }

    /** Writes the report as a PDF in the app's cache and returns a shareable content:// URI. */
    suspend fun reportPdf(report: Report, zone: ZoneId = ZoneId.systemDefault()): Uri = withContext(Dispatchers.IO) {
        val unit = settings.settings.first().glucoseUnit
        val file = freshFile("neutrino-report-${report.to}.pdf")
        file.outputStream().use { PdfReport(context, report, unit, zone).write(it) }
        uriFor(file)
    }

    /** Everything as CSV files (meals, foods in each meal, water, glucose, your food list) in one zip. */
    suspend fun csvZip(zone: ZoneId = ZoneId.systemDefault()): Uri = withContext(Dispatchers.IO) {
        val file = freshFile("neutrino-data-${LocalDate.now(zone)}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, header: List<String>, rows: List<List<Any?>>) {
                zip.putNextEntry(ZipEntry(name))
                val text = buildString {
                    appendLine(header.joinToString(",") { csv(it) })
                    rows.forEach { row -> appendLine(row.joinToString(",") { csv(it) }) }
                }
                // UTF-8 with BOM so spreadsheet apps show non-English food names correctly.
                zip.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val date = DateTimeFormatter.ISO_LOCAL_DATE
            val time = DateTimeFormatter.ofPattern("HH:mm:ss")
            fun at(ms: Long, zoneId: String) = Instant.ofEpochMilli(ms).atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(zone))

            val meals = db.backup().meals().sortedBy { it.eatenAtEpochMs }
            entry(
                "meals.csv",
                listOf("id", "date", "time", "time_zone", "meal", "name", "kcal", "carbs_g", "protein_g", "fat_g", "in_health_connect"),
                meals.map {
                    val t = at(it.eatenAtEpochMs, it.zoneId)
                    listOf(it.id, t.format(date), t.format(time), it.zoneId, it.mealType, it.name, r1(it.calories), r1(it.carbsG), r1(it.proteinG), r1(it.fatG), it.syncedToHealthConnect)
                },
            )
            entry(
                "meal_foods.csv",
                listOf("meal_id", "position", "food", "category", "quantity", "unit", "grams", "kcal", "carbs_g", "protein_g", "fat_g"),
                db.backup().items().sortedWith(compareBy({ it.mealId }, { it.position })).map {
                    listOf(it.mealId, it.position + 1, it.name, it.category, r1(it.quantity), it.unit, r1(it.grams), r1(it.calories), r1(it.carbsG), r1(it.proteinG), r1(it.fatG))
                },
            )
            entry(
                "water.csv",
                listOf("id", "date", "time", "time_zone", "ml", "in_health_connect"),
                db.backup().water().sortedBy { it.loggedAtEpochMs }.map {
                    val t = at(it.loggedAtEpochMs, it.zoneId)
                    listOf(it.id, t.format(date), t.format(time), it.zoneId, it.amountMl, it.syncedToHealthConnect)
                },
            )
            entry(
                "glucose.csv",
                listOf("id", "date", "time", "time_zone", "mmol_per_l", "mg_per_dl", "meal_mark", "meter_showed", "time_estimated", "source", "meter_serial", "edited", "in_health_connect"),
                db.glucose().all().sortedBy { it.measuredAtEpochMs }.map {
                    val t = at(it.measuredAtEpochMs, it.zoneId)
                    listOf(
                        it.id, t.format(date), t.format(time), it.zoneId,
                        String.format(Locale.US, "%.1f", it.mmolPerL), GlucoseUnit.toMgDl(it.mmolPerL).roundToInt(),
                        it.relationEnum.name, it.rangeFlag?.let { f -> if (f == "High") "HI" else "LO" }, it.timeEstimated,
                        if (it.isManual) "typed in" else "meter", it.meterSerial, it.edited, it.syncedToHealthConnect,
                    )
                },
            )
            entry(
                "my_foods.csv",
                listOf("name", "category", "kcal_per_100g", "carbs_per_100g", "protein_per_100g", "fat_per_100g", "times_eaten", "source"),
                db.backup().foods().sortedByDescending { it.useCount }.map {
                    listOf(it.name, it.category, r1(it.kcalPer100g), r1(it.carbsPer100g), r1(it.proteinPer100g), r1(it.fatPer100g), it.useCount, it.source)
                },
            )
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(context.getString(R.string.export_readme).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        uriFor(file)
    }

    /** Copies an exported file to where the user chose to save it. */
    suspend fun copy(from: Uri, to: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(from)?.use { input ->
            context.contentResolver.openOutputStream(to)?.use { output -> input.copyTo(output) }
        }
    }

    /** Only the latest export is kept; older ones are deleted. */
    private fun freshFile(name: String): File {
        exportDir.mkdirs()
        exportDir.listFiles()?.forEach { it.delete() }
        return File(exportDir, name)
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    private fun r1(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun csv(value: Any?): String {
        val s = value?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}

/** Draws the doctor report on A4 pages with Android's built-in PDF writer. */
private class PdfReport(
    private val context: Context,
    private val report: Report,
    private val unit: GlucoseUnit,
    private val zone: ZoneId,
) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = 0f

    private val ink = Color.rgb(33, 26, 25)
    private val muted = Color.rgb(110, 100, 98)
    private val line = Color.rgb(225, 215, 212)
    private val low = Color.rgb(186, 26, 26)
    private val inRange = Color.rgb(15, 118, 110)
    private val high = Color.rgb(180, 83, 9)

    private fun paint(size: Float, color: Int = ink, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private val canvas get() = page!!.canvas
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun g(mmol: Double) = unit.format(mmol)
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM")
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun write(out: OutputStream) {
        newPage()
        header()
        if (report.isEmpty) {
            text(s(R.string.report_empty), paint(12f, muted))
        }
        report.glucose?.let { glucose(it) }
        if (report.glucose != null && report.days.any { it.glucoseAverage != null }) glucoseChart()
        report.food?.let { food(it) }
        if (report.days.isNotEmpty()) dailyTable()
        if (report.readings.isNotEmpty()) readingsTable()
        finishPage()
        doc.writeTo(out)
        doc.close()
    }

    // ---- Page handling --------------------------------------------------------------------------

    private fun newPage() {
        finishPage()
        pageNumber++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNumber).create())
        y = M
    }

    private fun finishPage() {
        val p = page ?: return
        p.canvas.drawText(s(R.string.report_footer, pageNumber), M, H - 24f, paint(8f, muted))
        doc.finishPage(p)
        page = null
    }

    /** Starts a new page if [height] more points won't fit. */
    private fun need(height: Float) {
        if (y + height > H - 48f) newPage()
    }

    private fun text(value: String, p: Paint, x: Float = M, gap: Float = 4f) {
        need(p.textSize + gap)
        y += p.textSize
        canvas.drawText(value, x, y, p)
        y += gap
    }

    /** Wraps [value] to the page width. */
    private fun paragraph(value: String, p: Paint) {
        val words = value.split(" ")
        var current = ""
        for (w in words) {
            val next = if (current.isEmpty()) w else "$current $w"
            if (p.measureText(next) > W - 2 * M) {
                text(current, p, gap = 2f)
                current = w
            } else {
                current = next
            }
        }
        if (current.isNotEmpty()) text(current, p, gap = 2f)
    }

    private fun section(title: String) {
        // Keep a heading together with at least a few lines of what follows.
        need(90f)
        y += 14f
        text(title, paint(14f, ink, bold = true), gap = 6f)
        canvas.drawLine(M, y, W - M, y, paint(1f, line))
        y += 8f
    }

    private fun keyValues(pairs: List<Pair<String, String>>) {
        val label = paint(10f, muted)
        val value = paint(12f, ink, bold = true)
        val columns = 3
        val colWidth = (W - 2 * M) / columns
        pairs.chunked(columns).forEach { row ->
            need(34f)
            row.forEachIndexed { i, (k, v) ->
                canvas.drawText(k, M + i * colWidth, y + 10f, label)
                canvas.drawText(v, M + i * colWidth, y + 26f, value)
            }
            y += 36f
        }
    }

    private fun table(headers: List<String>, widths: List<Float>, rows: List<List<String>>) {
        val head = paint(9f, muted, bold = true)
        val cell = paint(9f, ink)
        fun drawHeader() {
            need(20f)
            var x = M
            headers.forEachIndexed { i, h -> canvas.drawText(h, x, y + 10f, head); x += widths[i] }
            y += 16f
            canvas.drawLine(M, y, W - M, y, paint(1f, line))
            y += 4f
        }
        drawHeader()
        rows.forEach { row ->
            if (y + 14f > H - 48f) {
                newPage()
                drawHeader()
            }
            var x = M
            row.forEachIndexed { i, c -> canvas.drawText(c, x, y + 10f, cell); x += widths[i] }
            y += 14f
        }
    }

    // ---- Sections -------------------------------------------------------------------------------

    private fun header() {
        text(s(R.string.report_title), paint(22f, ink, bold = true), gap = 6f)
        text(
            s(R.string.report_period, report.from.format(dateFormat), report.to.format(dateFormat)),
            paint(11f, muted),
        )
        text(s(R.string.report_created, LocalDate.now(zone).format(dateFormat)), paint(11f, muted), gap = 8f)
        paragraph(s(R.string.report_note), paint(9f, muted))
    }

    private fun glucose(stats: dev.ytosko.neutrino.domain.report.GlucoseStats) {
        section(s(R.string.report_glucose))
        keyValues(
            listOf(
                s(R.string.report_average) to "${g(stats.average)} ${unit.label}",
                s(R.string.report_readings) to stats.readings.toString(),
                s(R.string.report_target) to "${g(report.low)}–${g(report.high)} ${unit.label}",
                s(R.string.report_lowest) to "${g(stats.min)} ${unit.label}",
                s(R.string.report_highest) to "${g(stats.max)} ${unit.label}",
            ),
        )
        // Time in range as one stacked bar with its percentages.
        need(46f)
        text(s(R.string.health_glucose_time_in_range), paint(10f, muted))
        val barTop = y + 2f
        var x = M
        val width = W - 2 * M
        listOf(stats.belowShare to low, stats.inRangeShare to inRange, stats.aboveShare to high).forEach { (share, color) ->
            val w = (width * share).toFloat()
            if (w > 0f) canvas.drawRect(x, barTop, x + w, barTop + 10f, paint(1f, color))
            x += w
        }
        y = barTop + 24f
        canvas.drawText(
            s(
                R.string.report_tir_line,
                (stats.belowShare * 100).roundToInt(), (stats.inRangeShare * 100).roundToInt(), (stats.aboveShare * 100).roundToInt(),
            ),
            M, y, paint(10f, ink),
        )
        y += 10f
        if (stats.byRelation.isNotEmpty()) {
            text(s(R.string.health_glucose_by_meal), paint(10f, muted), gap = 2f)
            stats.byRelation.forEach { (rel, avg) -> text("${relation(rel)}: ${g(avg)} ${unit.label}", paint(10f, ink), gap = 2f) }
        }
    }

    /** Daily average with the day's lowest–highest, over the shaded target range. */
    private fun glucoseChart() {
        val days = report.days.filter { it.glucoseAverage != null }.sortedBy { it.date }
        need(190f)
        y += 10f
        text(s(R.string.report_daily_chart), paint(10f, muted))
        val top = y + 6f
        val chartH = 130f
        val left = M + 28f
        val right = W - M
        val maxV = maxOf(days.maxOf { it.glucoseMax!! }, report.high) * 1.1
        fun yOf(v: Double) = (top + chartH - (v / maxV * chartH)).toFloat()
        // Target band and axis labels.
        canvas.drawRect(RectF(left, yOf(report.high), right, yOf(report.low)), paint(1f, Color.argb(30, 15, 118, 110)))
        listOf(report.low, report.high).forEach { v ->
            canvas.drawText(g(v), M, yOf(v) + 3f, paint(8f, muted))
            canvas.drawLine(left, yOf(v), right, yOf(v), paint(0.5f, line))
        }
        canvas.drawLine(left, top + chartH, right, top + chartH, paint(1f, line))
        val slot = (right - left) / days.size.coerceAtLeast(1)
        val dot = paint(1f, ink)
        val whisker = paint(1f, muted).apply { strokeWidth = 1.2f }
        days.forEachIndexed { i, d ->
            val cx = left + slot * i + slot / 2
            canvas.drawLine(cx, yOf(d.glucoseMin!!), cx, yOf(d.glucoseMax!!), whisker)
            val avg = d.glucoseAverage!!
            dot.color = when {
                avg < report.low -> low
                avg > report.high -> high
                else -> inRange
            }
            canvas.drawCircle(cx, yOf(avg), 2.8f, dot)
        }
        // A few date labels so they don't crowd.
        val every = (days.size / 6).coerceAtLeast(1)
        days.forEachIndexed { i, d ->
            if (i % every == 0) canvas.drawText(d.date.format(DateTimeFormatter.ofPattern("d MMM")), left + slot * i, top + chartH + 12f, paint(8f, muted))
        }
        y = top + chartH + 22f
    }

    private fun food(stats: dev.ytosko.neutrino.domain.report.FoodStats) {
        section(s(R.string.report_food))
        keyValues(
            listOfNotNull(
                s(R.string.report_days_logged) to stats.daysLogged.toString(),
                s(R.string.report_carbs_day) to "${stats.carbsPerDay.roundToInt()} g",
                s(R.string.report_kcal_day) to "${stats.kcalPerDay.roundToInt()} kcal",
                s(R.string.report_protein_day) to "${stats.proteinPerDay.roundToInt()} g",
                s(R.string.report_fat_day) to "${stats.fatPerDay.roundToInt()} g",
                stats.waterPerDay?.let { s(R.string.report_water_day) to formatWater(it) },
            ),
        )
        paragraph(s(R.string.report_food_note), paint(9f, muted))
    }

    private fun dailyTable() {
        section(s(R.string.report_by_day))
        table(
            listOf(s(R.string.report_col_date), s(R.string.report_col_meals), s(R.string.report_col_carbs), s(R.string.report_col_kcal), s(R.string.report_col_water), s(R.string.report_col_glucose, unit.label), s(R.string.report_col_readings)),
            listOf(78f, 40f, 55f, 50f, 60f, 172f, 60f),
            report.days.map { d ->
                listOf(
                    d.date.format(shortDate),
                    if (d.meals > 0) d.meals.toString() else "–",
                    if (d.meals > 0) "${d.carbsG.roundToInt()} g" else "–",
                    if (d.meals > 0) d.kcal.roundToInt().toString() else "–",
                    if (d.waterMl > 0) formatWater(d.waterMl) else "–",
                    d.glucoseAverage?.let { "${g(it)}  (${g(d.glucoseMin!!)}–${g(d.glucoseMax!!)})" } ?: "–",
                    if (d.readings > 0) d.readings.toString() else "–",
                )
            },
        )
    }

    private fun readingsTable() {
        section(s(R.string.report_all_readings))
        table(
            listOf(s(R.string.report_col_date), s(R.string.report_col_time), s(R.string.report_col_value, unit.label), s(R.string.report_col_meal), s(R.string.report_col_source)),
            listOf(100f, 70f, 90f, 110f, 90f),
            report.readings.map { r ->
                val t = r.at.atZone(zone)
                listOf(
                    t.toLocalDate().format(shortDate),
                    t.format(timeFormat),
                    g(r.mmolPerL),
                    relation(r.relation),
                    s(if (r.fromMeter) R.string.report_source_meter else R.string.report_source_typed),
                )
            },
        )
    }

    private fun relation(r: GlucoseRelation) = s(
        when (r) {
            GlucoseRelation.General -> R.string.glucose_general
            GlucoseRelation.Fasting -> R.string.glucose_fasting
            GlucoseRelation.BeforeMeal -> R.string.glucose_before_meal
            GlucoseRelation.AfterMeal -> R.string.glucose_after_meal
            GlucoseRelation.Bedtime -> R.string.glucose_bedtime
        },
    )

    private companion object {
        /** A4 in PDF points. */
        const val W = 595
        const val H = 842
        const val M = 40f
    }
}
