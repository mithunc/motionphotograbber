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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration

/**
 * Checks the *shape* of what gets written, not just what reads back.
 *
 * `ExifMetadataCopierTest` asks [ExifInterface] what it wrote, which cannot detect a tag
 * stored in the wrong Exif format — the library reads its own malformed output back
 * without complaint, and the damage only appears when some other program opens the file.
 * These tests read the raw IFD entries instead. See `ExifStructure.kt`.
 */
class ExifStructureTest {

    companion object {
        /**
         * Tags [ExifInterface] writes on its own, into any file it saves.
         *
         * Confirmed with a control: a blank JPEG given nothing but `Software` still comes
         * back carrying these. They are the library's business, not the copier's, so the
         * format contract below does not hold them against us.
         */
        private val LIBRARY_WRITTEN_TAGS = setOf(
            0x0100, // ImageWidth
            0x0101, // ImageLength
            0x0112, // Orientation — also written by us, see the note in the format check
            0x9208, // LightSource, emitted as LONG where the spec says SHORT
        )

        /** Sub-IFD pointers, which are structure rather than metadata. */
        private val IFD_POINTER_TAGS = setOf(0x8769, 0x8825, 0xA005)
    }

    @Test
    fun `no copied tag has a format ExifInterface cannot write`() {
        // The heart of it. ExifInterface exposes only String accessors, so a tag whose
        // Exif format is UNDEFINED cannot survive the trip: setAttribute writes it as
        // ASCII and the value is quietly wrong. Verified against a real sample —
        // SceneType came back as Unknown where the source said "Directly photographed".
        //
        // A static check rather than a round-trip one, deliberately: a round trip only
        // catches the tags a sample happens to carry, and would have missed UserComment,
        // GPSProcessingMethod and GPSAreaInformation, none of which appear in samples/.
        val undefined = SUPPORTED_TAGS.filter { tag ->
            TAG_FORMATS[tag]?.formats == setOf(ExifFormat.UNDEFINED)
        }

        assertTrue(
            undefined.isEmpty(),
            "UNDEFINED-format tags cannot round-trip through ExifInterface and must not " +
                "be copied: $undefined",
        )
    }

    @Test
    fun `every copied tag is one ExifInterface actually knows`() {
        // ExifInterface declares some TAG_* constants with no backing ExifTag entry —
        // TAG_LENS_SERIAL_NUMBER is one. Copying such a tag silently does nothing, which
        // looks like preserved metadata right up until someone checks the output.
        val unknown = SUPPORTED_TAGS.filterNot { it in TAG_FORMATS }

        assertTrue(
            unknown.isEmpty(),
            "tags absent from ExifInterface's own tag table cannot be written: $unknown",
        )
    }

    @Test
    fun `written tags carry their specified Exif format`(@TempDir dir: File) {
        val source = writeBlankJpeg(File(dir, "source.jpg"), 128, 96)
        with(ExifInterface(source)) {
            setLatLong(37.422, -122.084)
            setAltitude(12.5)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:07:18 09:15:28")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "500")
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            setAttribute(ExifInterface.TAG_MODEL, "Pixel 10 Pro XL")
            setAttribute(ExifInterface.TAG_F_NUMBER, "1.7")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "6.9")
            setAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE, "0")
            setAttribute(ExifInterface.TAG_X_RESOLUTION, "72")
            saveAttributes()
        }
        val frame = writeBlankJpeg(File(dir, "frame.jpg"), 64, 48)

        val result = ExifMetadataCopier.copyMetadata(
            from = JpegFile.of(source)!!,
            to = JpegFile.of(frame)!!,
            editingSoftware = "Motion Photo Grabber (test)",
            frameOffset = Duration.ZERO,
        )
        assertTrue(result is CopyResult.Success, "copy failed: $result")

        val byId = TAG_FORMATS.values.associateBy { it.id }
        val violations = readIfdEntries(frame)
            .filterNot { it.tagId in IFD_POINTER_TAGS || it.tagId in LIBRARY_WRITTEN_TAGS }
            .mapNotNull { entry ->
                val spec = byId[entry.tagId] ?: return@mapNotNull null
                // ExifInterface widens integers to LONG when a value arrives as a String,
                // which TIFF permits wherever SHORT is specified. Tolerated; writing
                // ASCII where UNDEFINED is specified is the failure this guards against.
                val allowed = if (ExifFormat.SHORT in spec.formats) {
                    spec.formats + ExifFormat.LONG
                } else {
                    spec.formats
                }
                if (entry.format in allowed) {
                    null
                } else {
                    "0x%04X in %s written as %s, expected %s".format(
                        entry.tagId,
                        entry.ifd,
                        ExifFormat.name(entry.format),
                        allowed.joinToString("/") { ExifFormat.name(it) },
                    )
                }
            }

        assertTrue(violations.isEmpty(), "malformed Exif entries: $violations")
    }

    @Test
    fun `the reader finds the entries a copied frame should have`(@TempDir dir: File) {
        // Guards the reader itself: a walker that silently returned nothing would make
        // every check above vacuously pass.
        val source = writeBlankJpeg(File(dir, "source.jpg"), 128, 96)
        with(ExifInterface(source)) {
            setLatLong(37.422, -122.084)
            setAttribute(ExifInterface.TAG_MAKE, "Google")
            saveAttributes()
        }
        val frame = writeBlankJpeg(File(dir, "frame.jpg"), 64, 48)
        ExifMetadataCopier.copyMetadata(
            from = JpegFile.of(source)!!,
            to = JpegFile.of(frame)!!,
            editingSoftware = "Motion Photo Grabber (test)",
            frameOffset = Duration.ZERO,
        )

        val entries = readIfdEntries(frame)

        assertTrue(entries.any { it.ifd == "IFD0" && it.tagId == 0x010F }, "Make in IFD0")
        assertTrue(entries.any { it.ifd == "GPS" && it.tagId == 0x0002 }, "GPSLatitude in GPS IFD")
        assertTrue(
            entries.any { it.ifd == "ExifIFD" && it.tagId == 0xA002 },
            "PixelXDimension in Exif IFD",
        )
        assertEquals(
            emptyList<IfdEntry>(),
            entries.filter { it.format !in 1..13 },
            "no entry should carry an out-of-range format code",
        )
    }

    @Test
    fun `reads nothing from a JPEG with no Exif`(@TempDir dir: File) {
        val bare = writeBlankJpeg(File(dir, "bare.jpg"), 16, 16)

        assertEquals(emptyList<IfdEntry>(), readIfdEntries(bare))
    }
}
