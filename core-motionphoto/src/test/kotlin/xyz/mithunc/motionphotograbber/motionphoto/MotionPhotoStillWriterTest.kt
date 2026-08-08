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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Checks the still-only output the byte-copy save path produces.
 *
 * [MotionPhotoParser] is the oracle. With the video item stripped, `layOutItems` hits its
 * "no MotionPhoto item" branch and returns [MotionPhoto.NotMotionPhoto] — and it returns
 * *before* the range checks run, so a `Malformed` here would mean the rewrite left the
 * container's arithmetic broken rather than merely emptied. The distinction is the whole
 * point of the test.
 *
 * Two tiers, matching the rest of this module: a synthetic fixture that runs anywhere,
 * plus every real file in the gitignored `samples/`.
 */
class MotionPhotoStillWriterTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `synthetic motion photo loses its video and its declarations`() {
        val source = File(tempDir, "synthetic.jpg")
        source.writeBytes(SyntheticMotionPhoto.build().bytes)
        val found = assertInstanceOf(MotionPhoto.Found::class.java, MotionPhotoParser.parse(source))

        val destination = File(tempDir, "still.jpg")
        assertEquals(
            MotionPhotoStillWriter.Result.Success,
            MotionPhotoStillWriter.writeStill(source, found, destination),
        )

        assertInstanceOf(
            MotionPhoto.NotMotionPhoto::class.java,
            MotionPhotoParser.parse(destination),
            "the parser must disown our own output, and must not call it malformed",
        )
    }

    @Test
    fun `a source that is not a motion photo is refused rather than half written`() {
        // Reaching writeStill with ranges from a different file is a programming error,
        // but it must not leave a plausible-looking file behind for the caller to publish.
        val source = File(tempDir, "plain.jpg")
        source.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(4096))

        val destination = File(tempDir, "still.jpg")
        val result = MotionPhotoStillWriter.writeStill(
            source = source,
            found = MotionPhoto.Found(
                stillByteRange = 0L until 2048L,
                gainMapByteRange = null,
                videoByteRange = 2048L until 4096L,
                defaultFrameTimestampUs = null,
                flavor = Flavor.GOOGLE_CONTAINER,
            ),
            destination = destination,
        )

        assertInstanceOf(MotionPhotoStillWriter.Result.Failed::class.java, result)
        assertFalse(destination.exists(), "a failed write must not leave a file behind")
    }

    @TestFactory
    fun `every sample writes out as an ordinary photo`(): List<DynamicTest> {
        val samples = sampleJpegs()
        if (samples.isEmpty()) {
            return listOf(
                DynamicTest.dynamicTest("no samples present") {
                    assumeTrue(false, "samples/ contains no JPEGs; skipping still-writer checks")
                },
            )
        }
        return samples.map { sample ->
            DynamicTest.dynamicTest(sample.name) { assertStillIsAnOrdinaryPhoto(sample) }
        }
    }

    private fun assertStillIsAnOrdinaryPhoto(sample: File) {
        val found = assertInstanceOf(MotionPhoto.Found::class.java, MotionPhotoParser.parse(sample))
        val destination = File(tempDir, "${sample.nameWithoutExtension}-still.jpg")

        assertEquals(
            MotionPhotoStillWriter.Result.Success,
            MotionPhotoStillWriter.writeStill(sample, found, destination),
        )

        val gainMap = found.gainMapByteRange
        val expectedLength = (gainMap ?: found.stillByteRange).last + 1
        assertEquals(expectedLength, destination.length(), "still + gain map should be copied whole")

        assertInstanceOf(
            MotionPhoto.NotMotionPhoto::class.java,
            MotionPhotoParser.parse(destination),
            "the parser must disown our own output, and must not call it malformed",
        )

        val bytes = destination.readBytes()
        if (gainMap != null) {
            // The edit is same-length by construction, so the gain map has to still start
            // exactly where the source put it. This is the assertion that would catch a
            // regression to recomputing offsets.
            val gainMapLength = gainMap.last - gainMap.first + 1
            val start = (destination.length() - gainMapLength).toInt()
            assertEquals(0xFF.toByte(), bytes[start], "gain map should still start at $start")
            assertEquals(0xD8.toByte(), bytes[start + 1], "gain map should still start at $start")
        }

        val sourceXmp = standardXmp(sample.readBytes())
        val outputXmp = standardXmp(bytes)
        assertEquals(
            sourceXmp.length,
            outputXmp.length,
            "the XMP packet must keep its byte length or every later offset shifts",
        )
        for (declaration in listOf(
            "GCamera:MotionPhoto=",
            "GCamera:MotionPhotoVersion=",
            "GCamera:MotionPhotoPresentationTimestampUs=",
            "video/mp4",
            "MotionPhoto\"",
        )) {
            assertFalse(declaration in outputXmp, "output XMP still declares $declaration")
        }
        // The gain map is only usable if the metadata that describes it survives, which
        // is the whole reason this path exists instead of a re-encode.
        assertTrue("hdrgm:Version" in outputXmp, "output XMP lost its gain-map metadata")
        assertTrue("Item:Semantic=\"Primary\"" in outputXmp, "output XMP lost its Primary item")
        if (gainMap != null) {
            assertTrue("Item:Semantic=\"GainMap\"" in outputXmp, "output XMP lost its GainMap item")
        }
    }

    private fun standardXmp(bytes: ByteArray): String =
        requireNotNull(MotionPhotoParser.findStandardXmpPacket(bytes)) { "no standard XMP packet" }.text

    private fun sampleJpegs(): List<File> {
        val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "samples") }
            .firstOrNull { it.isDirectory }
            ?: return emptyList()
        return dir.listFiles { f: File -> f.isFile && f.name.lowercase().endsWith(".jpg") }
            ?.sortedBy { it.name }
            .orEmpty()
    }
}
