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

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Corrupted input: truncated files, and containers whose declared lengths do not match
 * the bytes actually present.
 *
 * Both are realistic rather than theoretical. A transfer interrupted partway leaves a
 * truncated file, and an editor that parses to the JPEG `EOI` and rewrites only what it
 * understood leaves a container still advertising a video that is no longer there.
 *
 * The contract these tests pin down: **never throw, and never return a range that does
 * not point at real data.** A plausible-looking wrong range is worse than an error,
 * because everything downstream — the frame extractor, the byte-copy save path — would
 * act on it.
 */
class MotionPhotoParserCorruptionTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `file truncated inside the video is malformed`() {
        val built = SyntheticMotionPhoto.build()
        // Lose the last 100 bytes: the container still declares the full video length,
        // but those bytes no longer exist.
        val truncated = built.bytes.copyOf(built.bytes.size - 100)

        val result = MotionPhotoParser.parse(truncated)

        assertInstanceOf(MotionPhoto.Malformed::class.java, result)
    }

    @Test
    fun `file truncated inside the video is malformed when read from disk`() {
        val built = SyntheticMotionPhoto.build()
        val file = File(tempDir, "truncated.MP.jpg")
        file.writeBytes(built.bytes.copyOf(built.bytes.size - 100))

        val result = MotionPhotoParser.parse(file)

        assertInstanceOf(MotionPhoto.Malformed::class.java, result)
    }

    @Test
    fun `XMP claiming a video larger than the whole file is malformed`() {
        val bytes = SyntheticMotionPhoto.buildWithDeclaredVideoLength(50_000_000)

        val result = MotionPhotoParser.parse(bytes)

        assertInstanceOf(MotionPhoto.Malformed::class.java, result)
    }

    @Test
    fun `XMP declaring a video length that disagrees with the file is malformed`() {
        // The subtle case: small enough that the implied-Primary arithmetic still
        // closes, so every range looks reasonable while pointing at the wrong bytes.
        val real = SyntheticMotionPhoto.build()
        val realVideoLength = (real.videoRange.last - real.videoRange.first + 1).toInt()
        val bytes = SyntheticMotionPhoto.buildWithDeclaredVideoLength(realVideoLength - 64)

        val result = MotionPhotoParser.parse(bytes)

        assertInstanceOf(MotionPhoto.Malformed::class.java, result)
    }

    @Test
    fun `truncating at any offset never throws`() {
        val built = SyntheticMotionPhoto.build()

        for (size in 0..built.bytes.size) {
            val prefix = built.bytes.copyOf(size)
            // The assertion is that this call returns at all. Any of the four results
            // is acceptable; an exception escaping the parser is not.
            val result = MotionPhotoParser.parse(prefix)
            assertNotNull(result, "parsing a $size-byte prefix returned null")
        }
    }

    @Test
    fun `truncating at any offset never throws when read from disk`() {
        val built = SyntheticMotionPhoto.build()
        val file = File(tempDir, "prefix.MP.jpg")

        // Step rather than exhaustive: each iteration is a real file write, and the
        // byte-array test above already covers every offset.
        for (size in 0..built.bytes.size step 7) {
            file.writeBytes(built.bytes.copyOf(size))
            val result = MotionPhotoParser.parse(file)
            assertNotNull(result, "parsing a $size-byte file returned null")
        }
    }
}
