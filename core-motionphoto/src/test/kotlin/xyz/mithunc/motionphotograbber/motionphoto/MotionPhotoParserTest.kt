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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Expected parse results for one real sample file.
 *
 * These offsets were measured from the actual files, not derived from the parser,
 * so they are an independent check rather than a restatement of the implementation.
 * `samples/` is git-ignored (the files carry real GPS data), so every test here
 * skips rather than fails when its file is absent — see `samples/README.md`.
 */
data class Fixture(
    val name: String,
    val size: Long,
    val still: LongRange,
    val gainMap: LongRange,
    val video: LongRange,
    val defaultFrameTimestampUs: Long,
)

val FIXTURES = listOf(
    Fixture(
        name = "PXL_20260717_011104182.MP.jpg",
        size = 8_525_923,
        still = 0L..4_392_644L,
        gainMap = 4_392_645L..4_528_530L,
        video = 4_528_531L..8_525_922L,
        defaultFrameTimestampUs = 1_399_844,
    ),
    Fixture(
        name = "PXL_20260717_051005777.MP.jpg",
        size = 4_949_039,
        still = 0L..1_690_937L,
        gainMap = 1_690_938L..1_726_894L,
        video = 1_726_895L..4_949_038L,
        defaultFrameTimestampUs = 848_258,
    ),
    // Attribute order inside <Container:Item> differs in this file from the other
    // two, which is why it is a fixture rather than a duplicate of them.
    Fixture(
        name = "PXL_20260718_091528579.MP.jpg",
        size = 3_848_655,
        still = 0L..2_129_122L,
        gainMap = 2_129_123L..2_166_400L,
        video = 2_166_401L..3_848_654L,
        defaultFrameTimestampUs = 201_232,
    ),
)

/** Locates `samples/` by walking up from the module directory. */
private fun sampleFile(name: String): File? =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "samples/$name") }
        .firstOrNull { it.isFile }

class MotionPhotoParserTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixturesForTest")
    fun `parses a Pixel container motion photo into exact byte ranges`(fixture: Fixture) {
        val file = sampleFile(fixture.name)
        assumeTrue(file != null, "sample ${fixture.name} not present; skipping")

        val result = MotionPhotoParser.parse(file!!)

        assertEquals(fixture.size, file.length(), "sample file size changed unexpectedly")
        val found = result as? MotionPhoto.Found
            ?: error("expected Found for ${fixture.name}, got $result")

        assertEquals(Flavor.GOOGLE_CONTAINER, found.flavor)
        assertEquals(fixture.still, found.stillByteRange, "still range")
        assertEquals(fixture.gainMap, found.gainMapByteRange, "gain map range")
        assertEquals(fixture.video, found.videoByteRange, "video range")
        assertEquals(
            fixture.defaultFrameTimestampUs,
            found.defaultFrameTimestampUs,
            "default frame timestamp",
        )
    }

    companion object {
        @JvmStatic
        fun fixturesForTest(): List<Fixture> = FIXTURES
    }
}
