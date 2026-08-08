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
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Writes a motion photo's still — Primary plus GainMap — out as an ordinary photo.
 *
 * This is the path taken when the user saves the frame the camera itself picked. Nothing
 * is decoded or re-encoded, so the output keeps the original's full resolution, its Ultra
 * HDR gain map, and its EXIF exactly as the camera wrote them. A mid-clip frame cannot
 * have any of that; the shutter-press frame can, and throwing it away to re-encode an
 * 8x smaller video frame would be perverse.
 *
 * **It is not a pure byte copy.** The Primary's XMP declares a `Container:Directory` whose
 * third item is the video, and `GCamera:MotionPhoto="1"` announces the file as a motion
 * photo. Copy those through and the output advertises a clip it does not contain:
 * [MotionPhotoParser] would call the result `Malformed`, and a gallery would likely offer
 * a play button that does nothing. So the XMP is edited on the way out.
 */
object MotionPhotoStillWriter {

    /**
     * How much of the file to search for the XMP packet.
     *
     * Matches [MotionPhotoParser]'s own header window: the packet has already been found
     * within it once, by the parse that produced the [MotionPhoto.Found] passed in here.
     */
    private const val MAX_HEADER_BYTES: Int = 1 shl 20

    private const val COPY_BUFFER_BYTES = 64 * 1024

    /**
     * The `<rdf:li>` wrapper around the video's `Container:Item`.
     *
     * Matched as a whole element rather than by locating `Container:Item` and walking
     * outward: leaving an empty `<rdf:li rdf:parseType="Resource"></rdf:li>` behind would
     * declare a fourth, contentless item to any reader stricter than ours.
     *
     * `[\s\S]` rather than `.` with DOT_MATCHES_ALL so the intent is local to the pattern;
     * the element spans several lines in every real file.
     */
    private val MOTION_PHOTO_ITEM = Regex(
        "<rdf:li\\b[^>]*>\\s*<Container:Item\\b[^>]*(?:Item:Semantic=\"MotionPhoto\"|" +
            "Item:Mime=\"video/mp4\")[\\s\\S]*?</rdf:li>"
    )

    /**
     * The GCamera attributes that announce a motion photo independently of the container.
     *
     * These sit on `rdf:Description`, *outside* `Container:Directory`, which is why
     * dropping the item alone is not enough. `MotionPhotoPresentationTimestampUs` is
     * included because it describes where the shutter press sat inside a clip the output
     * no longer has.
     */
    private val MOTION_PHOTO_ATTRIBUTES = Regex(
        "\\s*GCamera:MotionPhoto(?:Version|PresentationTimestampUs)?\\s*=\\s*\"[^\"]*\""
    )

    /** The outcome of writing a motion photo's still out on its own. */
    sealed interface Result {

        data object Success : Result

        /** The source could not be read, or the destination could not be written. */
        data class Failed(val reason: String) : Result
    }

    /**
     * Writes the still of [source] to [destination], overwriting it if it exists.
     *
     * @param found must have come from parsing [source]; its ranges are byte offsets into
     *   that exact file.
     */
    fun writeStill(source: File, found: MotionPhoto.Found, destination: File): Result {
        // Items are laid out consecutively from offset 0 with the video last, so the
        // still and its gain map are one contiguous run at the front — no stitching.
        val stillEnd = (found.gainMapByteRange ?: found.stillByteRange).last
        val stillLength = stillEnd + 1

        val failure = try {
            copyPrefix(source, destination, stillLength)
            stripMotionPhotoDeclarations(destination)
        } catch (e: IOException) {
            e.message ?: "could not write ${destination.name}"
        }
        if (failure != null) {
            // Never leave a half-written still behind: the caller's next step is to
            // publish this file to the gallery, and a file that exists is one it could
            // publish.
            destination.delete()
            return Result.Failed(failure)
        }
        return Result.Success
    }

    private fun copyPrefix(source: File, destination: File, length: Long) {
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) throw IOException("source ended before the still did")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    /**
     * Blanks every motion-photo declaration in [file]'s XMP, in place.
     *
     * Returns null on success, or a reason string.
     *
     * **Each deleted span is replaced by exactly as many spaces**, so the packet's byte
     * length never changes. That is what makes this safe: the APP1 segment's length field
     * stays correct without being recomputed, the Primary's byte length is unchanged, so
     * the GainMap still begins at the same offset and its declared `Item:Length` is still
     * true. The Primary declares no length — it is whatever the other items leave over —
     * so it re-derives to the same value against the now-smaller file. No offset in the
     * output is computed, and therefore none can be computed wrong.
     *
     * XML ignores whitespace between elements and between attributes, so the padding is
     * invisible to a reader.
     */
    private fun stripMotionPhotoDeclarations(file: File): String? {
        RandomAccessFile(file, "rw").use { raf ->
            val window = ByteArray(minOf(raf.length(), MAX_HEADER_BYTES.toLong()).toInt())
            raf.readFully(window)

            val packet = MotionPhotoParser.findStandardXmpPacket(window)
                ?: return "no XMP packet in the copied still"

            // ISO-8859-1, not UTF-8: it maps every byte to exactly one char and back, so
            // string indices are byte offsets. The patterns and the replacement are pure
            // ASCII, and any non-ASCII bytes elsewhere in the packet survive untouched.
            val text = String(window, packet.offset, packet.length, Charsets.ISO_8859_1)
            val edited = text
                .replace(MOTION_PHOTO_ITEM) { " ".repeat(it.value.length) }
                .replace(MOTION_PHOTO_ATTRIBUTES) { " ".repeat(it.value.length) }

            if (edited == text) return "XMP declares no motion photo to strip"
            // Insurance against a future pattern edit that changes the length. Shifting
            // the GainMap's start by even one byte is exactly the failure this whole
            // same-length design exists to rule out, and it would not surface until
            // someone opened a saved file. Refusing to write beats writing it.
            if (edited.length != text.length) {
                return "XMP edit changed the packet length ${text.length} -> ${edited.length}"
            }

            raf.seek(packet.offset.toLong())
            raf.write(edited.toByteArray(Charsets.ISO_8859_1))
        }
        return null
    }
}
