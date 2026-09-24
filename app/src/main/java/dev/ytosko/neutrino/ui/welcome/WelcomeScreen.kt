package dev.ytosko.neutrino.ui.welcome

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.ui.components.FeatureRow
import dev.ytosko.neutrino.ui.components.NeutrinoLogo
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit, onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                WelcomeHero()
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    Text(
                        text = stringResource(R.string.welcome_body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FeatureRow(
                        icon = R.drawable.ic_key,
                        title = stringResource(R.string.welcome_point_ai_title),
                        body = stringResource(R.string.welcome_point_ai_body),
                    )
                    FeatureRow(
                        icon = R.drawable.ic_pencil,
                        title = stringResource(R.string.welcome_point_review_title),
                        body = stringResource(R.string.welcome_point_review_body),
                    )
                    FeatureRow(
                        icon = R.drawable.ic_shield_check,
                        title = stringResource(R.string.welcome_point_privacy_title),
                        body = stringResource(R.string.welcome_point_privacy_body),
                    )
                }
            }
            WelcomeFooter(onGetStarted = onGetStarted, onRestore = onRestore)
        }
    }
}

@Composable
private fun WelcomeHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NeutrinoTheme.colors.brand,
                shape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Spacing.gutter)
            .padding(top = Spacing.xl, bottom = Spacing.xl),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.18f),
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_neutrino_mark),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(54.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = NeutrinoTheme.colors.onBrand,
            )
            Text(
                text = stringResource(R.string.welcome_tagline),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .semantics { heading() },
            )
        }
    }
}

@Composable
private fun WelcomeFooter(onGetStarted: () -> Unit, onRestore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.welcome_get_started), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(Spacing.xs))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_right),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.welcome_restore))
            }
            LegalText()
        }
    }
}

@Composable
private fun LegalText(modifier: Modifier = Modifier) {
    val terms = stringResource(R.string.url_terms)
    val privacy = stringResource(R.string.url_privacy)
    val full = stringResource(R.string.welcome_legal)
    val linkStyle = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
        ),
    )
    val text = buildAnnotatedString {
        var cursor = 0
        listOf("Terms of Use" to terms, "Privacy Policy" to privacy)
            .mapNotNull { (label, url) -> full.indexOf(label).takeIf { it >= 0 }?.let { Triple(it, label, url) } }
            .sortedBy { it.first }
            .forEach { (start, label, url) ->
                append(full.substring(cursor, start))
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
                cursor = start + label.length
            }
        append(full.substring(cursor))
        append("\n")
        append(stringResource(R.string.welcome_disclaimer))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun WelcomePreview() {
    NeutrinoTheme { WelcomeScreen(onGetStarted = {}, onRestore = {}) }
}
