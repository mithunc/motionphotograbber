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
package xyz.mithunc.motionphotograbber.ui

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import xyz.mithunc.motionphotograbber.R

/**
 * The whole app: a frame, a scrubber, and a save button.
 *
 * Stateless. The preview is a [SurfaceView] the player draws into rather than a bitmap in
 * state, so scrubbing does not go through recomposition at all — [onSurfaceCreated] hands
 * the view over once and frames arrive on it directly afterwards.
 */
@Composable
fun GrabberScreen(
    state: UiState,
    snackbarHostState: SnackbarHostState,
    onPickFile: () -> Unit,
    onScrub: (Long) -> Unit,
    onSave: () -> Unit,
    onSurfaceCreated: (SurfaceView) -> Unit,
    onSurfaceDisposed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .background(Color.Black),
        ) {
            when (state) {
                is UiState.Idle -> Placeholder(
                    message = stringResource(R.string.idle_prompt),
                    actionLabel = stringResource(R.string.open_photo),
                    onAction = onPickFile,
                )

                is UiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is UiState.Failed -> Placeholder(
                    message = state.error.message(),
                    actionLabel = stringResource(R.string.open_another_photo),
                    onAction = onPickFile,
                )

                is UiState.Ready -> ScrubbableFrame(
                    state = state,
                    onScrub = onScrub,
                    onSave = onSave,
                    onSurfaceCreated = onSurfaceCreated,
                    onSurfaceDisposed = onSurfaceDisposed,
                )
            }
        }
    }
}

@Composable
private fun ScrubbableFrame(
    state: UiState.Ready,
    onScrub: (Long) -> Unit,
    onSave: () -> Unit,
    onSurfaceCreated: (SurfaceView) -> Unit,
    onSurfaceDisposed: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                factory = { context -> SurfaceView(context).also(onSurfaceCreated) },
                modifier = Modifier
                    .fitInside(state.previewAspectRatio, maxWidth / maxHeight)
                    .align(Alignment.Center),
            )
            DisposableEffect(Unit) {
                onDispose(onSurfaceDisposed)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Scrubber(state = state, onScrub = onScrub)
            Button(onClick = onSave, enabled = !state.saving) {
                Text(stringResource(if (state.saving) R.string.saving else R.string.save))
            }
        }
    }
}

// The Slider `track` slot and SliderDefaults.Track are still experimental in Material3.
// Opted in here only, so the rest of the screen stays on stable API: drawing the detent
// needs the slot, and an overlay positioned by hand would have to guess the thumb inset.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scrubber(state: UiState.Ready, onScrub: (Long) -> Unit) {
    val range = state.durationMs.coerceAtLeast(1L).toFloat()
    Slider(
        value = state.positionMs.toFloat(),
        onValueChange = { onScrub(it.toLong()) },
        valueRange = 0f..range,
        enabled = !state.saving,
        track = { sliderState ->
            Box {
                SliderDefaults.Track(sliderState = sliderState)
                // A detent needs to be visible or it just feels like the slider sticking.
                // Deliberately unlabeled: SPEC.md's 2026-07-20 decision is that the app
                // says nothing about the resolution or HDR difference between this frame
                // and the rest, and a caption here would be exactly that.
                state.defaultFramePositionMs?.let { default ->
                    val fraction = (default / range).coerceIn(0f, 1f)
                    Canvas(Modifier.matchParentSize()) {
                        val x = size.width * fraction
                        drawLine(
                            color = Color.White,
                            start = Offset(x, size.height * 0.1f),
                            end = Offset(x, size.height * 0.9f),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Constrains the preview to [videoRatio], letterboxing rather than filling.
 *
 * A `SurfaceView` scales its content to its own bounds, so whatever shape this modifier
 * produces *is* the shape the video is drawn in — nothing downstream corrects it. Binding the
 * dimension that runs out first is what keeps a portrait clip upright in landscape and a
 * landscape clip un-squashed in portrait.
 *
 * Falls back to filling while [videoRatio] is null, which lasts only until the first frame
 * decodes; a letterbox guessed before the shape is known would visibly resize afterwards.
 */
private fun Modifier.fitInside(videoRatio: Float?, boxRatio: Float): Modifier = when {
    videoRatio == null || videoRatio <= 0f -> fillMaxSize()
    // Box is proportionally wider than the video, so height is the binding constraint.
    boxRatio > videoRatio -> fillMaxHeight().aspectRatio(videoRatio)
    else -> fillMaxWidth().aspectRatio(videoRatio)
}

@Composable
private fun BoxScope.Placeholder(message: String, actionLabel: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun LoadError.message(): String = when (this) {
    is LoadError.NotAMotionPhoto -> stringResource(R.string.error_not_a_motion_photo)
    is LoadError.UnsupportedFlavor -> stringResource(R.string.error_unsupported_flavor)
    is LoadError.Malformed -> stringResource(R.string.error_malformed, reason)
    is LoadError.Unreadable -> stringResource(R.string.error_unreadable, reason)
    is LoadError.DecodeFailed -> stringResource(R.string.error_decode_failed, reason)
}
