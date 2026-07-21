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

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Runs the parser against whatever real motion photos happen to be in `samples/`.
 *
 * Unlike [MotionPhotoParserTest], nothing here is tied to a particular file: no names,
 * no sizes, no offsets. Drop in any Pixel motion photo and it gets exercised. That is
 * the point — the exact-offset fixtures only ever run on the machine whose files they
 * describe, so without this tier a contributor's green build would never have parsed a
 * real motion photo at all.
 *
 * Skips only when `samples/` contains no JPEGs, keeping a fresh clone green.
 */
class MotionPhotoSampleInvariantTest {

    @TestFactory
    fun `every sample satisfies the container motion photo invariants`(): List<DynamicTest> {
        val samples = sampleJpegs()
        if (samples.isEmpty()) {
            // A TestFactory must yield at least one node, so emit a single skipping
            // test rather than an empty list, which JUnit reports as a failure.
            return listOf(
                DynamicTest.dynamicTest("no samples present") {
                    assumeTrue(false, "samples/ contains no JPEGs; skipping invariant checks")
                },
            )
        }
        return samples.map { sample ->
            DynamicTest.dynamicTest(sample.name) {
                assertContainerMotionPhotoInvariants(sample)
            }
        }
    }

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
