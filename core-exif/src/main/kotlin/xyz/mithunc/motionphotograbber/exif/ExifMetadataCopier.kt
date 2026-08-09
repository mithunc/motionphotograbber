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
package xyz.mithunc.motionphotograbber.exif

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration

/**
 * A file that existed, was readable, and began with the JPEG SOI marker `FFD8` at the
 * moment this was constructed.
 *
 * [ExifInterface] cannot create a destination file, so handing it a path that is absent
 * or not a JPEG is a programming error. Requiring this type moves that failure to the
 * call site instead of runtime.
 *
 * The guarantee is **checked at construction, not maintained over time** — nothing stops
 * the file being deleted or replaced afterward, which is why
 * [CopyResult.DestinationUnwritable] still exists. Kotlin offers no stronger construct.
 */
@JvmInline
value class JpegFile private constructor(val file: File) {

    companion object {
        private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())

        /** Null when [file] is absent, unreadable, or does not begin with a JPEG SOI marker. */
        fun of(file: File): JpegFile? {
            if (!file.isFile || !file.canRead()) return null
            val head = ByteArray(SOI.size)
            val read = try {
                FileInputStream(file).use { it.read(head) }
            } catch (_: IOException) {
                return null
            }
            if (read != SOI.size || !head.contentEquals(SOI)) return null
            return JpegFile(file)
        }
    }
}

/** The outcome of writing a source photo's metadata onto an extracted frame. */
sealed interface CopyResult {

    /** [tagsWritten] counts distinct tags set on the destination, overrides included. */
    data class Success(val tagsWritten: Int) : CopyResult

    data class SourceUnreadable(val reason: String) : CopyResult

    data class DestinationUnwritable(val reason: String) : CopyResult
}

/**
 * Copies a motion photo's metadata onto a still extracted from its embedded clip.
 *
 * Which tags move, and why the rest do not, is documented in `ExifTags.kt`.
 */
object ExifMetadataCopier {

    /** Exif stores whole seconds in this shape; the fraction lives in a SubSecTime tag. */
    private val EXIF_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /** SubSecTime holds the digits after the decimal point, so it scales to nanoseconds. */
    private const val SUB_SECOND_DIGITS = 9

    private const val NANOS_PER_MICRO = 1_000

    /**
     * Sub-second output width.
     *
     * Six, not three: the clip's frame positions arrive in microseconds, so writing
     * milliseconds would truncate a real value we hold — and truncation of an absolute
     * time always biases the same direction. Exif places no limit on the length of a
     * SubSecTime string. Android reduces it to milliseconds when deriving
     * `MediaStore.DATE_TAKEN`, which is the reader's business, not a reason to discard
     * the precision on the way in.
     */
    private const val SUB_SECOND_OUTPUT_FORMAT = "%06d"

    /**
     * @param editingSoftware identifies the producing app, written to `Software`. Supplied by
     *   the caller rather than hardcoded because both the app's name and its version are
     *   subject to change, and neither belongs in this module.
     * @param frameOffset signed position of the extracted frame relative to the shutter
     *   press — `framePosition - Found.defaultFrameTimestampUs`. **Negative** for frames
     *   captured before the button was pressed, which is most of the clip on some files.
     *   Pass [Duration.ZERO] when the container declares no default-frame timestamp; the
     *   capture times then come through unshifted.
     * @param overrides applied last, so a caller can force any tag, including ones this
     *   module otherwise omits.
     */
    fun copyMetadata(
        from: JpegFile,
        to: JpegFile,
        editingSoftware: String,
        frameOffset: Duration,
        overrides: Map<String, String> = emptyMap(),
    ): CopyResult {
        val source = try {
            ExifInterface(from.file)
        } catch (e: IOException) {
            return CopyResult.SourceUnreadable(e.message ?: "could not read ${from.file.name}")
        }
        val destination = try {
            ExifInterface(to.file)
        } catch (e: IOException) {
            return CopyResult.DestinationUnwritable(e.message ?: "could not read ${to.file.name}")
        }

        // Assembled as a map first so that a tag set twice — by an override, say — is
        // written once and counted once, with the last writer winning.
        val attributes = LinkedHashMap<String, String>()
        for (tag in SUPPORTED_TAGS) {
            source.getAttribute(tag)?.let { attributes[tag] = it }
        }
        attributes += shiftedTimestamps(source, frameOffset)
        attributes += outputDimensions(destination)

        // Media3 has already applied the clip's tkhd rotation, so the frame reaching the
        // encoder is upright. Copying the still's Orientation — 6 on a portrait Pixel
        // photo — would have every viewer rotate an upright frame another 90 degrees.
        // Written explicitly rather than left absent so the intent survives a future edit.
        attributes[ExifInterface.TAG_ORIENTATION] = ExifInterface.ORIENTATION_NORMAL.toString()
        attributes[ExifInterface.TAG_SOFTWARE] = editingSoftware
        attributes += overrides

        for ((tag, value) in attributes) {
            destination.setAttribute(tag, value)
        }

        return try {
            destination.saveAttributes()
            CopyResult.Success(attributes.size)
        } catch (e: IOException) {
            CopyResult.DestinationUnwritable(e.message ?: "could not write ${to.file.name}")
        }
    }

