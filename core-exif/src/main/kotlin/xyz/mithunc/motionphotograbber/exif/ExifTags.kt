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

/*
 * Which metadata moves from a motion photo onto a frame extracted from its clip.
 *
 * The governing rule is that **every tag on the output must be true of the output**.
 * Tags fall into four dispositions, and this file is organized by disposition rather
 * than by IFD so that "why isn't X here?" is answerable without leaving the file:
 *
 *   1. Copied verbatim  — still true of the frame. That is [SUPPORTED_TAGS].
 *   2. Derived          — true but shifted; see [DERIVED_TIMESTAMPS].
 *   3. Computed         — measured from the output itself: the pixel dimensions, written
 *                         by ExifMetadataCopier rather than listed here.
 *   4. Constant         — Orientation and Software, written by ExifMetadataCopier.
 *
 * Why an explicit list rather than a bulk copy: ExifInterface offers getAttribute and
 * setAttribute and nothing that enumerates the tags a file actually holds — the backing
 * maps are private. So the only copyable tags are the ones named here. There is no bulk
 * option being declined. (Reflecting over the ~161 TAG_* constants would enumerate them,
 * and is rejected: fragile across upgrades, unreadable, and it would drag in the whole
 * must-not-copy set below.)
 *
 * The list was built by triaging every one of those 161 constants, not from recall.
 * The set is identical in exifinterface 1.3.6 and 1.4.2. Deliberately omitted:
 *
 *   Describe the source's bytes. The frame is ~1080x1440 against a ~3072x4080 still, so
 *   these would make the file lie about itself and point thumbnail offsets into
 *   unrelated data: ImageWidth, ImageLength, BitsPerSample, Compression,
 *   CompressedBitsPerPixel, PhotometricInterpretation, SamplesPerPixel,
 *   PlanarConfiguration, RowsPerStrip, StripOffsets, StripByteCounts,
 *   JPEGInterchangeFormat(Length), ThumbnailImage*, ThumbnailOrientation,
 *   YCbCrSubSampling, YCbCrPositioning, and the still-frame coordinates SubjectArea and
 *   SubjectLocation. PixelXDimension/PixelYDimension are absent here only because they
 *   are computed from the output instead.
 *
 *   Per-exposure values that genuinely differed. Same sensor and same burst, but the clip
 *   ran its own auto-exposure and Media3 exposes no per-frame exposure data to substitute:
 *   ExposureTime, ShutterSpeedValue, BrightnessValue, ExposureBiasValue, ExposureProgram,
 *   ExposureMode, ExposureIndex, PhotographicSensitivity, SensitivityType,
 *   StandardOutputSensitivity, RecommendedExposureIndex, ISOSpeed, ISOSpeedLatitude*,
 *   MeteringMode, LightSource, WhiteBalance, Flash, FlashEnergy, GainControl, Contrast,
 *   Saturation, Sharpness, CustomRendered, SpectralSensitivity.
 *
 *   Framing and sensor readout differ — the clip is a differently cropped, binned
 *   readout: DigitalZoomRatio, FocalPlaneXResolution, FocalPlaneYResolution,
 *   FocalPlaneResolutionUnit.
 *
 *   Xmp. Copying it would be an outright bug: the source's XMP *is* the
 *   Container:Directory, so the frame would advertise an embedded video and gain map at
 *   offsets that do not exist within it, and :core-motionphoto would then "find" a motion
 *   photo inside a plain still.
 *
 *   IFD pointers, which are offsets into the source's structure that ExifInterface
 *   maintains itself and that corrupt the output if written: ExifIFDPointer,
 *   GPSInfoIFDPointer, InteroperabilityIFDPointer, SubIFDPointer.
 *
 *   Color characterization, which describes the source's color pipeline. The source is
 *   Ultra HDR and the frame is a fresh SDR encode from a BT.709 clip, so these are wrong
 *   rather than merely redundant: ColorSpace, Gamma, WhitePoint, PrimaryChromaticities,
 *   TransferFunction, ReferenceBlackWhite, YCbCrCoefficients. (This reasoning covers the
 *   re-encode path, which is the only path this module serves. The byte-copy save path
 *   emits the original Ultra HDR bytes and never calls into here at all.)
 *
 *   Opaque binary (UNDEFINED type), which ExifInterface's String accessors cannot
 *   round-trip safely, so copying corrupts more often than it preserves: MakerNote,
 *   OECF, CFAPattern, SpatialFrequencyResponse, DeviceSettingDescription, and — despite
 *   all describing the capture rather than the bytes — SceneType, FileSource,
 *   UserComment, GPSProcessingMethod and GPSAreaInformation. Not theoretical: copying
 *   SceneType writes it in string format, and exiftool then reads back Unknown where the
 *   source said "Directly photographed". Losing UserComment is a real cost, and the
 *   alternative is writing a corrupted value, which is worse. `ExifStructureTest` fails
 *   the build if an UNDEFINED-format tag is ever added back.
 *
 *   Tags ExifInterface declares a TAG_* constant for but has no entry for in its own tag
 *   table, so setAttribute silently does nothing: LensSerialNumber. That one looks like
 *   preserved metadata until someone reads the output.
 *
 *   Exif structure identity, describing the new file's own Exif block that ExifInterface
 *   writes: ExifVersion, FlashpixVersion, ComponentsConfiguration, InteroperabilityIndex.
 *
 *   ImageUniqueID, which identifies one specific image. Two files asserting the same
 *   unique ID is worse than one file having none.
 *
 *   RAW-only tags that cannot occur in a Pixel JPEG: DNGVersion, DefaultCropSize, every
 *   TAG_ORF_*, every TAG_RW2_*, NewSubfileType, SubfileType.
 *
 *   Orientation, which is written as a constant instead — see ExifMetadataCopier.
 *
 * Inward versus outward references decide two of those cases, and the distinction is
 * easy to miss. Xmp and the thumbnail offsets point *inward*, at content within this
 * file; copied onto a smaller output they are self-contradictory. RelatedSoundFile
 * points *outward*, at a sibling file; its validity was always environmental (move the
 * original still and it dangles too), so copying it makes no false claim about the
 * frame's own bytes. Hence one is omitted and the other is copied.
 */

