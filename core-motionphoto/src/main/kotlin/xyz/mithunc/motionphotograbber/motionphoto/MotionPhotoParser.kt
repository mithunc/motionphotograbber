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

import java.io.File

/**
 * Locates the still, gain map, and embedded video inside a motion photo.
 *
 * Pure JVM: takes bytes or a [File], never a `Context` or `Uri`, so it is unit-testable
 * against real sample files. See SPEC.md "What we know about the format" for the
 * measurements this implementation relies on.
 */
object MotionPhotoParser {

    /**
     * The container directory lives in an APP1 segment near the front of the file, so
     * only a prefix needs reading. Well clear of the ~120 KB of leading segments
     * observed in real files, while keeping an 8 MB photo off the heap.
     */
    private const val MAX_HEADER_BYTES: Int = 1 shl 20

    private const val SCAN_BUFFER_BYTES = 64 * 1024

    /** SOI plus a marker and its length field — below this nothing can be a JPEG. */
    private const val MIN_JPEG_BYTES = 4L

    private val XMP_STANDARD_PREFIX: ByteArray =
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    private val ITEM_ELEMENT = Regex("<Container:Item\\b([^>]*)>")
    private val ITEM_ATTRIBUTE = Regex("Item:(\\w+)\\s*=\\s*\"([^\"]*)\"")
    private val DEFAULT_FRAME_TIMESTAMP =
        Regex("MotionPhotoPresentationTimestampUs\\s*=\\s*\"(-?\\d+)\"")
    private val MICRO_VIDEO_OFFSET = Regex("GCamera:MicroVideoOffset\\s*=\\s*\"(-?\\d+)\"")

    private val SAMSUNG_MARKER: ByteArray = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    private data class Item(val semantic: String?, val mime: String?, val length: Long?)

