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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Metadata transfer against fixtures built in test code, so this tier runs everywhere —
 * including a fresh clone, where `samples/` is empty.
 *
 * The fixtures are seeded through [ExifInterface] itself, which means this tier checks
 * the copier's tag selection and time arithmetic rather than ExifInterface's own fidelity
 * to real camera output. `ExifMetadataCopierSampleTest` covers the latter.
 */
class ExifMetadataCopierTest {

    companion object {
        private const val SHUTTER_TIME = "2026:07:18 09:15:28"
        private const val SHUTTER_SUB_SECOND = "500"
        private const val SOFTWARE = "Motion Photo Grabber 9.9"

        private const val LATITUDE = 37.422
        private const val LONGITUDE = -122.084

        /** Deliberately different from [FRAME_WIDTH] so their provenance is separable. */
        private const val STILL_WIDTH = 128
        private const val STILL_HEIGHT = 96
        private const val FRAME_WIDTH = 64
        private const val FRAME_HEIGHT = 48
    }

    /**
     * A stand-in for the original motion photo: a JPEG seeded with the metadata a Pixel
     * would have written, including values this module is expected *not* to carry over.
     */
    private fun sourcePhoto(dir: File, orientation: String = "6"): JpegFile {
        val file = writeBlankJpeg(File(dir, "source.jpg"), STILL_WIDTH, STILL_HEIGHT)
        with(ExifInterface(file)) {
            setLatLong(LATITUDE, LONGITUDE)
            setAltitude(12.5)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, SHUTTER_TIME)
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, SHUTTER_SUB_SECOND)
            setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, SHUTTER_TIME)
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, SHUTTER_SUB_SECOND)
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 10 Pro XL")
            setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SN123456")
            setAttribute(ExifInterface.TAG_ARTIST, "A Photographer")
            // Shared optics, expected to survive.
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.7")
            // Per-exposure, expected to be dropped: the clip ran its own auto-exposure.
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "0.008")
            // Rotation is already baked into the decoded frame by Media3.
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            // Describes the source image specifically, so it must not be duplicated.
            setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, "abcdef0123456789")
            // UNDEFINED-format, so it cannot survive ExifInterface's String accessors.
            setAttribute(ExifInterface.TAG_SCENE_TYPE, "1")
            saveAttributes()
        }
        return requireNotNull(JpegFile.of(file)) { "seeded source is not a readable JPEG" }
    }

    /** A stand-in for the freshly encoded frame: a valid JPEG with no metadata at all. */
    private fun extractedFrame(dir: File, name: String = "frame.jpg"): JpegFile {
        val file = writeBlankJpeg(File(dir, name), FRAME_WIDTH, FRAME_HEIGHT)
        return requireNotNull(JpegFile.of(file)) { "blank frame is not a readable JPEG" }
    }

    private fun copyOnto(
        frame: JpegFile,
        source: JpegFile,
        frameOffset: Duration = Duration.ZERO,
        overrides: Map<String, String> = emptyMap(),
    ): ExifInterface {
        val result = ExifMetadataCopier.copyMetadata(
            from = source,
            to = frame,
            software = SOFTWARE,
            frameOffset = frameOffset,
            overrides = overrides,
        )
        assertTrue(result is CopyResult.Success, "copy failed: $result")
        return ExifInterface(frame.file)
    }

    @Test
    fun `carries location and camera identity onto the extracted frame`(@TempDir dir: File) {
        val source = sourcePhoto(dir)

        val written = copyOnto(extractedFrame(dir), source)

        val latLong = written.latLong
        assertNotNull(latLong, "no GPS on the extracted frame")
        assertEquals(LATITUDE, latLong!![0], 1e-6, "latitude")
        assertEquals(LONGITUDE, latLong[1], 1e-6, "longitude")
        assertEquals(12.5, written.getAltitude(0.0), 1e-6, "altitude")
        assertEquals("Google", written.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Pixel 10 Pro XL", written.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("A Photographer", written.getAttribute(ExifInterface.TAG_ARTIST))
    }

    @Test
    fun `copies the body serial number rather than scrubbing it`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir))

        // An identifying value, kept deliberately: this app preserves metadata, and a
        // frame that silently lost fields the original had would be the surprising result.
        assertEquals("SN123456", written.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER))
    }

    @Test
    fun `shifts the capture time forward to where the frame sits in the clip`(
        @TempDir dir: File,
    ) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir), frameOffset = 1750.milliseconds)

        // 09:15:28.500 + 1.750s. Sub-second output is microseconds, so .250 is "250000".
        assertEquals("2026:07:18 09:15:30", written.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("250000", written.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
    }

    @Test
    fun `shifts the capture time backward for a frame before the shutter press`(
        @TempDir dir: File,
    ) {
        // The shutter press sits *inside* the clip, so frames preceding it are ordinary,
        // not an edge case — the offset is genuinely negative and must borrow a second.
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir), frameOffset = (-750).milliseconds)

        // 09:15:28.500 - 0.750s, borrowing a second.
        assertEquals("2026:07:18 09:15:27", written.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("750000", written.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
    }

    @Test
    fun `leaves the capture time alone when there is no offset to apply`(@TempDir dir: File) {
        // What a container declaring no default-frame timestamp produces.
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir), frameOffset = Duration.ZERO)

        assertEquals(SHUTTER_TIME, written.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        // Same instant the source recorded, restated at the module's output precision:
        // "500" and "500000" are both one half-second, since SubSecTime holds the digits
        // after the decimal point.
        assertEquals("500000", written.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
    }

    @Test
    fun `orders two frames from one clip by their capture time`(@TempDir dir: File) {
        val source = sourcePhoto(dir)

        val earlier = copyOnto(extractedFrame(dir, "earlier.jpg"), source, 100.milliseconds)
        val later = copyOnto(extractedFrame(dir, "later.jpg"), source, 400.milliseconds)

        // Both land in the same whole second, so the sub-second tag is the only thing
        // keeping them in capture order. Without it a gallery would order them arbitrarily.
        assertEquals(
            earlier.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            later.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            "fixture no longer exercises the sub-second case",
        )
        val earlierSubSecond = earlier.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)!!
        val laterSubSecond = later.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)!!
        assertTrue(
            earlierSubSecond.toInt() < laterSubSecond.toInt(),
            "expected $earlierSubSecond to sort before $laterSubSecond",
        )
    }

    @Test
    fun `normalizes orientation instead of copying the still's`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir, orientation = "6"))

        // Media3 has already applied the clip's rotation. Copying the still's 6 would
        // have every viewer rotate an upright frame another 90 degrees.
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            written.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
        )
    }

    @Test
    fun `does not carry the source XMP onto the frame`(@TempDir dir: File) {
        val source = sourcePhoto(dir)
        with(ExifInterface(source.file)) {
            // Stands in for the Container:Directory a real motion photo carries.
            setAttribute(ExifInterface.TAG_XMP, CONTAINER_XMP)
            saveAttributes()
        }

        val written = copyOnto(extractedFrame(dir), source)

        // Copying it would have the frame advertise an embedded video at byte offsets
        // that do not exist inside it.
        assertNull(written.getAttribute(ExifInterface.TAG_XMP))
    }

    @Test
    fun `keeps shared optics but drops per-exposure values`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir))

        // Same lens, same fixed aperture — still true of the frame.
        assertEquals("1.7", written.getAttribute(ExifInterface.TAG_F_NUMBER))
        // The clip ran its own auto-exposure, and no per-frame value is recoverable.
        assertNull(written.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
    }

    @Test
    fun `records the frame's own dimensions, not the still's`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir))

        assertEquals(FRAME_WIDTH, written.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, -1))
        assertEquals(FRAME_HEIGHT, written.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, -1))
    }

    @Test
    fun `omits tags outside the supported list`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir))

        // Two files asserting the same unique ID is worse than one file having none.
        assertNull(written.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID))
    }

    @Test
    fun `omits UNDEFINED-format tags rather than corrupting them`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir))

        // SceneType describes the capture, so it looks like it belongs with the optics.
        // It does not: ExifInterface has only String accessors, and writing an UNDEFINED
        // tag through them changes its format. Verified with exiftool against a real
        // sample — the copy read back as Unknown where the source said "Directly
        // photographed". Preserving nothing beats preserving a wrong value.
        assertNull(written.getAttribute(ExifInterface.TAG_SCENE_TYPE))
    }

    @Test
    fun `overrides beat the source value`(@TempDir dir: File) {
        val written = copyOnto(
            extractedFrame(dir),
            sourcePhoto(dir),
            overrides = mapOf(ExifInterface.TAG_MAKE to "Overridden"),
        )

        assertEquals("Overridden", written.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun `identifies this app as the producer`(@TempDir dir: File) {
        val written = copyOnto(extractedFrame(dir), sourcePhoto(dir))

        assertEquals(SOFTWARE, written.getAttribute(ExifInterface.TAG_SOFTWARE))
    }

    @Test
    fun `the supported list holds no duplicates`() {
        // ExifInterface declares alias constants that share a string value —
        // TAG_CAMARA_OWNER_NAME/TAG_CAMERA_OWNER_NAME and
        // TAG_ISO_SPEED_RATINGS/TAG_PHOTOGRAPHIC_SENSITIVITY. Listing both of a pair
        // would silently write one tag twice.
        val duplicates = SUPPORTED_TAGS.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate tags in SUPPORTED_TAGS: ${duplicates.keys}")
    }

    @Test
    fun `rejects destinations that are not readable JPEGs`(@TempDir dir: File) {
        assertNull(JpegFile.of(File(dir, "absent.jpg")), "missing file")
        assertNull(JpegFile.of(dir), "directory")

        val notAJpeg = File(dir, "notes.txt").apply { writeText("plain text") }
        assertNull(JpegFile.of(notAJpeg), "no SOI marker")

        val truncated = File(dir, "truncated.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte())) }
        assertNull(JpegFile.of(truncated), "too short to hold a marker")

        assertNotNull(JpegFile.of(extractedFrame(dir).file), "a real JPEG")
    }

    @Test
    fun `reports an unreadable source rather than throwing`(@TempDir dir: File) {
        val frame = extractedFrame(dir)
        val source = sourcePhoto(dir)
        // The type guarantees the file was a JPEG when checked, not that it still is.
        source.file.delete()

        val result = ExifMetadataCopier.copyMetadata(
            from = source,
            to = frame,
            software = SOFTWARE,
            frameOffset = Duration.ZERO,
        )

        assertFalse(result is CopyResult.Success, "expected a failure, got $result")
    }
}

private val CONTAINER_XMP = """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/"/>
      </rdf:RDF>
    </x:xmpmeta>
""".trimIndent()
