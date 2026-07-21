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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Exact-value parse coverage that needs no sample files.
 *
 * This is the only tier that runs on a fresh clone, so it must never skip. See
 * [SyntheticMotionPhoto] for what a green result here does and does not prove.
 */
class SyntheticMotionPhotoTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var built: SyntheticMotionPhoto.Built
    private lateinit var file: File

    @BeforeEach
    fun setUp() {
        built = SyntheticMotionPhoto.build()
        file = File(tempDir, "synthetic.MP.jpg").apply { writeBytes(built.bytes) }
    }

    @Test
    fun `derives the exact byte ranges the builder wrote`() {
        val found = MotionPhotoParser.parse(file) as? MotionPhoto.Found
            ?: error("expected Found, got ${MotionPhotoParser.parse(file)}")

        assertEquals(built.stillRange, found.stillByteRange, "still range")
        assertEquals(built.gainMapRange, found.gainMapByteRange, "gain map range")
        assertEquals(built.videoRange, found.videoByteRange, "video range")
        assertEquals(Flavor.GOOGLE_CONTAINER, found.flavor)
    }

    @Test
    fun `reads the default frame timestamp from the container XMP`() {
        val found = MotionPhotoParser.parse(file) as MotionPhoto.Found

        assertEquals(SyntheticMotionPhoto.DEFAULT_FRAME_TIMESTAMP_US, found.defaultFrameTimestampUs)
    }

    @Test
    fun `satisfies the same invariants asserted against real samples`() {
        // Sharing the assertions with MotionPhotoSampleInvariantTest is what makes the
        // synthetic fixture meaningful: the two tiers agree on what a correct parse
        // looks like, so a divergence between synthetic and real input shows up as a
        // failure rather than as a silent difference in test rigour.
        assertContainerMotionPhotoInvariants(file)
    }

    @Test
    fun `parsing the bytes directly agrees with parsing the file`() {
        assertEquals(MotionPhotoParser.parse(file), MotionPhotoParser.parse(built.bytes))
    }
}
