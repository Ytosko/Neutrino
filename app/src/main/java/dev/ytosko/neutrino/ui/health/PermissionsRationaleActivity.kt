package dev.ytosko.neutrino.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

/**
 * Required by Health Connect: explains how Neutrino uses health data. Opened from the
 * permission dialog (Android 13 and lower) or from system settings (Android 14+).
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeutrinoTheme {
                val uriHandler = LocalUriHandler.current
                val privacyUrl = stringResource(R.string.url_privacy)
                SetupScaffold(
                    title = stringResource(R.string.rationale_title),
                    subtitle = stringResource(R.string.rationale_body),
                    onBack = ::finish,
                    bottomBar = {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Button(
                                onClick = { uriHandler.openUri(privacyUrl) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            ) { Text(stringResource(R.string.rationale_policy)) }
                            OutlinedButton(
                                onClick = ::finish,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.rationale_close)) }
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text(stringResource(R.string.hc_writes_nutrition), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.hc_writes_hydration), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.hc_reads_nothing),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}
