package dev.ytosko.neutrino.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.voice.VoiceNotSetUp
import dev.ytosko.neutrino.ui.ai.aiErrorMessage
import dev.ytosko.neutrino.ui.glucose.GlucoseEditDialog
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId

/** How long a press has to last to count as "hold to talk" rather than a tap. */
private const val HOLD_MS = 450L

/** Runs [onGranted] once the microphone may be used, asking the first time. */
@Composable
fun rememberMicAccess(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val denied = stringResource(R.string.voice_mic_denied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted() else Toast.makeText(context, denied, Toast.LENGTH_LONG).show()
    }
    return remember(context) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                granted()
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

/**
 * The mic. Tap to start and tap again to stop (it also stops when the user goes quiet), or press
 * and hold while talking and let go to stop. It pulses with the voice while listening.
 */
@Composable
fun MicButton(controller: VoiceController, size: Dp, modifier: Modifier = Modifier) {
    val phase by controller.phase.collectAsState()
    val level by controller.level.collectAsState()
    val current by rememberUpdatedState(phase)
    val start = rememberMicAccess { controller.start(stopOnSilence = true) }
    val listening = phase == VoicePhase.Listening
    val thinking = phase == VoicePhase.Thinking

    val breathing = rememberInfiniteTransition(label = "breathing")
    val breath by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breath",
    )
    val loud by animateFloatAsState(if (listening) level else 0f, spring(stiffness = 400f), label = "level")
    val ring = if (listening) 1f + maxOf(breath, loud * 0.45f) else 1f

    val label = stringResource(if (listening) R.string.voice_stop else R.string.voice_start)
    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    if (current == VoicePhase.Listening) controller.stop() else if (current != VoicePhase.Thinking) start()
                    true
                }
            }
            .pointerInput(controller) {
                awaitEachGesture {
                    awaitFirstDown()
                    when (current) {
                        VoicePhase.Listening -> {
                            waitForUpOrCancellation()
                            controller.stop()
                        }
                        VoicePhase.Thinking -> waitForUpOrCancellation()
                        else -> {
                            start()
                            var released = false
                            withTimeoutOrNull(HOLD_MS) {
                                waitForUpOrCancellation()
                                released = true
                            }
                            if (!released) {
                                // Held: keep listening through pauses; letting go stops.
                                controller.holding()
                                waitForUpOrCancellation()
                                controller.stop()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = ring
                    scaleY = ring
                    alpha = if (listening) 0.28f else 0f
                }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Surface(
            shape = CircleShape,
            color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 3.dp,
            modifier = Modifier.size(size * 0.78f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    thinking -> CircularProgressIndicator(modifier = Modifier.size(size * 0.36f), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    listening -> Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(size * 0.28f))
                    else -> Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(size * 0.36f))
                }
            }
        }
    }
}

/** Log by voice: listens as soon as it opens, then acts on what was said. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogByVoiceSheet(controller: VoiceController, onDismiss: () -> Unit) {
    val phase by controller.phase.collectAsState()
    val start = rememberMicAccess { controller.start(stopOnSilence = true) }
    LaunchedEffect(Unit) { start() }

    ModalBottomSheet(
        onDismissRequest = {
            controller.cancel()
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.voice_log_title), style = MaterialTheme.typography.titleLarge)
            MicButton(controller, size = 132.dp, modifier = Modifier.padding(vertical = Spacing.sm))
            AnimatedContent(targetState = phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") { shown ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                ) {
                    val (title, body) = statusText(shown)
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (shown is VoicePhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusText(phase: VoicePhase): Pair<String, String> = when (phase) {
    VoicePhase.Listening -> stringResource(R.string.voice_listening) to stringResource(R.string.voice_listening_hint)
    VoicePhase.Thinking -> stringResource(R.string.voice_thinking) to ""
    VoicePhase.Missed -> stringResource(R.string.voice_missed_title) to stringResource(R.string.voice_try_again_hint)
    is VoicePhase.Failed -> stringResource(R.string.voice_failed_title) to voiceErrorMessage(phase.error)
    VoicePhase.Idle -> stringResource(R.string.voice_idle_title) to stringResource(R.string.voice_log_example)
}

@Composable
fun voiceErrorMessage(error: Exception): String = when (error) {
    is AiException -> aiErrorMessage(error)
    is VoiceNotSetUp -> stringResource(R.string.voice_not_set_up)
    else -> stringResource(R.string.ai_error_no_result)
}

/**
 * The review page's mic, bottom right. While it works, a small label beside it says so; the answer
 * is spoken, and the changed lines light up for a moment.
 */
@Composable
fun ReviewVoiceMic(controller: VoiceController, modifier: Modifier = Modifier) {
    val phase by controller.phase.collectAsState()
    val context = LocalContext.current
    val failed = (phase as? VoicePhase.Failed)?.let { voiceErrorMessage(it.error) }
    LaunchedEffect(failed) {
        if (failed != null) {
            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            controller.reset()
        }
    }
    LaunchedEffect(phase) { if (phase == VoicePhase.Missed) controller.reset() }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val label = when (phase) {
            VoicePhase.Listening -> stringResource(R.string.voice_listening)
            VoicePhase.Thinking -> stringResource(R.string.voice_thinking)
            else -> null
        }
        AnimatedContent(targetState = label, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "micLabel") { text ->
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = Spacing.sm, vertical = 6.dp),
                )
            }
        }
        MicButton(controller, size = 72.dp)
    }
}

/** The glucose confirmation and "which medicine?" pop-ups a voice turn can ask for. */
@Composable
fun VoiceDialogs(controller: VoiceController, glucoseRange: ClosedFloatingPointRange<Double>) {
    val glucose by controller.pendingGlucose.collectAsState()
    val choice by controller.medicineChoice.collectAsState()
    val zone = remember { ZoneId.systemDefault() }

    glucose?.let { spoken ->
        GlucoseEditDialog(
            reading = null,
            range = glucoseRange,
            newReadingTime = spoken.time?.atZone(zone)?.toInstant() ?: Instant.now(),
            initialMmol = spoken.mmolPerL,
            initialRelation = GlucoseRelation.valueOf(spoken.relation.name),
            onSave = { mmol, relation, time, link ->
                if (mmol != null) controller.saveGlucose(mmol, relation, time, link) else controller.dismissGlucose()
            },
            onDelete = {},
            onDismiss = controller::dismissGlucose,
        )
    }

    if (glucose == null) {
        choice?.let { pick ->
            AlertDialog(
                onDismissRequest = { controller.chooseMedicine(null) },
                title = { Text(stringResource(R.string.voice_which_medicine, pick.spoken.name)) },
                text = {
                    Column {
                        pick.options.forEach { medicine ->
                            Text(
                                medicine.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { controller.chooseMedicine(medicine) }
                                    .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { controller.chooseMedicine(null) }) { Text(stringResource(R.string.home_cancel)) } },
            )
        }
    }
}
