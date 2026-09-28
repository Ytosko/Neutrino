package dev.ytosko.neutrino.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.ytosko.neutrino.data.meal.PhotoProcessor
import java.io.File

/** Opens the camera for one photo, or the photo picker for several (up to [PhotoProcessor.MAX_PHOTOS]). */
class PhotoPickers(val takePhoto: () -> Unit, val choose: (max: Int) -> Unit)

/**
 * The camera and the photo picker, shared by Home and the review page. [onPicked] gets the photos
 * and whether they came from the camera (its temporary file is deleted once read). [onNoCamera]
 * runs when the phone has no camera app.
 */
@Composable
fun rememberPhotoPickers(onPicked: (List<Uri>, fromCamera: Boolean) -> Unit, onNoCamera: () -> Unit): PhotoPickers {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val noCamera by rememberUpdatedState(onNoCamera)
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var allowed by remember { mutableStateOf(PhotoProcessor.MAX_PHOTOS) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCapture?.let(Uri::parse)
        pendingCapture = null
        if (success && uri != null) picked(listOf(uri), true)
    }
    val several = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoProcessor.MAX_PHOTOS)) { uris ->
        if (uris.isNotEmpty()) picked(uris.take(allowed), false)
    }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked(listOf(uri), false)
    }
    return remember(context) {
        PhotoPickers(
            takePhoto = {
                val uri = newCaptureUri(context)
                pendingCapture = uri.toString()
                try {
                    camera.launch(uri)
                } catch (_: ActivityNotFoundException) {
                    noCamera()
                }
            },
            choose = { max ->
                allowed = max.coerceIn(1, PhotoProcessor.MAX_PHOTOS)
                val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                // The multi-picker needs room for at least two.
                if (allowed == 1) one.launch(request) else several.launch(request)
            },
        )
    }
}

/** A fresh file in the app cache for the camera app to write into. */
fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "meal-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
