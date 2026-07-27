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
package xyz.mithunc.motionphotograbber.exif

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the Exif block of a JPEG as raw IFD entries, without going through
 * [ExifInterface].
 *
 * This exists because of a specific hole: [ExifInterface] reads back its own malformed
 * writes quite happily. A tag written in the wrong Exif *format* still round-trips
 * through `getAttribute`, so a test built on `getAttribute` cannot see the damage. Only
 * a second, independent reader can — and the corruption is silent until some other
 * program opens the file.
 *
 * That is not hypothetical. Copying `SceneType` produced a tag written as ASCII where
 * the format is UNDEFINED; `getAttribute` returned the value unharmed, while exiftool
 * read `Unknown` where the source said "Directly photographed".
 *
 * Deliberately minimal: enough to enumerate tag ids and format codes, which is what the
 * format contract needs. Values are not decoded.
 */

/** One IFD entry as it appears on disk. */
data class IfdEntry(
    val ifd: String,
    val tagId: Int,
    val format: Int,
    val count: Long,
)

/** Exif format codes, by their on-disk numbering. */
object ExifFormat {
    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val RATIONAL = 5
    const val UNDEFINED = 7
    const val SRATIONAL = 10

    fun name(code: Int): String = when (code) {
        BYTE -> "BYTE"
        ASCII -> "ASCII"
        SHORT -> "SHORT"
        LONG -> "LONG"
        RATIONAL -> "RATIONAL"
        6 -> "SBYTE"
        UNDEFINED -> "UNDEFINED"
        8 -> "SSHORT"
        9 -> "SLONG"
        SRATIONAL -> "SRATIONAL"
        11 -> "FLOAT"
        12 -> "DOUBLE"
        else -> "format $code"
    }
}

/** A tag's numeric id and the formats the Exif specification permits for it. */
data class TagSpec(val id: Int, val formats: Set<Int>)

/**
 * Ids and formats for every tag this module writes.
 *
 * Transcribed from `ExifInterface`'s own `ExifTag` tables in 1.4.2 — the library is the
 * authority on what it will accept, and hand-recalled format codes are exactly the kind
 * of detail CLAUDE.md forbids guessing at. Committed as data rather than read by
 * reflection so it is reviewable and cannot silently change under a version bump.
 */
