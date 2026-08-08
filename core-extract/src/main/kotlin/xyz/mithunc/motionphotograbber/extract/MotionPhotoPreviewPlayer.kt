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
package xyz.mithunc.motionphotograbber.extract

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What the preview player can currently do. */
sealed interface PreviewState {

    /** Opening the clip. No frame has reached the surface yet. */
    data object Preparing : PreviewState

    /** A frame is on the surface and [durationMs] is known. */
    data class Ready(val durationMs: Long) : PreviewState

    /** The clip could not be opened or decoded. */
    data class Failed(val reason: String) : PreviewState
}

/**
 * Renders the embedded clip to a surface so the user can scrub it.
 *
 * **Why a player rather than repeated frame extraction.** Scrubbing wants a new image
 * under the finger continuously, and it wants stale requests abandoned the instant the
 * finger moves. [MotionPhotoFrameExtractor] gives neither: it documents nothing about
 * concurrent `getFrame` calls, and cancelling one only calls `cancel(false)` on the
 * future — "do not interrupt" — so a superseded decode runs to completion anyway.
 * ExoPlayer preempts a pending seek natively and renders straight to a hardware surface,
 * which is also how the stock galleries do this. [MotionPhotoFrameExtractor] keeps the
 * job it is good at: one exact, full-resolution frame when the user saves.
 *
 * **Seeking is exact, and must stay that way.** `SeekParameters.CLOSEST_SYNC` looks like
 * the obvious scrub optimization and is a trap here — these clips carry very few sync
 * samples (measured 2026-07-26: 11 of 75, 8 of 50, and **3 of 20**), so nearest-keyframe
 * seeking would show three distinct images across a whole 1.27 s clip. An exact seek
 * decodes at most ~7 frames of 1440x1080 HEVC from the preceding keyframe, which is well
 * inside a frame interval on any device that shot the file.
 *
 * Not thread-safe: create it, call it, and close it on one thread with a Looper, which in
 * practice means the main thread.
 */
