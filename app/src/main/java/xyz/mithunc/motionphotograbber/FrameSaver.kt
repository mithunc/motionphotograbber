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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.mithunc.motionphotograbber.exif.CopyResult
import xyz.mithunc.motionphotograbber.exif.ExifMetadataCopier
import xyz.mithunc.motionphotograbber.exif.JpegFile
import xyz.mithunc.motionphotograbber.extract.MotionPhotoFrameExtractor
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhoto
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhotoStillWriter
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/** The outcome of writing a chosen frame into the gallery. */
sealed interface SaveResult {

    data class Saved(val displayName: String) : SaveResult

    data class Failed(val reason: String) : SaveResult
}

/**
 * Writes the frame the user chose to `Pictures/`.
 *
 * Two paths, and which one runs is the difference between keeping the original photo and
 * re-encoding a video frame:
 *
 * - **At the shutter-press frame**, the answer already exists in the file. The Primary
 *   JPEG is full resolution, Ultra HDR, and carries correct EXIF, so it is copied out
 *   whole by [MotionPhotoStillWriter] and nothing is decoded, re-encoded, or rewritten.
 * - **Anywhere else in the clip**, the frame has to be decoded and encoded, and then the
 *   metadata that is still true of it copied across by [ExifMetadataCopier].
 */
@UnstableApi
class FrameSaver(private val context: Context) {

    /**
     * @param positionMs where in the clip to save from. Should be the player's *settled*
     *   position, not the one the slider asked for — what the user is looking at is the
     *   frame the decoder landed on.
     * @param atDefaultFrame whether the scrubber is parked on the shutter-press frame,
     *   which selects the original-bytes path.
     */
    suspend fun save(
        source: SourcePhoto,
        found: MotionPhoto.Found,
        positionMs: Long,
        atDefaultFrame: Boolean,
        editingSoftware: String,
    ): SaveResult {
        val outputDir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val staging = File(outputDir, "staged.jpg")

        // Built in the cache first, then streamed into MediaStore. Not avoidable:
        // ExifMetadataCopier needs a File it can rewrite in place, and MediaStore only
        // ever hands back an OutputStream.
        val prepared = if (atDefaultFrame) {
            withContext(Dispatchers.IO) { writeOriginalStill(source, found, staging) }
        } else {
            encodeFrame(source, found, positionMs, staging, editingSoftware)
        }
        if (prepared != null) {
            staging.delete()
            return SaveResult.Failed(prepared)
        }

        val name = outputName(source.displayName, positionMs, atDefaultFrame)
        return withContext(Dispatchers.IO) {
            try {
                publish(staging, name, dateTakenMs(source.file, found, positionMs))
                SaveResult.Saved(name)
            } catch (e: IOException) {
                SaveResult.Failed(e.message ?: "could not write to the gallery")
            } finally {
                staging.delete()
            }
        }
    }

    /** Returns null on success, or a reason. */
    private fun writeOriginalStill(
        source: SourcePhoto,
        found: MotionPhoto.Found,
        staging: File,
    ): String? =
        when (val result = MotionPhotoStillWriter.writeStill(source.file, found, staging)) {
            is MotionPhotoStillWriter.Result.Success -> null
            is MotionPhotoStillWriter.Result.Failed -> result.reason
        }

    /** Returns null on success, or a reason. */
    private suspend fun encodeFrame(
        source: SourcePhoto,
        found: MotionPhoto.Found,
        positionMs: Long,
        staging: File,
        editingSoftware: String,
    ): String? {
        val bitmap = MotionPhotoFrameExtractor.create(context, source.file, found.videoByteRange)
            .use { extractor ->
                when (val result = extractor.frameAt(positionMs)) {
                    is MotionPhotoFrameExtractor.Result.Success -> result.frame.bitmap
                    is MotionPhotoFrameExtractor.Result.Failed -> return result.reason
                }
            }

        return withContext(Dispatchers.IO) {
            try {
                staging.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            } catch (e: IOException) {
                return@withContext e.message ?: "could not write the encoded frame"
            } finally {
                bitmap.recycle()
            }

            val from = JpegFile.of(source.file) ?: return@withContext "the source is not a readable JPEG"
            val to = JpegFile.of(staging) ?: return@withContext "the encoder did not produce a JPEG"

            when (
                val copied = ExifMetadataCopier.copyMetadata(
                    from = from,
                    to = to,
                    editingSoftware = editingSoftware,
                    frameOffset = frameOffset(found, positionMs),
                    // :core-exif cannot see what the encoder did, so it omits ColorSpace
                    // rather than guess (SPEC.md, 2026-07-26). Here we know: Bitmap.compress
                    // writes sRGB, and 1 is sRGB.
                    overrides = mapOf(ExifInterface.TAG_COLOR_SPACE to "1"),
                )
            ) {
                is CopyResult.Success -> null
                is CopyResult.SourceUnreadable -> copied.reason
                is CopyResult.DestinationUnwritable -> copied.reason
            }
        }
    }

    /**
     * The frame's position relative to the shutter press.
     *
     * **Signed, and routinely negative**: the shutter press sits *inside* the clip, so
     * frames before it are ordinary rather than an edge case.
     */
    private fun frameOffset(found: MotionPhoto.Found, positionMs: Long): Duration {
        val default = found.defaultFrameTimestampUs ?: return Duration.ZERO
        return (positionMs * 1_000L - default).microseconds
    }

    /**
     * What the gallery should sort this file by.
     *
     * Set explicitly rather than left for the scanner to re-derive from EXIF, because
     * `DATE_TAKEN` is what a gallery sorts on and it is what puts two frames pulled from
     * one clip in the order they were captured.
     */
    private fun dateTakenMs(sourceFile: File, found: MotionPhoto.Found, positionMs: Long): Long {
        val captured = JpegFile.of(sourceFile)
            ?.let(ExifMetadataCopier::readCaptureTimeMs)
        // A file with no DateTimeOriginal still has to sort somewhere, and its mtime is
        // the closest thing to a capture time available.
            ?: sourceFile.lastModified()
        return captured + frameOffset(found, positionMs).inWholeMilliseconds
    }

    private fun outputName(sourceName: String, positionMs: Long, atDefaultFrame: Boolean): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
        val suffix = if (atDefaultFrame) "original" else POSITION_FORMAT.format(positionMs)
        return "${base}_$suffix.jpg"
    }

    private fun publish(staging: File, displayName: String, dateTakenMs: Long) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTakenMs)
            // Hides the row until the bytes are all there, so a gallery scanning mid-write
            // cannot index a truncated photo.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
            ?: throw IOException("the gallery refused a new entry")

        try {
            resolver.openOutputStream(uri)?.use { output -> staging.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("the gallery entry could not be opened for writing")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }

        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    companion object {
        /**
         * Visually lossless, and the frame has already been through HEVC — pushing to 100
         * would preserve the decoder's artifacts at two to three times the size rather
         * than preserve any detail. SPEC.md defers a user-facing quality setting to M5.
         */
        private const val JPEG_QUALITY = 95

        private const val CACHE_SUBDIR = "outputs"

        /**
         * Names are zero-padded so lexicographic order matches clip order. Four digits
         * covers the 1-3 s clips these files carry; a longer one simply grows the field.
         */
        private const val POSITION_FORMAT = "%04dms"
    }
}