/** A datetime tag paired with the sub-second tag that refines it. */
data class TimestampTags(
    val dateTime: String,
    val subSecond: String,
)

/**
 * The GPS IFD in full, minus its pointer.
 *
 * None of these describe bytes, so the rule is simply "all of it" rather than a
 * judgment call per tag. The latitude and longitude refs are not optional extras — a
 * latitude without its N/S ref is meaningless, and dropping one silently mirrors a
 * location across the equator.
 *
 * GPSTimeStamp and GPSDateStamp record when the *fix* was taken, not when the shutter
 * fired, so unlike the capture timestamps they are copied unshifted.
 *
 * Two members of the IFD are missing, so this is "all of it" with an asterisk:
 * GPSProcessingMethod and GPSAreaInformation are UNDEFINED-format and cannot survive
 * ExifInterface's String accessors. `ExifStructureTest` enforces that.
 */
val GPS_TAGS: List<String> = listOf(
    ExifInterface.TAG_GPS_VERSION_ID,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_SPEED,
    ExifInterface.TAG_GPS_SPEED_REF,
    ExifInterface.TAG_GPS_TRACK,
    ExifInterface.TAG_GPS_TRACK_REF,
    ExifInterface.TAG_GPS_IMG_DIRECTION,
    ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
    ExifInterface.TAG_GPS_DEST_LATITUDE,
    ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
    ExifInterface.TAG_GPS_DEST_LONGITUDE,
    ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    ExifInterface.TAG_GPS_DEST_BEARING,
    ExifInterface.TAG_GPS_DEST_BEARING_REF,
    ExifInterface.TAG_GPS_DEST_DISTANCE,
    ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
    ExifInterface.TAG_GPS_MAP_DATUM,
    ExifInterface.TAG_GPS_MEASURE_MODE,
    ExifInterface.TAG_GPS_SATELLITES,
    ExifInterface.TAG_GPS_STATUS,
    ExifInterface.TAG_GPS_DOP,
    ExifInterface.TAG_GPS_DIFFERENTIAL,
    ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
)

