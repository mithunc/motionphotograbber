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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhoto
import xyz.mithunc.motionphotograbber.motionphoto.MotionPhotoParser
import java.io.File
import java.time.Duration as JavaDuration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.toJavaDuration

/**
 * Metadata transfer against whatever real motion photos are in `samples/`.
 *
 * Nothing here is tied to a particular file: no names, no coordinates, no timestamps.
 * Drop in any Pixel motion photo and it gets exercised. That matters because
 * [ExifMetadataCopierTest] seeds its fixtures through [ExifInterface] itself, so on its
 * own it never proves the copier survives metadata a real camera wrote.
 *
 * Skips when `samples/` holds no JPEGs, keeping a fresh clone green — see
 * `samples/README.md`.
 */
class ExifMetadataCopierSampleTest {

    companion object {
        private const val SOFTWARE = "Motion Photo Grabber (test)"

        /** A plausible mid-clip scrub position, measured from the start of the clip. */
        private val SCRUB_POSITION = 500_000.microseconds

        private val EXIF_DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        /** Matches the frame size Media3 decodes these clips to, near enough. */
        private const val FRAME_WIDTH = 1080
        private const val FRAME_HEIGHT = 1440
    }

    @TestFactory
    fun `metadata survives the trip from a real motion photo onto an extracted frame`():
        List<DynamicTest> {
        val samples = sampleJpegs()
        if (samples.isEmpty()) {
            // A TestFactory must yield at least one node, so emit a single skipping test
            // rather than an empty list, which JUnit reports as a failure.
            return listOf(
                DynamicTest.dynamicTest("no samples present") {
                    assumeTrue(false, "samples/ contains no JPEGs; skipping metadata checks")
                },
            )
        }
        return samples.map { sample ->
            DynamicTest.dynamicTest(sample.name) { checkRoundTrip(sample) }
        }
    }

    private fun checkRoundTrip(sample: File) {
        val parsed = MotionPhotoParser.parse(sample)
        val found = parsed as? MotionPhoto.Found
            ?: error("${sample.name} did not parse as a motion photo: $parsed")

        // The shutter press sits inside the clip, so a mid-clip scrub is routinely
        // *before* it — on these samples the offset comes out negative as often as not.
        val frameOffset = found.defaultFrameTimestampUs
            ?.let { SCRUB_POSITION - it.microseconds }
            ?: Duration.ZERO

        val source = requireNotNull(JpegFile.of(sample)) { "${sample.name} is not a readable JPEG" }
        val frameFile = writeBlankJpeg(
            File.createTempFile("frame", ".jpg"),
            FRAME_WIDTH,
            FRAME_HEIGHT,
        )
        try {
            val frame = requireNotNull(JpegFile.of(frameFile)) { "blank frame is not a JPEG" }

            val result = ExifMetadataCopier.copyMetadata(
                from = source,
                to = frame,
                editingSoftware = SOFTWARE,
                frameOffset = frameOffset,
            )
            assertTrue(result is CopyResult.Success, "copy failed for ${sample.name}: $result")

            val original = ExifInterface(sample)
            val written = ExifInterface(frameFile)

            assertLocationSurvives(original, written)
            assertCaptureTimeShifted(original, written, frameOffset)
            assertCameraIdentitySurvives(original, written)

            assertEquals(
                ExifInterface.ORIENTATION_NORMAL,
                written.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
                "orientation must be normalized whatever the still declares",
            )
            assertEquals(SOFTWARE, written.getAttribute(ExifInterface.TAG_SOFTWARE))
            assertEquals(
                FRAME_WIDTH,
                written.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, -1),
                "pixel dimensions must describe the frame, not the still",
            )

            // The strongest available check that the source XMP did not come across:
            // if the Container:Directory had been copied, the parser would locate an
            // embedded video inside a plain still.
            val reparsed = MotionPhotoParser.parse(frameFile)
            assertTrue(
                reparsed !is MotionPhoto.Found,
                "the extracted frame still advertises an embedded video: $reparsed",
            )
        } finally {
            frameFile.delete()
        }
    }

    private fun assertLocationSurvives(original: ExifInterface, written: ExifInterface) {
        val sourceLatLong = original.latLong
        // A photo taken with location off is a legitimate sample, so skip rather than fail.
        assumeTrue(sourceLatLong != null, "source carries no GPS; skipping the location check")

        val writtenLatLong = written.latLong
        assertNotNull(writtenLatLong, "GPS did not reach the extracted frame")
        assertEquals(sourceLatLong!![0], writtenLatLong!![0], 1e-6, "latitude")
        assertEquals(sourceLatLong[1], writtenLatLong[1], 1e-6, "longitude")
    }

    /**
     * The written capture time must sit exactly [frameOffset] away from the original.
     *
     * Asserted as a *difference* between the two files rather than by recomputing the
     * expected timestamp, so this does not restate the production arithmetic.
     */
    private fun assertCaptureTimeShifted(
        original: ExifInterface,
        written: ExifInterface,
        frameOffset: Duration,
    ) {
        val before = original.captureInstant()
        assumeTrue(before != null, "source carries no DateTimeOriginal; skipping the time check")

        val after = written.captureInstant()
        assertNotNull(after, "no capture time reached the extracted frame")
        assertEquals(
            frameOffset.toJavaDuration(),
            JavaDuration.between(before, after),
            "capture time moved by the wrong amount",
        )
    }

    private fun assertCameraIdentitySurvives(original: ExifInterface, written: ExifInterface) {
        for (tag in listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)) {
            val expected = original.getAttribute(tag) ?: continue
            assertEquals(expected, written.getAttribute(tag), tag)
        }
    }

    /** `DateTimeOriginal` and `SubSecTimeOriginal` combined, or null if unset. */
    private fun ExifInterface.captureInstant(): LocalDateTime? {
        val dateTime = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val parsed = runCatching { LocalDateTime.parse(dateTime, EXIF_DATE_TIME) }.getOrNull()
            ?: return null
        val digits = getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
            ?.trim()
            ?.takeWhile { it.isDigit() }
            .orEmpty()
        if (digits.isEmpty()) return parsed
        return parsed.plusNanos(digits.take(9).padEnd(9, '0').toLong())
    }
}
