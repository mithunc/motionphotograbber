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

import xyz.mithunc.motionphotograbber.motionphoto.Flavor

/** Why a file could not be turned into something scrubbable. */
sealed interface LoadError {

    /** A readable JPEG with no embedded video. */
    data object NotAMotionPhoto : LoadError

    /** A motion photo of a flavor this build does not parse yet. */
    data class UnsupportedFlavor(val flavor: Flavor) : LoadError

    /** Claims to be a motion photo but its structure does not hold together. */
    data class Malformed(val reason: String) : LoadError

    /** The file could not be read at all — a bad Uri, a revoked grant, a full disk. */
    data class Unreadable(val reason: String) : LoadError

    /** The embedded clip was located but could not be decoded. */
    data class DecodeFailed(val reason: String) : LoadError
}

/** Everything the one screen renders from. */
sealed interface UiState {

    /** Launched with no file. The screen offers a picker. */
    data object Idle : UiState

    /** A file is being copied, parsed, and opened. */
    data object Loading : UiState

    /**
     * A clip is on the surface and can be scrubbed.
     *
     * Carries no bitmap: the preview lives on the player's surface, not in state.
     *
     * [atDefaultFrame] decides which of the two save paths runs, so it is derived once
     * here rather than recomputed at save time from a float comparison.
     */
    data class Ready(
        val durationMs: Long,
        val positionMs: Long,
        /** Null when the container declares no shutter-press timestamp. */
        val defaultFramePositionMs: Long?,
        val atDefaultFrame: Boolean,
        /**
         * Width over height of the clip, or null until the decoder reports it.
         *
         * The preview surface has to be sized to this. A `SurfaceView` given no shape
         * stretches its content to whatever bounds it is handed, which distorted every
         * preview until it was measured against the true frame.
         */
        val previewAspectRatio: Float? = null,
        val saving: Boolean = false,
    ) : UiState

    data class Failed(val error: LoadError) : UiState
}
