package dev.ytosko.neutrino.ui.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.export.DataExport
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Period(val days: Int, val label: Int) {
    TwoWeeks(14, R.string.export_two_weeks),
    Month(30, R.string.export_month),
    ThreeMonths(90, R.string.export_three_months),
}

private enum class Kind(val mime: String) { Report("application/pdf"), Data("application/zip") }

/** Settings → Reports and export: a PDF for the doctor, or everything as spreadsheet files. */
@Composable
fun ExportScreen(export: DataExport, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var period by rememberSaveable { mutableStateOf(Period.TwoWeeks) }
    var working by remember { mutableStateOf<Kind?>(null) }
    var pendingSave by remember { mutableStateOf<Uri?>(null) }
    val saved = stringResource(R.string.export_saved)
    val failed = stringResource(R.string.export_failed)
    val nothing = stringResource(R.string.report_empty)

    val saveReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Kind.Report.mime)) { target ->
        val source = pendingSave
        pendingSave = null
        if (target != null && source != null) scope.launch {
            runCatching { export.copy(source, target) }.onSuccess { snackbar.showSnackbar(saved) }.onFailure { snackbar.showSnackbar(failed) }
        }
    }
    val saveData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Kind.Data.mime)) { target ->
        val source = pendingSave
        pendingSave = null
        if (target != null && source != null) scope.launch {
            runCatching { export.copy(source, target) }.onSuccess { snackbar.showSnackbar(saved) }.onFailure { snackbar.showSnackbar(failed) }
        }
    }

    fun share(uri: Uri, kind: Kind) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(kind.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        context.startActivity(Intent.createChooser(send, null))
    }

    /** Builds the file, then shares it or asks where to save it. */
    fun make(kind: Kind, andShare: Boolean) {
        if (working != null) return
        working = kind
        scope.launch {
            val uri = runCatching {
                when (kind) {
                    Kind.Report -> {
                        val report = export.report(period.days)
                        if (report.isEmpty) null else export.reportPdf(report)
                    }
                    Kind.Data -> export.csvZip()
                }
            }
            working = null
            val file = uri.getOrNull()
            when {
                uri.isFailure -> snackbar.showSnackbar(failed)
                file == null -> snackbar.showSnackbar(nothing)
                andShare -> share(file, kind)
                else -> {
                    pendingSave = file
                    val today = LocalDate.now()
                    if (kind == Kind.Report) saveReport.launch("neutrino-report-$today.pdf") else saveData.launch("neutrino-data-$today.zip")
                }
            }
        }
    }

    SetupScaffold(
        title = stringResource(R.string.export_title),
        subtitle = stringResource(R.string.export_body),
        onBack = onBack,
        bottomBar = { SnackbarHost(snackbar) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            ExportCard(
                icon = R.drawable.ic_heart_pulse,
                title = stringResource(R.string.export_report_title),
                body = stringResource(R.string.export_report_body),
                working = working == Kind.Report,
                enabled = working == null,
                onShare = { make(Kind.Report, andShare = true) },
                onSave = { make(Kind.Report, andShare = false) },
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    Period.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = period == p,
                            onClick = { period = p },
                            shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                        ) { Text(stringResource(p.label), maxLines = 1) }
                    }
                }
            }
            ExportCard(
                icon = R.drawable.ic_archive,
                title = stringResource(R.string.export_data_title),
                body = stringResource(R.string.export_data_body),
                working = working == Kind.Data,
                enabled = working == null,
                onShare = { make(Kind.Data, andShare = true) },
                onSave = { make(Kind.Data, andShare = false) },
            )
            Text(
                stringResource(R.string.export_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExportCard(
    icon: Int,
    title: String,
    body: String,
    working: Boolean,
    enabled: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IconBadge(icon, size = 44.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra()
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_external_link), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.xs))
                        Text(stringResource(R.string.export_share))
                    }
                }
                OutlinedButton(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.export_save))
                }
            }
        }
    }
}