    /**
     * When [from] was captured, as epoch milliseconds, or null if it does not say.
     *
     * Exists because `MediaStore.DATE_TAKEN` needs an instant, and an Exif capture time is
     * not one — it is a wall-clock reading whose zone lives in a separate tag. Resolving
     * that is this module's job: `ExifInterface`'s own `getDateTimeOriginal` is
     * `@RestrictTo(LIBRARY)`, so a caller outside the library has no supported way to ask,
     * and re-deriving the parse elsewhere would put Exif date handling in two places.
     *
     * Falls back to the device's current zone when `OffsetTimeOriginal` is absent, which
     * is the same assumption every gallery makes about an offset-less capture time.
     */
    fun readCaptureTimeMs(from: JpegFile): Long? {
        val exif = try {
            ExifInterface(from.file)
        } catch (_: IOException) {
            return null
        }
        val captured = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val parsed = try {
            LocalDateTime.parse(captured, EXIF_DATE_TIME)
        } catch (_: DateTimeParseException) {
            return null
        }
        val zone = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
            ?: ZoneId.systemDefault()

        return parsed
            .plusNanos(subSecondNanos(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Capture times moved to where the extracted frame actually sits in the clip.
     *
     * The shutter press is not the start of the clip — it sits inside it, at
     * `MotionPhotoPresentationTimestampUs` — so [offset] is signed and routinely
     * negative. Arithmetic runs on [LocalDateTime] rather than an instant deliberately:
     * Exif capture times are wall-clock readings with the zone in a separate tag, and
     * never converting to an instant means no DST rule can perturb a shift of seconds.
     */
    private fun shiftedTimestamps(
        source: ExifInterface,
        offset: Duration,
    ): Map<String, String> {
        val shifted = LinkedHashMap<String, String>()
        for ((dateTimeTag, subSecondTag) in DERIVED_TIMESTAMPS) {
            val original = source.getAttribute(dateTimeTag) ?: continue
            val parsed = try {
                LocalDateTime.parse(original, EXIF_DATE_TIME)
            } catch (_: DateTimeParseException) {
                // Cameras do emit blank or malformed timestamps. Passing one through
                // beats discarding the only capture time the file has.
                shifted[dateTimeTag] = original
                source.getAttribute(subSecondTag)?.let { shifted[subSecondTag] = it }
                continue
            }

            val exact = parsed
                .plusNanos(subSecondNanos(source.getAttribute(subSecondTag)))
                .plusNanos(offset.inWholeNanoseconds)

            shifted[dateTimeTag] = exact.format(EXIF_DATE_TIME)
            // Always written, even when the source carried no sub-second value and even
            // when the offset is zero: after a shift the fraction is part of the answer,
            // and omitting it would truncate the timestamp to the second and lose the
            // ordering between frames from the same clip. Emitting it unconditionally
            // keeps one code path rather than a special case that only fires sometimes.
            shifted[subSecondTag] =
                SUB_SECOND_OUTPUT_FORMAT.format(exact.nano / NANOS_PER_MICRO)
        }
        return shifted
    }

    /**
     * `SubSecTime` holds the digits *after* the decimal point, so "5" is half a second
     * rather than five of anything. Padding to nanosecond width is the whole conversion.
     */
    private fun subSecondNanos(value: String?): Long {
        val digits = value?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return 0L
        return digits.take(SUB_SECOND_DIGITS).padEnd(SUB_SECOND_DIGITS, '0').toLong()
    }

    /**
     * The frame's true pixel dimensions, read back off the frame itself.
     *
     * `PixelXDimension`/`PixelYDimension` are the Exif-side "valid image size" tags that
     * cameras conventionally write, so the output should carry correct ones rather than
     * the source's. No parsing is needed to find them: on a JPEG, ExifInterface derives
     * `ImageWidth`/`ImageLength` by walking the SOF markers, so on the *destination*
     * those two already describe the extracted frame.
     *
     * The TIFF-side `ImageWidth`/`ImageLength` are not themselves written — they belong
     * to TIFF and thumbnail IFDs, and are conventionally absent from a JPEG's primary
     * IFD.
     */
    private fun outputDimensions(destination: ExifInterface): Map<String, String> {
        val width = destination.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: return emptyMap()
        val height = destination.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: return emptyMap()
        return mapOf(
            ExifInterface.TAG_PIXEL_X_DIMENSION to width,
            ExifInterface.TAG_PIXEL_Y_DIMENSION to height,
        )
    }
}