@UnstableApi
class MotionPhotoPreviewPlayer private constructor(
    private val player: ExoPlayer,
) : AutoCloseable {

    companion object {
        /**
         * How long the pump waits for a seek to settle before moving on.
         *
         * A liveness guard, not a pacing knob — but its size is what the user feels when it
         * does fire. Measured on a Pixel 10 Pro XL 2026-08-08: an exact seek that settles
         * takes **46-100 ms** (median 72), so 250 ms leaves ample margin for a slower device
         * while capping a missed signal at a hitch rather than the half-second stall the
         * original 500 ms produced.
         */
        private const val SETTLE_TIMEOUT_MS = 250L

        /** Enable with `adb shell setprop log.tag.MotionPhotoSeek VERBOSE`. */
        private const val SEEK_LOG_TAG = "MotionPhotoSeek"

        /**
         * @param videoByteRange the range from [xyz.mithunc.motionphotograbber.motionphoto.MotionPhoto.Found],
         *   inclusive at both ends.
         */
        fun create(
            context: Context,
            sourceFile: File,
            videoByteRange: LongRange,
        ): MotionPhotoPreviewPlayer {
            // The range is inclusive at both ends, so the length is one more than the span.
            val dataSourceFactory = SubrangeDataSource.Factory(
                upstreamFactory = FileDataSource.Factory(),
                rangeStart = videoByteRange.first,
                rangeLength = videoByteRange.last - videoByteRange.first + 1,
            )
            val source = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(sourceFile)))

            val player = ExoPlayer.Builder(context).build().apply {
                setSeekParameters(SeekParameters.EXACT)
                // Never plays, so this is belt and braces — but a clip with an audio
                // track must not make noise if that ever changes.
                volume = 0f
                playWhenReady = false
                setMediaSource(source)
            }
            return MotionPhotoPreviewPlayer(player).also { it.start() }
        }
    }

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Preparing)
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    /**
     * Where the player actually landed after the last seek.
     *
     * This, not the position the caller asked for, is what a save should extract. The
     * request is a millisecond value; what the user is looking at is the frame the
     * decoder chose for it, and the two need not be the same frame.
     */
    val settledPositionMs: Long
        get() = player.currentPosition.coerceAtLeast(0L)

    private val _aspectRatio = MutableStateFlow<Float?>(null)

    /**
     * Width over height of the frames being rendered, or null until the first one arrives.
     *
     * A bare `SurfaceView` scales whatever it is given to fill its own bounds, so without
     * this the preview is silently distorted by however far the view's shape differs from
     * the video's. Media3 reports the shape; nothing else has to be inferred.
     */
    val aspectRatio: StateFlow<Float?> = _aspectRatio.asStateFlow()

    /** The position the caller most recently asked for. Latest value wins; see [pump]. */
    private val requestedPositionMs = MutableStateFlow<Long?>(null)

    /**
     * Signals that a seek resolved. Conflated because only the most recent signal matters
     * and the pump must never block on a backlog of them.
     *
     * Fed from two callbacks, because neither is sufficient alone. `onRenderedFirstFrame`
     * means "a *new* frame was painted", which a seek resolving to the frame already on
     * screen legitimately never does — measured 2026-08-08, 9 of 39 seeks during a fast drag
     * produced no such callback, and none arrived late either. `STATE_READY` covers those:
     * the seek is finished whether or not the picture changed.
     */
    private val seekSettled = Channel<Unit>(Channel.CONFLATED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                // duration is C.TIME_UNSET until the source has been read.
                val duration = player.duration
                if (duration > 0L) _state.value = PreviewState.Ready(duration)
                seekSettled.trySend(Unit)
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _aspectRatio.value = aspectRatioOf(videoSize)
        }

        override fun onRenderedFirstFrame() {
            seekSettled.trySend(Unit)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PreviewState.Failed(error.message ?: error.errorCodeName)
        }
    }

    private fun start() {
        player.addListener(listener)
        player.prepare()
        scope.launch { pump() }
    }

    /**
     * Turns a stream of requested positions into at most one decode at a time.
     *
     * **Why this is necessary rather than an optimization.** Firing `seekTo` on every slider
     * callback looks right because ExoPlayer preempts a seek still in flight — but preempting
     * *discards* that decode rather than accelerating it. At ~60-120 callbacks a second, and
     * with exact seeking needing up to ~7 frames decoded from the preceding keyframe, every
     * seek was being killed by its successor and nothing reached the screen until the finger
     * stopped. Measured on device 2026-07-26 before this pump existed.
     *
     * [requestedPositionMs] is a `StateFlow`, so a collector that is slow — and awaiting a
     * decode makes this one slow by design — skips every intermediate position and resumes at
     * the newest. No queue to drain, never more than one decode alive, and every decode that
     * starts gets painted.
     */
    private suspend fun pump() {
        requestedPositionMs.filterNotNull().collect { position ->
            // Drop any signal left over from an earlier seek, or this wait would be
            // satisfied by a frame that is already on screen.
            val stale = generateSequence { seekSettled.tryReceive().getOrNull() }.count()
            val startedAt = SystemClock.elapsedRealtime()
            player.seekTo(position)
            // Still a deadline rather than a guarantee: the two callbacks together cover
            // every case seen so far, but a missed signal must cost one short interval
            // rather than wedge scrubbing entirely.
            val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) { seekSettled.receive() } != null
            log(position, startedAt, settled, stale)
        }
    }

    /**
     * Traces one seek's round trip, off unless explicitly enabled.
     *
     * The pump is the only place that knows both when a seek was issued and when it painted,
     * which makes it the natural instrument for two open questions: why fast scrubbing stalls,
     * and what an exact seek actually costs relative to its distance from a keyframe.
     * Enable with `adb shell setprop log.tag.MotionPhotoSeek VERBOSE`; silent otherwise, so it
     * costs nothing in a normal build.
     */
    private fun log(position: Long, startedAt: Long, settled: Boolean, stale: Int) {
        if (!Log.isLoggable(SEEK_LOG_TAG, Log.VERBOSE)) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        Log.v(
            SEEK_LOG_TAG,
            "seek=${position}ms elapsed=${elapsed}ms " +
                (if (settled) "settled" else "TIMED_OUT") +
                (if (stale > 0) " staleSignals=$stale" else ""),
        )
    }

    /**
     * Routes decoded frames to [surfaceView].
     *
     * Safe to call again with a different view: a configuration change destroys the old
     * surface and Compose hands us a new one, with the player and its position surviving
     * in between.
     */
    fun attachTo(surfaceView: SurfaceView) {
        player.setVideoSurfaceView(surfaceView)
    }

    fun detach() {
        player.clearVideoSurface()
    }

    /**
     * Asks for the frame at [positionMs].
     *
     * Returns immediately. The seek is issued by [pump] as soon as the previous one has
     * painted; if several positions arrive in the meantime only the last is honored, which is
     * what makes a drag show frames instead of only its final position.
     */
    fun seekTo(positionMs: Long) {
        requestedPositionMs.value = positionMs
    }

    override fun close() {
        scope.cancel()
        seekSettled.close()
        player.removeListener(listener)
        player.release()
    }
}

/**
 * Width over height of [videoSize] as it will actually be drawn, or null if unknown.
 *
 * Pixel aspect ratio is folded in because anamorphic content is not square-pixelled, and a
 * non-positive value from Media3 means "unknown" rather than zero.
 *
 * **No rotation correction, deliberately.** `onVideoSizeChanged` reports the size of the
 * frames being *rendered*, so when the player applies the clip's rotation — measured on a
 * Pixel 10 Pro XL (2026-07-20) that it does — `width` and `height` already describe the drawn
 * frame. `VideoSize.unappliedRotationDegrees` exists for the era when apps rotated video
 * themselves and is deprecated in 1.10.1; consulting it here would transpose the one case it
 * was meant to fix.
 */
internal fun aspectRatioOf(videoSize: VideoSize): Float? {
    if (videoSize.width <= 0 || videoSize.height <= 0) return null
    val pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
    return videoSize.width * pixelRatio / videoSize.height
}
