package dev.ytosko.neutrino.ui.backup

import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.IosAlert
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ytosko.neutrino.R

/**
 * Runs an action that needs the phone backup folder, asking for access first when needed:
 * a short explanation, then "All files access" in system settings (Android 11+) or the storage
 * permission (Android 9–10). [hasAccess] is checked again when the user comes back.
 */
@Composable
fun rememberStorageAccess(hasAccess: () -> Boolean, settingsIntent: () -> Intent, onChecked: () -> Unit = {}): (onGranted: () -> Unit) -> Unit {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var explaining by remember { mutableStateOf(false) }
    val currentHasAccess by rememberUpdatedState(hasAccess)
    val currentOnChecked by rememberUpdatedState(onChecked)

    fun finish() {
        currentOnChecked()
        val action = pending
        pending = null
        if (currentHasAccess()) action?.invoke()
    }

    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    if (explaining) {
        IosAlert(
            title = stringResource(R.string.storage_access_title),
            message = stringResource(R.string.storage_access_body),
            buttons = listOf(
                AlertButton(stringResource(R.string.backup_cancel)) {
                    explaining = false
                    pending = null
                },
                AlertButton(stringResource(R.string.storage_access_continue), AlertStyle.Cancel) {
                    explaining = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            settings.launch(settingsIntent())
                        } catch (_: ActivityNotFoundException) {
                            // Some phones only have the general list of apps with this access.
                            settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    } else {
                        permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
            ),
            onDismiss = {
                explaining = false
                pending = null
            },
        )
    }

    return { onGranted ->
        if (hasAccess()) {
            onGranted()
        } else {
            pending = onGranted
            explaining = true
        }
    }
}
