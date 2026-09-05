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

import java.io.ByteArrayOutputStream

/**
 * Builds a structurally valid Google/Pixel container motion photo in memory.
 *
 * Why this exists: the real samples in `samples/` are git-ignored because they carry
 * GPS data, so on a fresh clone there is nothing for the parser to run against. This
 * builder gives every contributor exact-value parse coverage with no sample files at
 * all. It is committed as *code* rather than a binary fixture so the bytes it produces
 * are reviewable in a diff.
 *
 * **What it cannot do.** It validates the parser against *our model* of the format, not
 * against reality. If a real Pixel file does something this builder does not imitate,
 * only a real sample will reveal it — that is what the invariant tests over `samples/`
 * are for. Treat a green synthetic test as "the parser is self-consistent", never as
 * "the parser handles real files".
 */
internal object SyntheticMotionPhoto {

    const val DEFAULT_FRAME_TIMESTAMP_US: Long = 500_000L

    private const val XMP_STANDARD_PREFIX = "http://ns.adobe.com/xap/1.0/\u0000"

    /**
     * What the XMP-carrying JPEG is padded to when the directory does not call it the
     * Primary. Comfortably above the ~1.2 KB the packet actually occupies.
     */
    private const val XMP_CARRIER_BYTES = 4096

    /** A synthetic file plus the byte ranges the parser is expected to derive from it. */
    internal class Built(
        val bytes: ByteArray,
        val stillRange: LongRange,
        val gainMapRange: LongRange,
        val videoRange: LongRange,
    )

    /** The three items a Google container motion photo carries. */
    enum class Part { PRIMARY, GAIN_MAP, VIDEO }

    /** Primary first, video last — the only layout the format permits. */
    val CONFORMANT_ORDER: List<Part> = listOf(Part.PRIMARY, Part.GAIN_MAP, Part.VIDEO)

    /**
     * Items are laid out consecutively from offset 0, and only the gain map and video
     * declare a length — the Primary's is implied by subtraction, exactly as real files
     * do it. Because the builder knows every length it wrote, the expected ranges are
     * derived here rather than hardcoded.
     */
    fun build(): Built {
        val video = mp4()
        val gainMap = minimalJpeg()
        val still = jpegCarrying(
            directory(CONFORMANT_ORDER, gainMapLength = gainMap.size, videoLength = video.size)
        )

        val stillEnd = still.size.toLong() - 1
        val gainMapEnd = stillEnd + gainMap.size
        return Built(
            bytes = still + gainMap + video,
            stillRange = 0L..stillEnd,
            gainMapRange = (stillEnd + 1)..gainMapEnd,
            videoRange = (gainMapEnd + 1)..(gainMapEnd + video.size),
        )
    }

    /**
     * A file whose directory lists [order] and whose bytes are laid out to match, so a
     * non-conformant ordering can be rejected for *being* non-conformant rather than for
     * the byte mismatch a directory-only reordering would also produce.
     *
     * Whatever the directory calls it, physical position 0 always holds a JPEG carrying the
     * XMP. A file that does not open with an SOI marker is rejected as not-a-JPEG long
     * before any ordering check, and a fixture that got that wrong would quietly be
     * asserting the wrong rejection.
     */
    fun buildInOrder(order: List<Part>): ByteArray {
        require(order.size == Part.entries.size && order.toSet() == Part.entries.toSet()) {
            "every part must appear exactly once, got $order"
        }
        require(order.first() != Part.VIDEO) {
            "an MP4 at offset 0 is not a JPEG, so the parser would never reach the ordering check"
        }

        val video = mp4()
        val carrier = order.first()
        // The XMP declares a length for every item but the Primary, so an item that both
        // carries the XMP and declares a length would need its own size before the XMP
        // stating it could be written. Padding the carrier to a fixed width cuts that
        // circularity; the Primary needs none, its length being implied by subtraction.
        val gainMapLength =
            if (carrier == Part.GAIN_MAP) XMP_CARRIER_BYTES else minimalJpeg().size
        val xmp = directory(order, gainMapLength = gainMapLength, videoLength = video.size)

        return order.map { part ->
            when (part) {
                Part.PRIMARY ->
                    if (carrier == Part.PRIMARY) jpegCarrying(xmp) else minimalJpeg()
                Part.GAIN_MAP ->
                    if (carrier == Part.GAIN_MAP) padTo(jpegCarrying(xmp), gainMapLength)
                    else minimalJpeg()
                Part.VIDEO -> video
            }
        }.reduce(ByteArray::plus)
    }