    fun parse(file: File): MotionPhoto {
        val totalSize = file.length()
        if (totalSize < MIN_JPEG_BYTES) {
            return MotionPhoto.NotMotionPhoto("file is too small to be a JPEG")
        }
        val header = ByteArray(minOf(totalSize, MAX_HEADER_BYTES.toLong()).toInt())
        file.inputStream().use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
        }
        return withSamsungFallback(parse(header, totalSize)) { containsSamsungMarker(file) }
    }

    fun parse(bytes: ByteArray): MotionPhoto =
        withSamsungFallback(parse(bytes, bytes.size.toLong())) {
            indexOf(bytes, SAMSUNG_MARKER, 0, bytes.size) >= 0
        }

    /**
     * The Samsung marker sits after a complete JPEG — megabytes past the header window
     * — so finding it means scanning the whole input. Only worth doing once nothing
     * cheaper has matched, hence the lazy predicate.
     */
    private inline fun withSamsungFallback(
        result: MotionPhoto,
        hasMarker: () -> Boolean,
    ): MotionPhoto =
        if (result is MotionPhoto.NotMotionPhoto && hasMarker()) {
            MotionPhoto.NotSupported(
                Flavor.SAMSUNG_MARKER,
                "Samsung motion photos are detected but not parsed yet",
            )
        } else {
            result
        }

    private fun parse(header: ByteArray, totalSize: Long): MotionPhoto {
        if (!hasJpegStartOfImage(header)) {
            return MotionPhoto.NotMotionPhoto("not a JPEG (no SOI marker)")
        }
        val xmp = findStandardXmp(header)
            ?: return MotionPhoto.NotMotionPhoto("no XMP packet in the leading JPEG segments")

        val items = parseContainerItems(xmp)
        if (items.isEmpty()) {
            if (MICRO_VIDEO_OFFSET.containsMatchIn(xmp)) {
                return MotionPhoto.NotSupported(
                    Flavor.GOOGLE_MICROVIDEO,
                    "legacy GCamera:MicroVideoOffset files are detected but not parsed yet",
                )
            }
            return MotionPhoto.NotMotionPhoto("XMP has no Container:Item entries")
        }
        return layOutItems(items, xmp, totalSize)
    }

    private fun hasJpegStartOfImage(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /**
     * Returns the payload of the standard XMP APP1 segment, or null if absent.
     *
     * Deliberately ignores *extended* XMP (`.../xmp/extension/`): in real files that
     * holds only `HDRPlusMakerNote`, so the container directory never needs the
     * multi-segment chunk reassembly extended XMP would otherwise demand.
     */
    private fun findStandardXmp(header: ByteArray): String? {
        var i = 2
        while (i + 3 < header.size) {
            if (header[i] != 0xFF.toByte()) return null
            val marker = header[i + 1].toInt() and 0xFF
            // A run of 0xFF bytes before a marker is legal padding.
            if (marker == 0xFF) {
                i++
                continue
            }
            // Standalone markers carry no length field.
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            // SOS begins entropy-coded data and EOI ends the image; the container
            // directory always appears before either, so stop rather than scan on.
            if (marker == 0xDA || marker == 0xD9) return null

            val length = ((header[i + 2].toInt() and 0xFF) shl 8) or (header[i + 3].toInt() and 0xFF)
            if (length < 2) return null
            val payloadStart = i + 4
            val payloadEnd = i + 2 + length
            if (payloadEnd > header.size) return null

            if (marker == 0xE1 && startsWith(header, payloadStart, payloadEnd, XMP_STANDARD_PREFIX)) {
                return String(
                    header,
                    payloadStart + XMP_STANDARD_PREFIX.size,
                    payloadEnd - payloadStart - XMP_STANDARD_PREFIX.size,
                    Charsets.UTF_8,
                )
            }
            i = payloadEnd
        }
        return null
    }

    /**
     * Streams the file looking for the Samsung marker, carrying `marker.size - 1` bytes
     * between reads so a marker straddling a buffer boundary is still found.
     */
    private fun containsSamsungMarker(file: File): Boolean {
        val overlap = SAMSUNG_MARKER.size - 1
        val buffer = ByteArray(SCAN_BUFFER_BYTES + overlap)
        return file.inputStream().buffered().use { stream ->
            var carried = 0
            var found = false
            while (!found) {
                val read = stream.read(buffer, carried, buffer.size - carried)
                if (read <= 0) break
                val filled = carried + read
                found = indexOf(buffer, SAMSUNG_MARKER, 0, filled) >= 0
                carried = minOf(overlap, filled)
                System.arraycopy(buffer, filled - carried, buffer, 0, carried)
            }
            found
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, until: Int): Int {
        outer@ for (start in from..until - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun startsWith(bytes: ByteArray, from: Int, until: Int, prefix: ByteArray): Boolean {
        if (until - from < prefix.size) return false
        for (k in prefix.indices) if (bytes[from + k] != prefix[k]) return false
        return true
    }

    /**
     * Attribute order inside `<Container:Item>` varies between real files, so
     * attributes are read into a map rather than matched positionally.
     */
    private fun parseContainerItems(xmp: String): List<Item> =
        ITEM_ELEMENT.findAll(xmp).map { element ->
            val attributes = ITEM_ATTRIBUTE.findAll(element.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2] }
            Item(
                semantic = attributes["Semantic"],
                mime = attributes["Mime"],
                length = attributes["Length"]?.toLongOrNull(),
            )
        }.toList()

    private fun layOutItems(items: List<Item>, xmp: String, totalSize: Long): MotionPhoto {
        // The Primary item declares no length: it is whatever the other items leave
        // over. More than one undeclared length would make the layout ambiguous.
        val undeclared = items.count { it.length == null }
        if (undeclared > 1) {
            return MotionPhoto.Malformed("$undeclared container items declare no Item:Length")
        }
        val declaredTotal = items.sumOf { it.length ?: 0L }
        if (declaredTotal > totalSize) {
            return MotionPhoto.Malformed(
                "container items declare $declaredTotal bytes but the file is $totalSize"
            )
        }
        val impliedLength = totalSize - declaredTotal

        var offset = 0L
        val ranges = ArrayList<LongRange>(items.size)
        for (item in items) {
            val length = item.length ?: impliedLength
            if (length <= 0L) {
                return MotionPhoto.Malformed("container item has non-positive length $length")
            }
            ranges += offset until offset + length
            offset += length
        }
        if (offset != totalSize) {
            return MotionPhoto.Malformed("container items span $offset bytes, file is $totalSize")
        }

        val videoIndex = items.indexOfFirst { it.semantic == "MotionPhoto" || it.mime == "video/mp4" }
        if (videoIndex < 0) {
            return MotionPhoto.NotMotionPhoto("container declares no MotionPhoto item")
        }
        val stillIndex = items.indexOfFirst { it.semantic == "Primary" }
        if (stillIndex < 0) {
            return MotionPhoto.Malformed("container declares no Primary item")
        }
        val gainMapIndex = items.indexOfFirst { it.semantic == "GainMap" }

        return MotionPhoto.Found(
            stillByteRange = ranges[stillIndex],
            gainMapByteRange = gainMapIndex.takeIf { it >= 0 }?.let { ranges[it] },
            videoByteRange = ranges[videoIndex],
            defaultFrameTimestampUs = DEFAULT_FRAME_TIMESTAMP.find(xmp)
                ?.groupValues?.get(1)?.toLongOrNull(),
            flavor = Flavor.GOOGLE_CONTAINER,
        )
    }
}