/**
 * UTC offsets for the three capture timestamps.
 *
 * Copied rather than derived: shifting a capture time by a few seconds cannot change
 * which time zone it was captured in.
 */
val TIME_ZONE_TAGS: List<String> = listOf(
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
)

/**
 * What took the photo.
 *
 * The serial numbers and owner name are identifying, and are copied deliberately: this
 * app's purpose is to produce a frame as close to the original as the format allows, and
 * mainstream editors preserve these too. Stripping metadata is a different app.
 *
 * Note ExifInterface also declares `TAG_CAMARA_OWNER_NAME`, a retained misspelling with
 * the identical string value. Listing both would silently write the tag twice.
 *
 * LensSerialNumber is absent despite fitting here: ExifInterface exports the constant but
 * has no tag-table entry for it, so setting it does nothing at all.
 */
val CAMERA_TAGS: List<String> = listOf(
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_LENS_MAKE,
    ExifInterface.TAG_LENS_MODEL,
    ExifInterface.TAG_LENS_SPECIFICATION,
    ExifInterface.TAG_BODY_SERIAL_NUMBER,
    ExifInterface.TAG_CAMERA_OWNER_NAME,
)

/**
 * Optics the still and the embedded clip genuinely share.
 *
 * This is the copied half of the exposure metadata. The clip came off the same lens
 * through the same fixed aperture, so focal length and f-number remain true of an
 * extracted frame. The per-exposure half — shutter speed, ISO, metering, white balance —
 * is omitted, because the clip ran its own auto-exposure and no per-frame values are
 * recoverable to replace them with. See the file header for the full omission list.
 *
 * SceneType and FileSource would belong here on meaning alone, and are omitted anyway:
 * both are UNDEFINED-format tags, which ExifInterface's String accessors cannot
 * round-trip. Verified with exiftool — a copied SceneType arrives as "Non-standard
 * format (string)" and reads back as Unknown rather than "Directly photographed".
 */
val OPTICS_TAGS: List<String> = listOf(
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_MAX_APERTURE_VALUE,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_SUBJECT_DISTANCE,
    ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
    ExifInterface.TAG_SENSING_METHOD,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
)

/**
 * Who the photo belongs to and what it depicts, plus print resolution.
 *
 * XResolution and friends are DPI hints for printing and say nothing about pixel count,
 * so they survive a change of image dimensions untouched.
 *
 * UserComment belongs here on meaning and is omitted anyway: it is UNDEFINED-format, and
 * a corrupted comment is worse than an absent one.
 */
val ATTRIBUTION_TAGS: List<String> = listOf(
    ExifInterface.TAG_ARTIST,
    ExifInterface.TAG_COPYRIGHT,
    ExifInterface.TAG_IMAGE_DESCRIPTION,
    ExifInterface.TAG_RELATED_SOUND_FILE,
    ExifInterface.TAG_X_RESOLUTION,
    ExifInterface.TAG_Y_RESOLUTION,
    ExifInterface.TAG_RESOLUTION_UNIT,
)

/** Every tag copied from the source unchanged. */
val SUPPORTED_TAGS: List<String> =
    GPS_TAGS + TIME_ZONE_TAGS + CAMERA_TAGS + OPTICS_TAGS + ATTRIBUTION_TAGS

/**
 * Capture timestamps, which are shifted rather than copied.
 *
 * Each datetime tag holds whole seconds only, so the matching sub-second tag carries the
 * fraction. Both must move together: shifting one without the other silently truncates
 * the result to the nearest second, which is exactly enough precision loss to make two
 * frames from the same clip sort arbitrarily in a gallery.
 */
val DERIVED_TIMESTAMPS: List<TimestampTags> = listOf(
    TimestampTags(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
    TimestampTags(ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED),
    TimestampTags(ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME),
)
