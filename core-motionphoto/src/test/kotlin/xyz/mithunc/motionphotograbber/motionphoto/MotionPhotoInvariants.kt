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
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.io.RandomAccessFile

/**
 * Properties that hold for *any* valid Google/Pixel container motion photo.
 *
 * Deliberately free of hardcoded filenames and offsets, so the same assertions run
 * against a synthetic fixture and against whatever real samples a contributor happens
 * to supply. That shared use is the point: if an invariant passes on synthetic input
 * but fails on a real file, our model of the format is wrong — which is exactly what
 * the synthetic fixture on its own could never tell us.
 */
internal fun assertContainerMotionPhotoInvariants(file: File): MotionPhoto.Found {
    val result = MotionPhotoParser.parse(file)
    val found = result as? MotionPhoto.Found
        ?: error("${file.name}: expected Found, got $result")

    assertEquals(Flavor.GOOGLE_CONTAINER, found.flavor, "${file.name}: flavor")

    // The items tile the file exactly: first starts at 0, each begins where the
    // previous ended, and the last byte of the video is the last byte of the file.
    assertEquals(0L, found.stillByteRange.first, "${file.name}: still must start at 0")

    val gainMap = found.gainMapByteRange
    if (gainMap != null) {
        assertEquals(
            found.stillByteRange.last + 1,
            gainMap.first,
            "${file.name}: gain map must begin where the still ends",
        )
    }
    val expectedVideoStart = (gainMap ?: found.stillByteRange).last + 1
    assertEquals(
        expectedVideoStart,
        found.videoByteRange.first,
        "${file.name}: video must begin where the preceding item ends",
    )
    assertEquals(
        file.length() - 1,
        found.videoByteRange.last,
        "${file.name}: video must run to the last byte of the file",
    )

    RandomAccessFile(file, "r").use { raf ->
        // Each image item is itself a complete JPEG, so it opens with SOI.
        assertEquals(
            "FFD8",
            raf.hexAt(found.stillByteRange.first, 2),
            "${file.name}: still must start with a JPEG SOI marker",
        )
        if (gainMap != null) {
            assertEquals(
                "FFD8",
                raf.hexAt(gainMap.first, 2),
                "${file.name}: gain map must start with a JPEG SOI marker",
            )
        }
        // The strongest check available: an ISO-BMFF file carries its `ftyp` box type
        // at bytes 4..8. This confirms the computed offset landed on a real MP4 header
        // without the test needing to know what the offset should have been — and it
        // is the check that would catch a parser fooled by an `ftyp` byte sequence
        // occurring inside entropy-coded JPEG data.
        assertEquals(
            "ftyp",
            raf.asciiAt(found.videoByteRange.first + 4, 4),
            "${file.name}: video must begin with an ISO-BMFF ftyp box",
        )
    }

    found.defaultFrameTimestampUs?.let {
        assertTrue(it >= 0, "${file.name}: default frame timestamp must not be negative")
    }
    return found
}

private fun RandomAccessFile.readAt(offset: Long, count: Int): ByteArray {
    seek(offset)
    val buffer = ByteArray(count)
    readFully(buffer)
    return buffer
}

private fun RandomAccessFile.hexAt(offset: Long, count: Int): String =
    readAt(offset, count).joinToString("") { "%02X".format(it) }

private fun RandomAccessFile.asciiAt(offset: Long, count: Int): String =
    String(readAt(offset, count), Charsets.US_ASCII)
