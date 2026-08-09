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
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A decoded frame together with the clip position it actually came from. */
data class ExtractedFrame(
    val bitmap: Bitmap,
    val presentationTimeMs: Long,
)

/**
 * What the container declares about the embedded clip, read without decoding it.
 *
 * [rotationDegrees] is the `tkhd` rotation the file declares. Whether it has already
 * been applied to the bitmaps [MotionPhotoFrameExtractor.frameAt] returns is a separate
 * question, and an open one — see the class docs.
 */
data class VideoInfo(
    val durationMs: Long,
    val declaredWidth: Int,
    val declaredHeight: Int,
    val rotationDegrees: Int,
)

/**
 * Extracts frames from the video embedded in a motion photo.
 *
 * The still and the video share one file, so nothing is copied out: [SubrangeDataSource]
 * hands Media3 a view of just the video's bytes, reached via `setMediaSourceFactory`,
 * which is the only injection point `FrameExtractor.Builder` offers.
 *
 * **No rotation is applied here, deliberately.** The embedded clips declare a `tkhd`
 * rotation that varies per file (90° and 270° both occur in the sample set), and Media3
 * documents nothing about whether `FrameExtractor` honors it. Measured on a physical
 * Pixel (2026-07-20): it does — a declared 1440x1080 track decodes to a 1080x1440 bitmap.
 * Rotating again here would produce 180°-wrong frames. `FrameExtractorOrientationTest`
 * pins that behavior so a Media3 upgrade cannot change it unnoticed.
 */
@UnstableApi
class MotionPhotoFrameExtractor private constructor(
    private val sourceFile: File,
    private val videoByteRange: LongRange,
    private val frameExtractor: FrameExtractor,
) : AutoCloseable {

    /** The outcome of decoding one frame. */
    sealed interface Result {

        data class Success(val frame: ExtractedFrame) : Result

        /** The frame could not be decoded. */
        data class Failed(val reason: String) : Result
    }

    /**
     * Reads the clip's declared properties without decoding any frames.
     *
     * Uses [MediaMetadataRetriever] rather than Media3 because its
     * `setDataSource(FileDescriptor, offset, length)` overload takes a byte range
     * directly, which is exactly the shape we have. Note it performs its own track
     * selection, so on a file with more than one video track these values may describe
     * a different track than the one decoded.
     */
    fun readVideoInfo(): VideoInfo {
        val start = videoByteRange.first
        val length = videoByteRange.last - videoByteRange.first + 1
        val retriever = MediaMetadataRetriever()
        try {
            FileInputStream(sourceFile).use { stream ->
                retriever.setDataSource(stream.fd, start, length)
            }
            fun key(k: Int): Int =
                retriever.extractMetadata(k)?.toIntOrNull() ?: 0
            return VideoInfo(
                durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )?.toLongOrNull() ?: 0L,
                declaredWidth = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                declaredHeight = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotationDegrees = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * Decodes the frame at [positionMs] within the clip.
     *
     * A frame that will not decode is an expected outcome, so it comes back as
     * [Result.Failed] rather than as an exception. Media3 has no single type to catch for
     * it — `PlaybackException` for decoder and source trouble, `IllegalStateException` for
     * a released extractor or a position that yields no frame, a bare `RuntimeException`
     * for renderer teardown — and their nearest common supertype is `Exception`. Catching
     * that width is unavoidable; keeping it *here*, in the module that owns the Media3
     * dependency, is what stops it from being every caller's problem.
     */
    suspend fun frameAt(positionMs: Long): Result {
        val frame = try {
            frameExtractor.getFrame(positionMs).await()
        } catch (e: CancellationException) {
            // Not a decode failure: the caller moved on. Reporting it as one would be a
            // lie, and swallowing it would leave this coroutine running.
            throw e
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "the frame at ${positionMs}ms could not be decoded")
        }
        return Result.Success(
            ExtractedFrame(bitmap = frame.bitmap, presentationTimeMs = frame.presentationTimeMs),
        )
    }

    override fun close() {
        frameExtractor.close()
    }

    companion object {
        /**
         * @param videoByteRange the range from [xyz.mithunc.motionphotograbber.motionphoto.MotionPhoto.Found],
         *   inclusive at both ends.
         */
        fun create(
            context: Context,
            sourceFile: File,
            videoByteRange: LongRange,
        ): MotionPhotoFrameExtractor {
            val start = videoByteRange.first
            // The range is inclusive at both ends, so the length is one more than the span.
            val length = videoByteRange.last - videoByteRange.first + 1

            val dataSourceFactory = SubrangeDataSource.Factory(
                upstreamFactory = FileDataSource.Factory(),
                rangeStart = start,
                rangeLength = length,
            )
            val mediaItem = MediaItem.fromUri(Uri.fromFile(sourceFile))
            val frameExtractor = FrameExtractor.Builder(context, mediaItem)
                .setMediaSourceFactory(ProgressiveMediaSource.Factory(dataSourceFactory))
                .build()

            return MotionPhotoFrameExtractor(sourceFile, videoByteRange, frameExtractor)
        }
    }
}

/**
 * Bridges Media3's `ListenableFuture` to a coroutine.
 *
 * Hand-rolled rather than pulling in `kotlinx-coroutines-guava` for a single call site.
 * The listener runs on whichever thread completes the future, so a direct executor is
 * appropriate — no thread hop is wanted here.
 */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                // Unwrap so callers see the real failure, not the future's wrapper.
                cont.resumeWithException(e.cause ?: e)
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        },
        { it.run() },
    )
    cont.invokeOnCancellation { cancel(false) }
}
