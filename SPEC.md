# SPEC.md — MotionPhotoGrabber

(Working title, display name "Motion Photo Grabber" — see CLAUDE.md if this changes.)

## User story

> I have a motion photo. The frame my camera picked is blurry / someone blinked. I
> want to pick a better frame from the embedded clip and save it as a normal photo
> that still knows when and where it was taken.

## Flow

1. User shares a motion photo to the app, or opens one from a file picker.
2. App parses the file, locates the embedded video, and reports its duration.
3. App shows the frame at the current scrub position, full-bleed, with a scrubber
   below it.
4. User scrubs. Preview updates.
5. User taps Save.
6. App decodes the frame at full resolution, encodes JPEG, copies EXIF from the
   source, and writes to shared storage.

## Module layout

```
:core-motionphoto   Pure Kotlin/JVM. Format detection, offset parsing. No Android UI.
:core-extract       Frame decoding via Media3. Android dep, no UI.
:core-exif          Read EXIF from source, write onto destination. Testable.
:app                Compose UI, ViewModel, SAF plumbing, share-target intent filter.
```

The three `core-` modules are the reason this project is worth building carefully:
they are independently testable, and `:core-motionphoto` in particular can be
verified entirely on the JVM against sample files.

## Milestones

Build strictly in this order. Each is independently verifiable.

### M1 — Format parser (`:core-motionphoto`)

Input: an `InputStream` or `File`. Output:

```kotlin
sealed interface MotionPhoto {
    data class Found(
        val stillByteRange: LongRange,
        val videoByteRange: LongRange,
        val flavour: Flavour,   // GOOGLE_XMP, SAMSUNG_MARKER, HEIC_CONTAINER
    ) : MotionPhoto
    data class NotMotionPhoto(val reason: String) : MotionPhoto
    data class Malformed(val reason: String) : MotionPhoto
}
```

Detection strategies, tried in order:
- XMP `GCamera:MicroVideoOffset` → video starts at `filesize - offset`.
- XMP `Container:Directory` / `Item` entries (newer Google format) with per-item
  lengths.
- Byte scan for the ASCII marker `MotionPhoto_Data` (Samsung), video begins after it.
- HEIC container — check whether Media3's HEIF extractor already handles this before
  writing anything custom.

**Done when:** unit tests pass against every file in `samples/`, including at least
one negative case (an ordinary JPEG must return `NotMotionPhoto`).

### M2 — Frame extraction (`:core-extract`)

Given a source file plus the video byte range from M1, return a `Bitmap` for a given
timestamp, and report the clip duration.

- Decode in place using file descriptor + offset + length. No temp files.
- Use Media3's frame extractor (see the API drift warning in CLAUDE.md — verify the
  module and import path first).
- Provide two paths: a fast/approximate one for scrub previews (nearest sync frame is
  fine, downscaled) and an exact full-resolution one for the final save.

**Done when:** an instrumented test extracts a frame from a sample and asserts
non-null, correct dimensions.

### M3 — EXIF transfer (`:core-exif`)

```kotlin
fun copyMetadata(from: File, to: File, overrides: Map<String, String> = emptyMap())
```

- Copy at minimum: GPS (lat/lon/altitude/timestamp), `DateTimeOriginal`,
  `DateTimeDigitized`, `Make`, `Model`, `LensModel`, `Orientation`, exposure fields.
- `androidx.exifinterface` has no bulk copy. Enumerate the tags you support in an
  explicit list constant — this is the honest, debuggable approach.
- Decide and document: should `DateTimeOriginal` be the original shutter time, or
  shutter time offset by the frame's position in the clip? **Default to the original
  shutter time**, so the extracted still sorts next to its siblings in a gallery.
  Make it a setting later, not now.
- Write `Software` to identify this app as the producer. Do not strip anything else.

**Done when:** a JVM test writes a frame, copies metadata, reads it back, and asserts
GPS and timestamp survive a round trip.

### M4 — UI (`:app`)

- Full-bleed preview with a `Slider` (or Media3's `DefaultTimeBar`) beneath it.
- Debounce scrub events; cancel in-flight decodes when the position changes.
- Filmstrip of periodic thumbnails is a **stretch goal**, not part of M4.
- Save button → `MediaStore` write to `Pictures/`.
- Register as a share target for `image/jpeg` so the user can share from any gallery,
  including ReFra.

**Done when:** the human confirms on-device that scrubbing is responsive and a saved
file appears in their gallery with correct location and date.

### M5 — Polish

Error states for non-motion-photos. Frame-step buttons (±1 frame). Optional PNG
output. Settings for output quality.

## Open questions to resolve with the human, not by guessing

- Which manufacturers' files must work at launch? (Drives how many samples are needed.)
- Should the saved still land in the same album as the source, or a dedicated folder?
- Is the "if user picks the default frame, return original bytes untouched"
  optimisation worth the extra code path in v1?
