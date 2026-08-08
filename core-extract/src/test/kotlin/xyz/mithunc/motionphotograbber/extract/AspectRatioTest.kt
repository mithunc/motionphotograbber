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
package xyz.mithunc.motionphotograbber.extract

import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The arithmetic behind sizing the preview surface.
 *
 * Worth its own tier because getting it wrong is invisible in code review and subtle on
 * screen: the bug this replaced stretched every preview by 12.6% vertically, which passed a
 * human eyeball and only failed a correlation against the true frame.
 */
@UnstableApi
class AspectRatioTest {

    @Test
    fun `portrait video reports a portrait ratio`() {
        // The shape of every sample clip once Media3 has applied its rotation.
        assertEquals(0.75f, aspectRatioOf(VideoSize(1080, 1440))!!, 1e-4f)
    }

    @Test
    fun `landscape video reports a landscape ratio`() {
        assertEquals(1440f / 1080f, aspectRatioOf(VideoSize(1440, 1080))!!, 1e-4f)
    }

    @Test
    fun `pixel aspect ratio widens a non-square-pixelled frame`() {
        // Anamorphic: 720x480 stored pixels displayed at 4:3 via a 1.333 pixel ratio.
        assertEquals(720f * 1.3333f / 480f, aspectRatioOf(VideoSize(720, 480, 1.3333f))!!, 1e-4f)
    }

    @Test
    fun `a non-positive pixel ratio is treated as square rather than as zero`() {
        // Media3 uses a non-positive pixelWidthHeightRatio to mean "unknown". Multiplying by
        // it would collapse the ratio to 0 and give the surface no width at all.
        assertEquals(0.75f, aspectRatioOf(VideoSize(1080, 1440, 0f))!!, 1e-4f)
    }

    @Test
    fun `unknown size yields null so the caller can fall back`() {
        assertNull(aspectRatioOf(VideoSize.UNKNOWN))
        assertNull(aspectRatioOf(VideoSize(0, 1440)))
        assertNull(aspectRatioOf(VideoSize(1080, 0)))
    }
}
