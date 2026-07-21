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

## Scope

**v1 supports Pixel/Google motion photos only** — specifically the XMP
`Container:Directory` layout described under "What we know about the format". That is
the only layout the available samples cover, and shipping only what can be tested
against real files is a deliberate choice, not an oversight.

### Post-v1: single-file formats

Legacy Google `MicroVideoOffset`, Samsung, and the Google/Samsung **HEIC container**
(single file, embedded video — what Media3's `HeifExtractor` handles as of 1.9.0) are
out of scope for v1 but need no restructuring to add:

- `Flavor` already enumerates them, so a new format is a new detection branch and an
  enum value — not a change to the result type or to anything downstream of it.
- `MotionPhoto.NotSupported` exists alongside `NotMotionPhoto`, so once a format is
  recognized the UI can distinguish "a motion photo we can't read yet" from "not a
  motion photo" without a new state being threaded through.
- The parser takes bytes and a total size — never a `Context` or `Uri` — so any new
  format can be developed and verified entirely on the JVM against a sample file.

### Post-v1: Apple Live Photos are a different shape

Apple is **not** simply "the HEIC case", and the distinction is load-bearing. An Apple
Live Photo is *two* files — a HEIC still and a separate `.MOV` — paired by a shared
content identifier (maker-note key 17 in the HEIC, `quickTimeMetadataContentIdentifier`
in the MOV). Nothing is embedded.

This contradicts the one-file assumption in CLAUDE.md's domain facts, and the current
design cannot absorb it as-is:

- `Found.videoByteRange` is a range *within the source file*. For Apple no such range
  exists; the video is a sibling file that must be located and opened separately.
- M4 registers as a share target for a single image. Sharing an Apple Live Photo
  delivers only the still — the `.MOV` never reaches the app at all.

So Apple support means a second input model (a still *plus* a located video), not a new
`Flavor`. Whether to shape the v1 types for that now or refactor later is an open
question below. Structure verified from public documentation only — **no Apple sample
has been inspected**, so treat the details above as unconfirmed until one is.

The order for adding a format post-v1 is: **obtain a real sample, write a failing test
against it, then implement.** Not before. See "never fabricate a device-specific format
detail" in CLAUDE.md — a format branch written from a prose description and never run
against a real file is exactly what that rule exists to prevent.

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

## What we know about the format

Measured from real Pixel files on 2026-07-20, not assumed. Anything here that a future
sample contradicts should be corrected here rather than worked around in code.

**Container structure**

- Current Pixel files use the XMP `Container:Directory`. `GCamera:MicroVideoOffset` is
  the *legacy* form — we have no sample that uses it, so it is unverified.
- The directory sits in the **standard** XMP APP1 segment (~1.5 KB, single segment),
  identified by the `http://ns.adobe.com/xap/1.0/\0` prefix. The much larger *extended*
  XMP segments hold `HDRPlusMakerNote` and are irrelevant to us — no chunk reassembly
  is needed.
- Three items, in file order: **Primary** (image/jpeg), **GainMap** (image/jpeg),
  **MotionPhoto** (video/mp4). Items are laid out consecutively from offset 0.
- **Primary declares no `Item:Length`.** Compute it as
  `filesize − Σ(other declared lengths)`. A parser that requires the attribute fails on
  every real file.
- **Attribute order varies between files.** Parsing must be order-independent.
- **Never byte-scan for `ftyp` to locate the video.** The ASCII sequence occurs inside
  entropy-coded JPEG data; one sample has a false positive ~2 MB before the real box.
  Trust the declared lengths.

**The embedded video**

- HEVC (`hvc1`), **Main profile — 8-bit**, `colr/nclx` transfer 1 (BT.709). It is
  **SDR**. Mid-clip frames can never be HDR; there is no HDR data to recover.
- Substantially smaller and differently oriented than the still: ~1440×1080 landscape
  against a ~3072×4080 portrait still, roughly 8× fewer pixels. Clips run 1–3 seconds.
- A file may contain **more than one `vide` track**. Select the track with more than one
  sample / non-zero duration. Selecting by highest resolution picks the wrong track —
  one observed file carries a single-sample 2048×1536 track beside the real clip.
- The still is Ultra HDR: Primary JPEG plus GainMap, with `XMP-hdrgm` metadata.

**Tooling caveat**

`exiftool` misreports `GainMapImage` size as the video's length on these three-item
containers. Prefer our own parse over its output.

## Milestones

Build strictly in this order. Each is independently verifiable.

### M1 — Format parser (`:core-motionphoto`)

Input: an `InputStream` or `File`. Output:

```kotlin
sealed interface MotionPhoto {
    data class Found(
        val stillByteRange: LongRange,
        val gainMapByteRange: LongRange?,    // null when the file is not Ultra HDR
        val videoByteRange: LongRange,
        val defaultFrameTimestampUs: Long?,  // MotionPhotoPresentationTimestampUs
        val flavor: Flavor,
    ) : MotionPhoto
    /** Definitely a motion photo, but a flavor this build does not parse. */
    data class NotSupported(val flavor: Flavor, val reason: String) : MotionPhoto
    data class NotMotionPhoto(val reason: String) : MotionPhoto
    data class Malformed(val reason: String) : MotionPhoto
}

// Flavor: GOOGLE_CONTAINER, GOOGLE_MICROVIDEO, SAMSUNG_MARKER, HEIC_CONTAINER
```

Detection strategies, tried in order:
- XMP `Container:Directory` / `Item` entries. **The only flavor v1 parses.** Video is
  the last item, so `videoStart = filesize − videoLength`.
- XMP `GCamera:MicroVideoOffset` (legacy) → detected, returns `NotSupported`.
- ASCII marker `MotionPhoto_Data` (Samsung) → detected, returns `NotSupported`.
- HEIC container → **not detected in v1.** A HEIC file is not a JPEG, so it falls
  through to `NotMotionPhoto`. Detection is deliberately deferred until a real sample
  exists to test against, per the Scope section above. (Media3's HeifExtractor handles
  HEIC motion photos as of 1.9.0 but exposes no byte ranges, so it would not substitute
  for a parser here.)

Detecting-but-not-parsing is deliberate: `NotSupported` lets the UI say "this is a
motion photo we can't read yet" instead of the misleading "not a motion photo".

**Done when:** unit tests pass against every file in `samples/`, asserting exact byte
ranges, plus a negative case (ordinary JPEG → `NotMotionPhoto`) and a truncated file
(→ `Malformed`). Expected offsets are per-file and live in the test source, not here —
`samples/` is gitignored, so tests skip cleanly when it is empty.

### M2 — Frame extraction (`:core-extract`)

Given a source file plus the video byte range from M1, return a `Bitmap` for a given
timestamp, and report the clip duration.

- Decode in place using file descriptor + offset + length. No temp files.
- Use Media3's frame extractor. Verified 2026-07-20 against Media3 **1.10.1**: artifact
  `androidx.media3:media3-inspector-frame`, import
  `androidx.media3.inspector.frame.FrameExtractor`, built via
  `FrameExtractor.Builder(context, mediaItem)`; `getFrame(ms)` returns
  `ListenableFuture<FrameExtractor.Frame>`. Re-verify before use — this class has moved
  modules three times.
- Select the video track by sample count, not resolution (see format facts above).
- Frames are saved at native video resolution. No upscaling, and no UI messaging about
  the size difference from the still.
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
- Verified 2026-07-20: current stable is **1.4.2**. Its XMP quirk is JPEG-specific and
  deliberate — HEIC and PNG were fixed to prefer the separate XMP segment, but JPEG
  still prefers Exif tag 700 for backward compatibility. **Do not use ExifInterface to
  read the Container directory**; that is `:core-motionphoto`'s job. ExifInterface is
  for writing metadata onto the output only.
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
- Save button → `MediaStore` write to `Pictures/`. Two paths: if the scrub position is
  nearest the default frame, byte-copy Primary + GainMap from the source untouched
  (preserving Ultra HDR and the original EXIF); otherwise decode, encode, and copy EXIF.
- Register as a share target for `image/jpeg` so the user can share from any gallery,
  including ReFra.

**Done when:** the human confirms on-device that scrubbing is responsive and a saved
file appears in their gallery with correct location and date.

### M5 — Polish

Error states for non-motion-photos. Frame-step buttons (±1 frame). Optional PNG
output. Settings for output quality.

## Decisions made

Recorded so they are not re-litigated. Date them when they change.

- **2026-07-20 — Launch format:** Pixel/Google `Container:Directory` only. Other
  flavors return `NotSupported`.
- **2026-07-20 — Original-bytes optimization is in v1**, and that copy **includes the
  GainMap**, so the saved default frame stays Ultra HDR. Mid-clip frames are SDR
  because the source video is SDR; the asymmetry is accepted.
- **2026-07-20 — Mid-clip frames save at native video resolution**, with no special UI
  messaging about the difference.

## Open questions to resolve with the human, not by guessing

- Should the saved still land in the same album as the source, or a dedicated folder?
- Apple Live Photos are a still plus a *separate* `.MOV`, not one container. Should the
  v1 types accommodate a two-file source now, or is a later refactor accepted? This also
  decides whether M4 can stay a single-file share target.
- What counts as "nearest the default frame" for the byte-copy path — an exact
  timestamp match, or a tolerance window?
- What rotation does the embedded video carry? Landscape video inside a portrait photo
  implies a `tkhd` rotation matrix, but **no value has been verified**. Frames may
  render sideways until this is confirmed.
- Does `FrameExtractor.Builder` accept a custom `DataSource.Factory`, or a file
  descriptor with offset and length? Unverified — reference docs would not render.
  Blocks the "decode in place, no temp files" requirement in M2.
