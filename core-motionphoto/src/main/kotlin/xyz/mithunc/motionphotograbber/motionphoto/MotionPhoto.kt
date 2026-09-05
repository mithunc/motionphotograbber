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

/** The container layout a motion photo uses to embed its video. */
enum class Flavor {
    /** Google/Pixel XMP `Container:Directory` with per-item lengths. The only flavor parsed. */
    GOOGLE_CONTAINER,

    /** Legacy Google XMP `GCamera:MicroVideoOffset`, measured from the end of the file. */
    GOOGLE_MICROVIDEO,

    /** Samsung: a complete JPEG, the ASCII marker `MotionPhoto_Data`, then a complete MP4. */
    SAMSUNG_MARKER,

    /** HEIC container with embedded video tracks. */
    HEIC_CONTAINER,
}

/** The result of inspecting a file for an embedded motion photo video. */
sealed interface MotionPhoto {

    /**
     * A motion photo whose parts have been located.
     *
     * All ranges are absolute byte offsets into the source file and are inclusive at
     * both ends, so they can be used directly for byte-copying without adjustment.
     *
     * **The items are in conformant order:** [stillByteRange] starts at 0 and
     * [videoByteRange] ends at the last byte of the file, with everything else packed
     * between them. The format requires it and the parser rejects containers that break
     * it, so callers may treat the still and its gain map as a contiguous prefix — which
     * is what makes recovering the original still a plain byte copy.
     */
    data class Found(
        val stillByteRange: LongRange,
        /** Null when the file carries no gain map, i.e. the still is not Ultra HDR. */
        val gainMapByteRange: LongRange?,
        val videoByteRange: LongRange,
        /**
         * Where the shutter-press frame sits within the clip. Null when the file does
         * not declare it. Used to decide whether a scrub position is close enough to
         * the default frame to byte-copy the original still instead of re-encoding.
         */
        val defaultFrameTimestampUs: Long?,
        val flavor: Flavor,
    ) : MotionPhoto

    /**
     * Definitely a motion photo, but of a flavor this build does not parse.
     */
    data class NotSupported(val flavor: Flavor, val reason: String) : MotionPhoto

    /** A readable file with no embedded video — an ordinary photo. */
    data class NotMotionPhoto(val reason: String) : MotionPhoto

    /** Claims to be a motion photo but its structure does not hold together. */
    data class Malformed(val reason: String) : MotionPhoto
}