val TAG_FORMATS: Map<String, TagSpec> = mapOf(
    // GPS IFD
    ExifInterface.TAG_GPS_VERSION_ID to TagSpec(0x0000, setOf(ExifFormat.BYTE)),
    ExifInterface.TAG_GPS_LATITUDE_REF to TagSpec(0x0001, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_LATITUDE to TagSpec(0x0002, setOf(ExifFormat.RATIONAL, ExifFormat.SRATIONAL)),
    ExifInterface.TAG_GPS_LONGITUDE_REF to TagSpec(0x0003, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_LONGITUDE to TagSpec(0x0004, setOf(ExifFormat.RATIONAL, ExifFormat.SRATIONAL)),
    ExifInterface.TAG_GPS_ALTITUDE_REF to TagSpec(0x0005, setOf(ExifFormat.BYTE)),
    ExifInterface.TAG_GPS_ALTITUDE to TagSpec(0x0006, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_TIMESTAMP to TagSpec(0x0007, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_SATELLITES to TagSpec(0x0008, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_STATUS to TagSpec(0x0009, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_MEASURE_MODE to TagSpec(0x000A, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DOP to TagSpec(0x000B, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_SPEED_REF to TagSpec(0x000C, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_SPEED to TagSpec(0x000D, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_TRACK_REF to TagSpec(0x000E, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_TRACK to TagSpec(0x000F, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF to TagSpec(0x0010, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_IMG_DIRECTION to TagSpec(0x0011, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_MAP_DATUM to TagSpec(0x0012, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DEST_LATITUDE_REF to TagSpec(0x0013, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DEST_LATITUDE to TagSpec(0x0014, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_DEST_LONGITUDE_REF to TagSpec(0x0015, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DEST_LONGITUDE to TagSpec(0x0016, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_DEST_BEARING_REF to TagSpec(0x0017, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DEST_BEARING to TagSpec(0x0018, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_DEST_DISTANCE_REF to TagSpec(0x0019, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DEST_DISTANCE to TagSpec(0x001A, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_GPS_PROCESSING_METHOD to TagSpec(0x001B, setOf(ExifFormat.UNDEFINED)),
    ExifInterface.TAG_GPS_AREA_INFORMATION to TagSpec(0x001C, setOf(ExifFormat.UNDEFINED)),
    ExifInterface.TAG_GPS_DATESTAMP to TagSpec(0x001D, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_GPS_DIFFERENTIAL to TagSpec(0x001E, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR to TagSpec(0x001F, setOf(ExifFormat.RATIONAL)),

    // IFD0
    ExifInterface.TAG_IMAGE_DESCRIPTION to TagSpec(0x010E, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_MAKE to TagSpec(0x010F, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_MODEL to TagSpec(0x0110, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_ORIENTATION to TagSpec(0x0112, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_X_RESOLUTION to TagSpec(0x011A, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_Y_RESOLUTION to TagSpec(0x011B, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_RESOLUTION_UNIT to TagSpec(0x0128, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_SOFTWARE to TagSpec(0x0131, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_DATETIME to TagSpec(0x0132, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_ARTIST to TagSpec(0x013B, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_COPYRIGHT to TagSpec(0x8298, setOf(ExifFormat.ASCII)),

    // Exif IFD
    ExifInterface.TAG_F_NUMBER to TagSpec(0x829D, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_DATETIME_ORIGINAL to TagSpec(0x9003, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_DATETIME_DIGITIZED to TagSpec(0x9004, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_OFFSET_TIME to TagSpec(0x9010, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL to TagSpec(0x9011, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED to TagSpec(0x9012, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_APERTURE_VALUE to TagSpec(0x9202, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_MAX_APERTURE_VALUE to TagSpec(0x9205, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_SUBJECT_DISTANCE to TagSpec(0x9206, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_USER_COMMENT to TagSpec(0x9286, setOf(ExifFormat.UNDEFINED)),
    ExifInterface.TAG_SUBSEC_TIME to TagSpec(0x9290, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL to TagSpec(0x9291, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED to TagSpec(0x9292, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_FOCAL_LENGTH to TagSpec(0x920A, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_PIXEL_X_DIMENSION to TagSpec(0xA002, setOf(ExifFormat.SHORT, ExifFormat.LONG)),
    ExifInterface.TAG_PIXEL_Y_DIMENSION to TagSpec(0xA003, setOf(ExifFormat.SHORT, ExifFormat.LONG)),
    ExifInterface.TAG_RELATED_SOUND_FILE to TagSpec(0xA004, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_SENSING_METHOD to TagSpec(0xA217, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_SCENE_TYPE to TagSpec(0xA301, setOf(ExifFormat.UNDEFINED)),
    ExifInterface.TAG_FILE_SOURCE to TagSpec(0xA300, setOf(ExifFormat.UNDEFINED)),
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to TagSpec(0xA405, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_SCENE_CAPTURE_TYPE to TagSpec(0xA406, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE to TagSpec(0xA40C, setOf(ExifFormat.SHORT)),
    ExifInterface.TAG_CAMERA_OWNER_NAME to TagSpec(0xA430, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_BODY_SERIAL_NUMBER to TagSpec(0xA431, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_LENS_SPECIFICATION to TagSpec(0xA432, setOf(ExifFormat.RATIONAL)),
    ExifInterface.TAG_LENS_MAKE to TagSpec(0xA433, setOf(ExifFormat.ASCII)),
    ExifInterface.TAG_LENS_MODEL to TagSpec(0xA434, setOf(ExifFormat.ASCII)),
)

/** Sub-IFD pointers worth following, by tag id. */
private val SUB_IFDS = mapOf(0x8769 to "ExifIFD", 0x8825 to "GPS", 0xA005 to "Interop")

private const val ENTRY_BYTES = 12
/** ASCII `Exif` followed by two NUL bytes: the identifier marking an APP1 as Exif. */
private val EXIF_APP1_PREFIX = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)

/** Every IFD entry in [file]'s Exif APP1 segment, or empty when it carries none. */
fun readIfdEntries(file: File): List<IfdEntry> {
    val tiff = exifApp1Payload(file.readBytes()) ?: return emptyList()
    if (tiff.size < 8) return emptyList()

    val order = when (String(tiff, 0, 2, Charsets.US_ASCII)) {
        "MM" -> ByteOrder.BIG_ENDIAN
        "II" -> ByteOrder.LITTLE_ENDIAN
        else -> return emptyList()
    }
    val buffer = ByteBuffer.wrap(tiff).order(order)

    val entries = mutableListOf<IfdEntry>()
    walkIfd(buffer, buffer.getInt(4), "IFD0", entries, mutableSetOf())
    return entries
}

private fun walkIfd(
    buffer: ByteBuffer,
    offset: Int,
    name: String,
    into: MutableList<IfdEntry>,
    visited: MutableSet<Int>,
) {
    // Guards against a malformed file pointing an IFD at itself.
    if (offset <= 0 || offset + 2 > buffer.capacity() || !visited.add(offset)) return

    val count = buffer.getShort(offset).toInt() and 0xFFFF
    for (i in 0 until count) {
        val at = offset + 2 + i * ENTRY_BYTES
        if (at + ENTRY_BYTES > buffer.capacity()) return
        val tagId = buffer.getShort(at).toInt() and 0xFFFF
        val format = buffer.getShort(at + 2).toInt() and 0xFFFF
        into += IfdEntry(name, tagId, format, buffer.getInt(at + 4).toLong() and 0xFFFFFFFFL)

        SUB_IFDS[tagId]?.let { subName ->
            walkIfd(buffer, buffer.getInt(at + 8), subName, into, visited)
        }
    }
}

/** The TIFF block inside the Exif-identified APP1 segment, or null when absent. */
private fun exifApp1Payload(jpeg: ByteArray): ByteArray? {
    if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
    var i = 2
    while (i + 4 <= jpeg.size) {
        if (jpeg[i] != 0xFF.toByte()) return null
        val marker = jpeg[i + 1].toInt() and 0xFF
        // Standalone markers carry no length field.
        if (marker == 0xFF || marker == 0x01 || marker in 0xD0..0xD7) {
            i += if (marker == 0xFF) 1 else 2
            continue
        }
        // Entropy-coded data begins at SOS; nothing past it is a segment.
        if (marker == 0xDA || marker == 0xD9) return null

        val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
        if (length < 2 || i + 2 + length > jpeg.size) return null
        val payloadStart = i + 4
        val payloadEnd = i + 2 + length
        if (marker == 0xE1 &&
            payloadEnd - payloadStart > EXIF_APP1_PREFIX.size &&
            jpeg.copyOfRange(payloadStart, payloadStart + EXIF_APP1_PREFIX.size)
                .contentEquals(EXIF_APP1_PREFIX)
        ) {
            return jpeg.copyOfRange(payloadStart + EXIF_APP1_PREFIX.size, payloadEnd)
        }
        i = payloadEnd
    }
    return null
}
