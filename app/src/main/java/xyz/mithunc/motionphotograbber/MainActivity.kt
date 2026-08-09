/*
 * Copyright 2026 Mithun Chaubey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.mithunc.motionphotograbber

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import xyz.mithunc.motionphotograbber.ui.GrabberScreen

/**
 * Single entry point, reached three ways: the launcher, a share, and the file picker.
 *
 * Everything stateful lives in [GrabberViewModel]; this is intent plumbing and theming.
 */
@UnstableApi
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = sharedImageUri(intent)
        setContent {
            MaterialTheme {
                GrabberApp(initialUri = shared)
            }
        }
    }

    /** The image a share sheet handed us, or null when launched without one. */
    private fun sharedImageUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
}

@UnstableApi
@Composable
private fun GrabberApp(initialUri: Uri?) {
    val viewModel: GrabberViewModel = viewModel()
    // collectAsState, not collectAsStateWithLifecycle: the latter lives in
    // lifecycle-runtime-compose, and this screen has nothing worth pausing collection for.
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // A rotation destroys this composition and runs the effect again with the same Uri,
    // so the ViewModel — not the effect key — is what makes the load happen once.
    LaunchedEffect(initialUri) {
        if (initialUri != null) viewModel.loadIfNeeded(initialUri)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.load(uri) }

    val appName = stringResource(R.string.app_name)
    // SPEC.md requires the Software tag to name the producing app and its version, and
    // :core-exif deliberately takes it as a parameter because neither belongs there.
    val editingSoftware = remember(appName) { "$appName ${BuildConfig.VERSION_NAME}" }

    // The system permission dialog cannot carry custom text, so the reason has to be ours
    // and has to come first. Requested only when a save actually needs it.
    var showLocationRationale by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocationPermissionResult(granted) }

    val savedTemplate = stringResource(R.string.saved)
    val failedTemplate = stringResource(R.string.save_failed)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GrabberEvent.NeedsLocationPermission -> showLocationRationale = true
                is GrabberEvent.SaveFinished -> snackbarHostState.showSnackbar(
                    when (val result = event.result) {
                        is SaveResult.Saved -> savedTemplate.format(result.displayName)
                        is SaveResult.Failed -> failedTemplate.format(result.reason)
                    }
                )
            }
        }
    }

    if (showLocationRationale) {
        AlertDialog(
            onDismissRequest = {
                // Dismissing is a decision too: proceed without location rather than leaving
                // the save silently abandoned.
                showLocationRationale = false
                viewModel.onLocationPermissionResult(granted = false)
            },
            title = { Text(stringResource(R.string.location_title)) },
            text = { Text(stringResource(R.string.location_rationale)) },
            confirmButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }) { Text(stringResource(R.string.location_allow)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLocationRationale = false
                    viewModel.onLocationPermissionResult(granted = false)
                }) { Text(stringResource(R.string.location_skip)) }
            },
        )
    }

    GrabberScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        // image/jpeg only, matching the share filter: this app can do nothing with a PNG
        // and offering one in the picker would only produce an error.
        onPickFile = { picker.launch(arrayOf("image/jpeg")) },
        onScrub = viewModel::onScrub,
        onSave = { viewModel.save(editingSoftware) },
        onSurfaceCreated = viewModel::attachSurface,
        onSurfaceDisposed = viewModel::detachSurface,
    )
}
