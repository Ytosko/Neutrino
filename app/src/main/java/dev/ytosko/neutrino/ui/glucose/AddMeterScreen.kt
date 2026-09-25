package dev.ytosko.neutrino.ui.glucose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.MeterModel
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.Spacing

/** Tap + → pick which meter you have; the next page pairs it. */
@Composable
fun AddMeterScreen(onBack: () -> Unit, onPick: (MeterModel) -> Unit) {
    SetupScaffold(
        title = stringResource(R.string.meters_choose_title),
        subtitle = stringResource(R.string.meters_choose_body),
        onBack = onBack,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            MeterModel.entries.forEach { model ->
                Card(
                    onClick = { onPick(model) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = CardDefaults.outlinedCardBorder(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        MeterPicture(model, size = 88.dp)
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.meters_bluetooth),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
