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

    /** A synthetic file plus the byte ranges the parser is expected to derive from it. */
    internal class Built(
        val bytes: ByteArray,
        val stillRange: LongRange,
        val gainMapRange: LongRange,
        val videoRange: LongRange,
    )

    /**
     * Items are laid out consecutively from offset 0, and only the gain map and video
     * declare a length — the Primary's is implied by subtraction, exactly as real files
     * do it. Because the builder knows every length it wrote, the expected ranges are
     * derived here rather than hardcoded.
     */
    fun build(): Built {
        val video = mp4()
        val gainMap = minimalJpeg()
        val still = primaryJpeg(gainMapLength = gainMap.size, videoLength = video.size)

        val stillEnd = still.size.toLong() - 1
        val gainMapEnd = stillEnd + gainMap.size
        return Built(
            bytes = still + gainMap + video,
            stillRange = 0L..stillEnd,
            gainMapRange = (stillEnd + 1)..gainMapEnd,
            videoRange = (gainMapEnd + 1)..(gainMapEnd + video.size),
        )
    }

    /** SOI, an APP1 segment carrying the container directory, then EOI. */
    private fun primaryJpeg(gainMapLength: Int, videoLength: Int): ByteArray {
        val payload = (XMP_STANDARD_PREFIX + xmp(gainMapLength, videoLength))
            .toByteArray(Charsets.UTF_8)
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

    private fun minimalJpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            ByteArray(64) + // stand-in for image data
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /**
     * The third item deliberately orders its attributes differently from the second.
     * Real files vary here, and a parser that reads attributes positionally would pass
     * a uniformly-ordered fixture while failing on a real photo.
     */
    private fun xmp(gainMapLength: Int, videoLength: Int): String = """
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
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/>
             </rdf:li>
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Length="$gainMapLength" Item:Mime="image/jpeg" Item:Semantic="GainMap"/>
             </rdf:li>
             <rdf:li rdf:parseType="Resource">
              <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
             </rdf:li>
            </rdf:Seq>
           </Container:Directory>
          </rdf:Description>
         </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

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
