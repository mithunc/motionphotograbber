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
package xyz.mithunc.motionphotograbber.motionphoto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Error and unsupported-flavor paths.
 *
 * These build their input in memory rather than reading `samples/`, so unlike the
 * byte-range tests they run in a fresh clone with no sample files present.
 */
class MotionPhotoParserEdgeCaseTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `input that is not a JPEG is not a motion photo`() {
        val result = MotionPhotoParser.parse(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))

        assertInstanceOf(MotionPhoto.NotMotionPhoto::class.java, result)
    }

    @Test
    fun `ordinary JPEG with no XMP is not a motion photo`() {
        val result = MotionPhotoParser.parse(jpeg(xmp = null))

        assertInstanceOf(MotionPhoto.NotMotionPhoto::class.java, result)
    }

    @Test
    fun `Ultra HDR photo with a gain map but no video is not a motion photo`() {
        // A non-motion Pixel photo still carries a Container directory — Primary plus
        // GainMap. Having a container is not on its own evidence of a motion photo.
        val result = MotionPhotoParser.parse(
            jpeg(
                container(
                    item(semantic = "Primary", mime = "image/jpeg"),
                    item(semantic = "GainMap", mime = "image/jpeg", length = 100),
                ),
            ),
        )

        assertInstanceOf(MotionPhoto.NotMotionPhoto::class.java, result)
    }

    @Test
    fun `legacy MicroVideoOffset file is reported as unsupported`() {
        val xmp = "<rdf:Description xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
            "GCamera:MicroVideo=\"1\" GCamera:MicroVideoOffset=\"4096\"/>"

        val result = MotionPhotoParser.parse(jpeg(xmp))

        val unsupported = assertInstanceOf(MotionPhoto.NotSupported::class.java, result)
        assertEquals(Flavor.GOOGLE_MICROVIDEO, unsupported.flavor)
    }

    @Test
    fun `Samsung marker file is reported as unsupported`() {
        val result = MotionPhotoParser.parse(samsungBytes())

        val unsupported = assertInstanceOf(MotionPhoto.NotSupported::class.java, result)
        assertEquals(Flavor.SAMSUNG_MARKER, unsupported.flavor)
    }

    @Test
    fun `Samsung marker beyond the header window is still detected when parsing a file`() {
        // The marker sits after a complete JPEG, which in a real file is megabytes in —
        // past the prefix the parser reads. Detection must not depend on it being near
        // the front.
        val file = File(tempDir, "samsung.jpg")
        file.writeBytes(samsungBytes(padding = 2 * 1024 * 1024))

        val result = MotionPhotoParser.parse(file)

        val unsupported = assertInstanceOf(MotionPhoto.NotSupported::class.java, result)
        assertEquals(Flavor.SAMSUNG_MARKER, unsupported.flavor)
    }

    @Test
    fun `container declaring more bytes than the file holds is malformed`() {
        val result = MotionPhotoParser.parse(
            jpeg(
                container(
                    item(semantic = "Primary", mime = "image/jpeg"),
                    item(semantic = "MotionPhoto", mime = "video/mp4", length = 50_000_000),
                ),
            ),
        )

        assertInstanceOf(MotionPhoto.Malformed::class.java, result)
    }

    @Test
    fun `container with two undeclared lengths is malformed`() {
        // Two items without Item:Length make the layout ambiguous: there is no way to
        // tell where one ends and the next begins.
        val result = MotionPhotoParser.parse(
            jpeg(
                container(
                    item(semantic = "Primary", mime = "image/jpeg"),
                    item(semantic = "MotionPhoto", mime = "video/mp4"),
                ),
            ),
        )

        assertInstanceOf(MotionPhoto.Malformed::class.java, result)
    }

    @Test
    fun `a container whose Primary is not the first item is malformed`() {
        // "The directory may contain only one primary image item and it must be the first
        // item in the directory." MotionPhotoStillWriter recovers the still by copying a
        // prefix of the file, so a Primary anywhere else would have it publish the wrong
        // bytes down the one path meant to preserve the original exactly.
        val result = MotionPhotoParser.parse(
            SyntheticMotionPhoto.buildInOrder(
                listOf(
                    SyntheticMotionPhoto.Part.GAIN_MAP,
                    SyntheticMotionPhoto.Part.PRIMARY,
                    SyntheticMotionPhoto.Part.VIDEO,
                ),
            ),
        )

        val malformed = assertInstanceOf(MotionPhoto.Malformed::class.java, result)
        assertTrue(malformed.reason.contains("Primary"), malformed.reason)
    }

    @Test
    fun `a container whose video is not the last item is malformed`() {
        // "The location of this media item must be at the end of the file. No other bytes
        // may be placed after this media item's bytes have terminated." Note the bytes here
        // are internally consistent — every item lands on the data it claims — so only the
        // ordering check catches this one.
        val result = MotionPhotoParser.parse(
            SyntheticMotionPhoto.buildInOrder(
                listOf(
                    SyntheticMotionPhoto.Part.PRIMARY,
                    SyntheticMotionPhoto.Part.VIDEO,
                    SyntheticMotionPhoto.Part.GAIN_MAP,
                ),
            ),
        )

        val malformed = assertInstanceOf(MotionPhoto.Malformed::class.java, result)
        assertTrue(malformed.reason.contains("not last"), malformed.reason)
    }

    @Test
    fun `the conformant order is accepted`() {
        // Guards the two checks above against being trivially satisfiable: a fixture path
        // that rejected everything would pass both of them.
        val result = MotionPhotoParser.parse(
            SyntheticMotionPhoto.buildInOrder(SyntheticMotionPhoto.CONFORMANT_ORDER),
        )

        assertInstanceOf(MotionPhoto.Found::class.java, result)
    }

    @Test
    fun `a path with no file at it is unreadable`() {
        // Not "too small to be a JPEG": File.length() answers 0 here, but nothing has been
        // read, so there is no structure to make a claim about.
        val result = MotionPhotoParser.parse(File(tempDir, "never-written.jpg"))

        assertInstanceOf(MotionPhoto.Unreadable::class.java, result)
    }

    @Test
    fun `a file that cannot be opened is unreadable rather than a throw`() {
        // The reachable case in the app: a cache copy whose backing storage went away, or
        // a share grant revoked between the copy and the parse. RandomAccessFile throws
        // IOException, and GrabberViewModel calls this inside a bare viewModelScope.launch
        // with no handler, so a throw here is a crash.
        val file = File(tempDir, "unreadable.jpg")
        file.writeBytes(SyntheticMotionPhoto.build().bytes)
        assumeTrue(
            file.setReadable(false, false) && !file.canRead(),
            "read permission could not be revoked here — running as root?",
        )

        try {
            val result = MotionPhotoParser.parse(file)

            val unreadable = assertInstanceOf(MotionPhoto.Unreadable::class.java, result)
            assertTrue(unreadable.reason.isNotBlank(), "the reason must say what went wrong")
        } finally {
            // Restored so @TempDir cleanup is not left fighting the permissions.
            file.setReadable(true, false)
        }
    }

    // --- input builders ---

    private fun item(semantic: String, mime: String, length: Long? = null): String {
        val lengthAttribute = length?.let { " Item:Length=\"$it\"" } ?: ""
        return "<rdf:li rdf:parseType=\"Resource\">" +
            "<Container:Item Item:Mime=\"$mime\" Item:Semantic=\"$semantic\"$lengthAttribute/>" +
            "</rdf:li>"
    }

    private fun container(vararg items: String): String =
        "<rdf:Description><Container:Directory><rdf:Seq>" +
            items.joinToString("") +
            "</rdf:Seq></Container:Directory></rdf:Description>"

    /** Minimal but structurally valid JPEG: SOI, optional XMP APP1, EOI. */
    private fun jpeg(xmp: String?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        if (xmp != null) {
            val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) +
                xmp.toByteArray(Charsets.UTF_8)
            val segmentLength = payload.size + 2
            out.write(0xFF); out.write(0xE1)
            out.write((segmentLength shr 8) and 0xFF)
            out.write(segmentLength and 0xFF)
            out.write(payload)
        }
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun samsungBytes(padding: Int = 0): ByteArray =
        jpeg(xmp = null) +
            ByteArray(padding) +
            "MotionPhoto_Data".toByteArray(Charsets.US_ASCII) +
            ByteArray(64) // stand-in for the trailing MP4
}