    /**
     * A file whose XMP declares a video length that does not match the bytes present.
     *
     * Models the two ways a container can lie about its own layout: a length larger
     * than the whole file, and a length that merely disagrees with reality. The second
     * is the dangerous one — the arithmetic still closes, so nothing is obviously wrong
     * unless the parser checks that the items land on real data.
     */
    fun buildWithDeclaredVideoLength(declaredVideoLength: Int): ByteArray {
        val video = mp4()
        val gainMap = minimalJpeg()
        val still = jpegCarrying(
            directory(
                CONFORMANT_ORDER,
                gainMapLength = gainMap.size,
                videoLength = declaredVideoLength,
            )
        )
        return still + gainMap + video
    }

    /** SOI, an APP1 segment carrying [xmp], then EOI. */
    private fun jpegCarrying(xmp: String): ByteArray {
        val payload = (XMP_STANDARD_PREFIX + xmp).toByteArray(Charsets.UTF_8)
        val segmentLength = payload.size + 2
        require(segmentLength <= 0xFFFF) { "XMP too large for a single APP1 segment" }

        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(0xFF); out.write(0xE1)
        out.write((segmentLength shr 8) and 0xFF)
        out.write(segmentLength and 0xFF)
        out.write(payload)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    /**
     * Zero-pads to exactly [size]. The bytes land after EOI, which is legal filler: an
     * item is only ever checked for how it *starts*.
     */
    private fun padTo(bytes: ByteArray, size: Int): ByteArray {
        require(bytes.size <= size) {
            "$XMP_CARRIER_BYTES is too small for a ${bytes.size}-byte XMP carrier"
        }
        return bytes + ByteArray(size - bytes.size)
    }

    private fun minimalJpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            ByteArray(64) + // stand-in for image data
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /**
     * The directory, listing [order] as `<rdf:li>` entries in exactly that sequence.
     *
     * Attribute order is keyed on the item rather than its position, so it survives a
     * reordering: the MotionPhoto item deliberately orders its attributes differently from
     * the GainMap's. Real files vary here, and a parser that read attributes positionally
     * would pass a uniformly-ordered fixture while failing on a real photo.
     */
    private fun directory(order: List<Part>, gainMapLength: Int, videoLength: Int): String {
        val entries = order.joinToString("\n") { part ->
            val item = when (part) {
                Part.PRIMARY ->
                    """<Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>"""
                Part.GAIN_MAP ->
                    """<Container:Item Item:Length="$gainMapLength" Item:Mime="image/jpeg" Item:Semantic="GainMap"/>"""
                Part.VIDEO ->
                    """<Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>"""
            }
            "             <rdf:li rdf:parseType=\"Resource\">\n" +
                "              $item\n" +
                "             </rdf:li>"
        }
        return """
        <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/">
         <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
          <rdf:Description rdf:about=""
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MotionPhoto="1"
            GCamera:MotionPhotoVersion="1"
            GCamera:MotionPhotoPresentationTimestampUs="$DEFAULT_FRAME_TIMESTAMP_US">
           <Container:Directory>
            <rdf:Seq>
$entries
            </rdf:Seq>
           </Container:Directory>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
        """.trimIndent()
    }

    /** ftyp, moov (carrying an mvhd), and mdat — enough to be a well-formed ISO-BMFF file. */
    private fun mp4(): ByteArray {
        val ftyp = box(
            "ftyp",
            "isom".toByteArray(Charsets.US_ASCII) +
                intBytes(0x00000200) +
                "isom".toByteArray(Charsets.US_ASCII) +
                "mp42".toByteArray(Charsets.US_ASCII),
        )
        // mvhd version 0: 4 bytes version/flags, then creation, modification,
        // timescale, duration — the rest (rate, volume, matrix, next track id) can
        // stay zeroed because nothing in this project reads it.
        val mvhd = ByteArray(100)
        writeInt(mvhd, offset = 12, value = 1000) // timescale: 1000 units per second
        writeInt(mvhd, offset = 16, value = 2000) // duration: 2 seconds
        val moov = box("moov", box("mvhd", mvhd))
        val mdat = box("mdat", ByteArray(128))
        return ftyp + moov + mdat
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        intBytes(8 + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun intBytes(value: Int): ByteArray = ByteArray(4).also { writeInt(it, 0, value) }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
