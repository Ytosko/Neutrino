package dev.ytosko.neutrino.ui.components

import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.ui.theme.Spacing

/**
 * Layout for setup and settings sub-screens: one compact header row (back, title, actions), optional
 * step progress, scrollable content and a bottom action area that stays above the keyboard.
 */
@Composable
fun SetupScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    step: Pair<Int, Int>? = null,
    subtitle: String? = null,
    /** Buttons pinned to the bottom; null (most settings pages) means no bar at all. */
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // 0 at the top, 1 once scrolled a little: the title shrinks and the bar gets its hairline.
    val collapse = (scroll.value / with(density) { 56.dp.toPx() }).coerceIn(0f, 1f)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // The page scrolls under the gesture bar instead of stopping above an empty strip;
        // the list's own bottom padding keeps its last row clear of it.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            // Back, title and actions share one row, with no empty bar above the title.
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .heightIn(min = 64.dp)
                        .padding(start = if (onBack != null) 4.dp else Spacing.gutter, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.action_back))
                        }
                    }
                    Text(
                        title,
                        style = lerp(MaterialTheme.typography.headlineMedium, MaterialTheme.typography.titleLarge, collapse),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = Spacing.xs).semantics { heading() },
                    )
                    actions()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = collapse))
                }
            }
        },
        bottomBar = {
            if (bottomBar != null) Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = bottomBar,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .then(
                        if (bottomBar == null) {
                            Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom))
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.lg),
            ) {
                if (step != null) {
                    Text(
                        stringResource(R.string.setup_step, step.first, step.second),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { step.first / step.second.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs, bottom = Spacing.md),
                    )
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.padding(top = if (subtitle != null) Spacing.lg else Spacing.xs), content = content)
            }
        }
    }
}
