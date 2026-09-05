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
- **That order is required by the format, not merely observed.** Verified 2026-09-05
  against <https://developer.android.com/media/platform/motion-photo-format>:
  - "The directory may contain only one primary image item and it must be the first item
    in the directory."
  - "Media items must be located in the container file in the same order as the media item
    elements in the directory and must be tightly packed."
  - "writers encoding motion photos must place the gainmap item element before the video
    item element."
  - Of the video item: "The location of this media item must be at the end of the file. No
    other bytes may be placed after this media item's bytes have terminated."

  `MotionPhotoParser` asserts the first and the last of these and returns `Malformed`
  otherwise; together they imply the other two, because tightly-packed-from-zero makes
  "the video is the last item" and "the video ends at EOF" the same statement. This is
  load-bearing rather than decorative: `MotionPhotoStillWriter` recovers the still by
  copying a **prefix** of the file, which is correct only under exactly this layout.
- **Primary declares no `Item:Length`.** Compute it as
  `filesize − Σ(other declared lengths)`. A parser that requires the attribute fails on
  every real file.
- **Attribute order varies between files.** Parsing must be order-independent.
- **Never byte-scan for `ftyp` to locate the video.** The ASCII sequence occurs inside
  entropy-coded JPEG data; one sample has a false positive ~2 MB before the real box.
  Trust the declared lengths — then *verify* them. Scanning for `ftyp` is unsound, but
  checking for it at the offset the lengths produced is sound and is what catches a
  truncated or mis-declared file. See "Declared lengths must be checked against the
  bytes" under M1.

**The embedded video**

- HEVC (`hvc1`), **Main profile — 8-bit**, `colr/nclx` transfer 1 (BT.709). It is
  **SDR**. Mid-clip frames can never be HDR; there is no HDR data to recover.
- Substantially smaller and differently oriented than the still: ~1440×1080 landscape
  against a ~3072×4080 portrait still, roughly 8× fewer pixels. Clips run 1–3 seconds.
- A file may contain **more than one `vide` track**. Select the track with more than one
  sample / non-zero duration. Selecting by highest resolution picks the wrong track —
  one observed file carries a single-sample 2048×1536 track beside the real clip.
- The video **is rotated, and the angle varies per file.** Measured from the `tkhd`
  matrices on 2026-07-20: two samples declare 90°, one declares 270°. This is what makes
  a landscape clip sit inside a portrait photo. Hardcoding 90° would silently produce
  upside-down frames on 270° files.
- The still is Ultra HDR: Primary JPEG plus GainMap, with `XMP-hdrgm` metadata.
- **Sync samples are sparse, and that decides how the preview seeks.** Counted from the
  `stss` boxes on 2026-07-26:

  | Sample | Track | Duration | Samples | Sync samples | fps |
  | --- | --- | --- | --- | --- | --- |
  | `..._011104182` | 1440x1080 | 2.60 s | 75 | 11 | 28.8 |
  | `..._051005777` | 1440x1080 | 2.08 s | 50 | 8 | 24.1 |
  | `..._051005777` | 2048x1536 | 0.00 s | 1 | 1 | — (the decoy track) |
  | `..._091528579` | 1440x1080 | 1.27 s | 20 | **3** | 15.7 |

  Seeking to the nearest sync frame would show **three** distinct images across the whole
  1.27 s clip. Exact seeking is therefore mandatory, not a preference — and it is cheap
  here, at most ~7 frames of 1440x1080 HEVC decoded from the preceding keyframe. Frame
  rates also vary far more than expected, and a 20-frame clip means a continuous slider
  addresses only 20 distinct images.

**The motion-photo declarations are not all in the container**

Measured 2026-07-26 while building M4's byte-copy path. Beside the `Container:Directory`,
`rdf:Description` carries `GCamera:MotionPhoto="1"`, `GCamera:MotionPhotoVersion="1"` and
`GCamera:MotionPhotoPresentationTimestampUs`. `:core-motionphoto` ignores these — it goes
by container items — but a gallery plausibly keys its play-button badge on the first.
**Anything producing a plain still from a motion photo must clear these as well as the
container item**, or the output announces a clip it does not contain. The XMP packet has
no `<?xpacket>` wrapper and ~1 byte of trailing whitespace, so there is no slack to
shrink into; `hdrgm:Version` and `xmpNote:HasExtendedXMP` sit outside the directory and
must survive.

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

#### Declared lengths must be checked against the bytes

**`Found` guarantees its ranges point at data of the declared type.** Computing offsets
from the XMP alone is not enough to earn that.

Because the Primary item declares no `Item:Length`, it absorbs whatever the other items
leave over — so `implied = filesize − Σ(declared)` *always* balances, no matter how
wrong the file is. A truncated file, or one whose XMP misstates a length, still yields a
complete, plausible, entirely incorrect set of ranges. There is no arithmetic check that
can catch this: a "do the lengths span the file?" test is provably dead code whenever
exactly one item omits its length.

So the parser verifies each item begins with the data its mime type implies:

| Declared mime | Checked | Why |
| --- | --- | --- |
| `video/mp4` | ASCII `ftyp` at bytes 4–8 of the range | ISO/IEC 14496-12 places the FileTypeBox first; present in every sample |
| `image/jpeg` | `FFD8` at the start of the range | Each image item is a complete JPEG |
| anything else | nothing | Never guess at an item type we have not verified — an unfamiliar item must not cause a valid file to be rejected |

A mismatch is `Malformed`. This is a deliberate strictness trade: a real motion photo
that led with some box other than `ftyp` would now be rejected. That is preferable to
returning ranges that look reasonable and are not, because everything downstream —
frame extraction, and the v1 byte-copy save path — would act on them and write the
wrong bytes into the user's gallery.

The check reads 8 bytes at an offset rather than loading the file, so it costs nothing
on an 8 MB photo.

**Done when:** unit tests pass against every file in `samples/`, asserting exact byte
ranges, plus:
- a negative case (ordinary JPEG → `NotMotionPhoto`)
- a truncated file and an XMP that misstates a length (both → `Malformed`)
- truncation at *every* byte offset, asserting the parser returns rather than throws

Expected offsets are per-file and live in the test source, not here — `samples/` is
gitignored, so those tests skip cleanly when it is empty. Coverage that does not depend
on samples is described in `samples/README.md`.

### M2 — Frame extraction (`:core-extract`)

Given a source file plus the video byte range from M1, return a `Bitmap` for a given
timestamp, and report the clip duration.

- Decode in place using file descriptor + offset + length. No temp files.
- Use Media3's frame extractor. Verified 2026-07-20 against Media3 **1.10.1**: artifact
  `androidx.media3:media3-inspector-frame`, import
  `androidx.media3.inspector.frame.FrameExtractor`, built via
  `FrameExtractor.Builder(context, mediaItem)`; `getFrame(ms)` returns
  `ListenableFuture<FrameExtractor.Frame>`. Now compiled against, not just read from docs.
  Note `media3-inspector` still exists at 1.10.1 but no longer holds `FrameExtractor`, so
  depending on the wrong one resolves in Gradle and fails only at the import.
- Select the video track by sample count, not resolution (see format facts above).
  **Observed 2026-07-20:** Media3 already picks the real clip track on the one sample that
  carries a single-sample decoy, so no explicit selection is wired up. This is observed
  behavior, not something the code enforces — `FrameExtractorOrientationTest` guards it by
  asserting the returned frame lands near the requested position, which a single-sample
  track could not do.
- Frames are saved at native video resolution. No upscaling, and no UI messaging about
  the size difference from the still.
- Provide two paths: a fast/approximate one for scrub previews (nearest sync frame is
  fine, downscaled) and an exact full-resolution one for the final save.

**Done when:** an instrumented test extracts a frame from a sample and asserts
non-null, correct dimensions.

### M3 — EXIF transfer (`:core-exif`)

```kotlin
fun copyMetadata(
    from: JpegFile,
    to: JpegFile,
    editingSoftware: String,
    frameOffset: Duration,
    overrides: Map<String, String> = emptyMap(),
): CopyResult
```

`JpegFile` is a value class whose factory checks the file exists, is readable, and starts
with `FFD8`. `ExifInterface` cannot create a destination, so that precondition is worth a
type rather than a doc comment. The guarantee is checked at construction, not maintained
over time, which is why `CopyResult.DestinationUnwritable` still exists.

**The governing rule is that every tag on the output must be true of the output.** Tags
fall into four dispositions — copied verbatim, derived, computed from the output, or set
to a constant. Details and the full omission list live in `ExifTags.kt`; the summary:

- Copy: the whole GPS IFD, the `OffsetTime*` zone tags, camera and lens identity
  (including `BodySerialNumber` — this app preserves metadata, it does not scrub it),
  shared optics, attribution, print resolution.
- Derive: the three capture timestamps and their `SubSecTime*` counterparts.
- Compute: `PixelXDimension`/`PixelYDimension`, read off the output's own SOF markers.
- Constant: `Orientation` and `Software`.
- **Do not copy `Orientation`.** Media3 has already applied the clip's rotation (see the
  2026-07-20 decision), so the frame is upright; copying a portrait still's `6` makes
  every viewer rotate it another 90°. Written as `1` explicitly.
- **Do not copy `Xmp`.** The source's XMP *is* the `Container:Directory` — carrying it
  over makes the frame advertise an embedded video at offsets that do not exist in it,
  and `:core-motionphoto` would then parse a plain still as a motion photo.
- **Split the exposure fields.** Optics the still and clip genuinely share (f-number,
  focal length, aperture) are true of the frame and are copied. Per-exposure values
  (shutter speed, ISO, metering, white balance) are not: the clip ran its own
  auto-exposure and Media3 exposes no per-frame values to substitute.
- `androidx.exifinterface` has no bulk copy — it offers no way to enumerate the tags a
  file holds, so the only copyable tags are the ones named in an explicit list constant.
  That is a necessity, not a preference, and it happens to be the debuggable approach
  too. The list came from triaging all 161 `TAG_*` constants (identical in 1.3.6 and
  1.4.2), not from recall.

#### Two library constraints the tag list has to respect

Both were found by reading the output bytes, and both are enforced by
`ExifStructureTest` so they cannot regress.

1. **A tag whose Exif format is `UNDEFINED` cannot be copied at all.** `ExifInterface`
   exposes only `String` accessors, so `setAttribute` writes such a tag as ASCII and the
   value is silently wrong — `getAttribute` still returns it intact, which is why a
   round-trip test cannot see the damage. Confirmed with exiftool: a copied `SceneType`
   reads back as `Unknown` where the source said `Directly photographed`. This rules out
   `SceneType`, `FileSource`, `UserComment`, `GPSProcessingMethod` and
   `GPSAreaInformation`, all of which describe the capture and would otherwise qualify.
   Losing `UserComment` is a genuine cost; writing a corrupted one is worse.
2. **Some `TAG_*` constants have no entry in `ExifInterface`'s own tag table**, so
   setting them does nothing. `LensSerialNumber` is one. It looks like preserved
   metadata until someone reads the file.

#### What ExifInterface adds on its own

Any file it saves also gains `ImageWidth`, `ImageLength`, `Orientation` and
`LightSource`, written as `LONG`. Verified with a control: a blank JPEG given nothing but
`Software` comes back carrying all four. exiftool flags `ImageWidth`/`ImageLength` in
IFD0 as `[minor] not allowed in JPEG`, and `LightSource` as a non-standard format. These
are the library's business, not ours — the values are accurate, real readers use the SOF
markers regardless, and overriding them would mean patching the library's output after
every save. Left alone deliberately.
- Verified 2026-07-20: current stable is **1.4.2**. Its XMP quirk is JPEG-specific and
  deliberate — HEIC and PNG were fixed to prefer the separate XMP segment, but JPEG
  still prefers Exif tag 700 for backward compatibility. **Do not use ExifInterface to
  read the Container directory**; that is `:core-motionphoto`'s job. ExifInterface is
  for writing metadata onto the output only.
- **`DateTimeOriginal` is offset by the frame's position in the clip** (2026-07-26,
  reversing the 2026-07-20 decision — see "Decisions made"). Not a user setting.
  Two things this requires:
  - The offset is **signed**. The shutter press sits *inside* the clip, at
    `MotionPhotoPresentationTimestampUs` — 1.40 s, 0.85 s and 0.20 s on the three
    samples — so frames before it are ordinary, not an edge case, and the offset is
    `framePosition − defaultFrameTimestampUs`.
  - `SubSecTime*` must move with its datetime tag. `DateTimeOriginal` holds whole
    seconds, so shifting it alone truncates the result and two frames from one clip
    sort arbitrarily. Written at microsecond width, because the clip's frame positions
    arrive in microseconds and rounding to milliseconds discards a value we hold.
- Write `Software` to identify this app as the producer. Supplied by the caller, not
  hardcoded here: both the app's name and its version are subject to change. `:app`
  composes it from `R.string.app_name` and `BuildConfig.VERSION_NAME`.
- Do not strip anything else.

**Done when:** a JVM test writes a frame, copies metadata, reads it back, and asserts
GPS and timestamp survive a round trip. ✅ 2026-07-26 — 19 tests, two tiers (synthetic
fixtures that run in a fresh clone, plus every file in `samples/`).

`ExifInterface` needs one accommodation to run under JVM unit tests:
`unitTests.isReturnDefaultValues` **plus** a real `android.util.Pair` in the test source
set. `setAttribute` unboxes a `Pair` returned by `guessDataFormat`, and the mockable
`android.jar` strips constructor bodies, so the fields arrive null. Nothing else in the
File-based JPEG path needs the same treatment.

### M4 — UI (`:app`)

- Full-bleed preview with a `Slider` (or Media3's `DefaultTimeBar`) beneath it.
- Debounce scrub events; cancel in-flight decodes when the position changes.
- Filmstrip of periodic thumbnails is a **stretch goal**, not part of M4.
- Save button → `MediaStore` write to `Pictures/`. Two paths: if the scrub position is
  nearest the default frame, copy Primary + GainMap from the source (preserving Ultra HDR
  and the original EXIF); otherwise decode, encode, and copy EXIF.
- **That first path is not a pure byte copy** (noticed 2026-07-26 while building M3). The
  Primary's EXIF is complete and correct, so nothing there needs repair — but its XMP
  holds the `Container:Directory` declaring three items, the third a `video/mp4`. Write
  out Primary + GainMap alone and the output still advertises a video it does not
  contain, with lengths that no longer add up; `:core-motionphoto` would return
  `Malformed` on our own output. The path must rewrite the XMP to drop the MotionPhoto
  item and correct the remaining lengths, while preserving the `hdrgm` gain-map metadata
  that keeps the file Ultra HDR. **Inferred from the format facts above, not verified
  against a real gallery** — get a real file through the path and check before relying
  on any of it.
- Set `MediaStore.DATE_TAKEN` explicitly, in milliseconds, rather than leaving the
  scanner to re-derive it from EXIF. `DATE_TAKEN` is what a gallery sorts on, and it is
  what makes two frames extracted from one clip appear in capture order.
- Compose the `Software` string M3 requires from `R.string.app_name` and
  `BuildConfig.VERSION_NAME`.
- Register as a share target for `image/jpeg` so the user can share from any gallery,
  including ReFra.

**Done when:** the human confirms on-device that scrubbing is responsive and a saved
file appears in their gallery with correct location and date.

**Device run 2026-07-26** (Pixel 10 Pro XL, Android 16). Confirmed working: install; byte-exact
`_original.jpg` (output size equals filesize − video length to the byte); full-resolution
2736x3648 default-frame save; EXIF timestamps shifted by the signed frame offset at microsecond
width; `DATE_TAKEN` set; upright rendering on the 270° sample; seeking accurate to the expected
frame at five positions; preview and save landing on the same frame; both error states with
specific, distinct messages; our own parser disowning our own output; and a cache holding one
file with no accumulation. **Sharing verified by hand from Ente Photos and Google Photos.**

Four defects found and fixed in a follow-up pass — see the 2026-07-26 decisions on GPS
redaction, JVM-test blindness, seek throttling, and surface sizing. Two things the run could
not settle and that remain open questions below: HDR rendering, and whether the location
permission covers the SAF path.

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
- **2026-07-20 — Media3 applies the video rotation; this project must not.** Measured on a
  Pixel 10 Pro XL (Android 16): a declared 1440×1080 track decodes to a 1080×1440 bitmap on
  all three samples, and the decoded frames were confirmed **visually upright** for both the
  90° and 270° files — so the direction is right, not just the shape. Rotating again would
  yield 180°-wrong frames. `FrameExtractorOrientationTest` pins this so a Media3 upgrade
  cannot change it silently.
- **2026-07-20 — `FrameExtractorOrientationTest` must not be run via `connectedAndroidTest`.**
  That task uninstalls the test APK afterward, which deletes `/sdcard/Android/data/<pkg>/`
  along with the pushed samples and the test's output PNGs. The next run then finds no
  samples and *skips*, which reads as a pass. Install once and drive it with `am instrument`
  instead; the procedure is in the test's KDoc.
- **2026-07-20 — Decode in place is achieved through `setMediaSourceFactory`.**
  `FrameExtractor.Builder` takes no `DataSource.Factory` or fd+offset+length directly, but
  a `MediaSource.Factory` is built from one, so `SubrangeDataSource` applies the byte range
  a level down. No temp MP4 is written.
- **2026-07-26 — `DateTimeOriginal` is offset by the frame's position, reversing the
  2026-07-20 decision** that defaulted to the original shutter time "so the extracted
  still sorts next to its siblings". That rationale did not hold: an offset timestamp
  still sorts adjacently, because clips run 1–3 s, *and* it disambiguates two frames
  pulled from the same clip, which identical timestamps cannot. It is also simply the
  more accurate value. **Not a user setting** — there is no plausible use case, and the
  option would only confuse.
- **2026-07-26 — Every tag written must be true of the output.** That single rule decides
  the whole M3 tag list: copy what still holds, derive what shifted, compute what can be
  measured from the output, omit what became false. In particular `Orientation` and `Xmp`
  are omitted because copying them is a bug, not a preference, and the exposure fields
  split into shared optics (copied) and per-exposure values (omitted).
- **2026-07-26 — `ColorSpace` is neither copied nor synthesized, for now.** Copying is
  wrong: the tag describes the *source's* color encoding, and its value is device
  specific. The Pixel samples say `Uncalibrated` (0xFFFF), which means "not sRGB, look at
  the ICC profile or `InteropIndex`" — carried onto a frame that has neither, it tells a
  color-managed reader to distrust sRGB and gives it nothing to use instead, so colors
  can come out oversaturated. Another camera in Adobe RGB mode would write `Uncalibrated`
  + `InteropIndex` `R03`, with the same problem. Synthesizing `1` (sRGB) is very likely
  right for an SDR frame out of `Bitmap.compress`, but `:core-exif` cannot observe what
  the encoder did, so that waits for M4. Omitting is technically non-conformant and in
  practice identical to writing `1`, since readers default to sRGB for a JPEG with no
  profile. Revisit once the encode path exists.
- **2026-07-26 — `ACCESS_MEDIA_LOCATION` is declared, and it is the app's only permission.**
  Android zeroes the GPS IFD on every `ContentResolver` read — MediaStore **and SAF** — without
  it. Measured on device: the app's cached copy was byte-identical to the on-disk original
  apart from **41 bytes in range 937–1162**, at the same total length, so nothing downstream
  can detect the loss let alone recover it. Preserving GPS is the whole point of the app, so
  the zero-permission manifest had to go. Requested **on first Save**, not on load or at
  launch: redaction happens at read time, so a grant is only actionable by re-reading, and
  asking at the moment the user commits to an output is the only place the request explains
  itself. A denial saves without location and is never asked about again in that process.
- **2026-07-29 — `ACCESS_MEDIA_LOCATION` alone is sufficient, including for SAF.** Measured by
  granting only that permission with `READ_MEDIA_VISUAL_USER_SELECTED` explicitly *not* granted:
  a document opened through `ACTION_OPEN_DOCUMENT` came back with real coordinates, and the
  saved frame carried latitude, longitude, altitude, date stamp and image direction identical
  to the source. **`READ_MEDIA_IMAGES` is not needed** and must not be added for this.
- **2026-07-29 — The system permission dialog is broader than the request, and that is
  unresolved.** `ACCESS_MEDIA_LOCATION` sits in the `READ_MEDIA_VISUAL` group, so requesting it
  raises the group prompt — *"Allow Motion Photo Grabber to access photos and videos on this
  device?"* with **Allow limited access / Allow all / Don't allow** — not a location-specific
  one. Our own rationale says "required to copy GPS coordinates", which is true of what the app
  does but reads as an understatement of what the system is about to ask for. Noted as an open
  question below rather than papered over.
- **2026-07-26 — The JVM tests structurally cannot catch a redaction bug.** They read
  `samples/*.jpg` straight off disk and never cross a `ContentResolver`. Any future claim about
  metadata must be verified against a file the app read through a `content://` Uri.
- **2026-07-26 — Seeking must be throttled to one decode in flight.** The original M4 code
  fired `seekTo` on every slider callback, justified by "ExoPlayer supersedes a seek still in
  flight". That is true and it was exactly the bug: **superseding discards the in-flight decode
  rather than accelerating it.** At ~60–120 callbacks a second against exact seeks needing up
  to ~7 frames decoded from the preceding keyframe, every seek was killed by its successor and
  nothing reached the screen until the finger stopped. `MotionPhotoPreviewPlayer` now pumps
  from a `StateFlow` and waits for `onRenderedFirstFrame` before taking the next position, so
  intermediate positions are skipped rather than queued and every decode that starts paints.
- **2026-08-08 — A seek is "done" on `onRenderedFirstFrame` *or* `STATE_READY`, never the first
  alone.** Waiting only on the render callback left fast scrubbing freezing in bursts. Traced:
  **9 of 39 seeks produced no render callback at all**, and none arrived late either
  (`staleSignals` was 0 throughout), so each burned the full timeout. The cause is that
  `onRenderedFirstFrame` means "a *new* frame was painted", which a seek resolving to the frame
  already on screen legitimately never does. `STATE_READY` reports that the seek resolved
  regardless. After the change: **0 timeouts in 144 seeks, and the update rate went 5.9 → 16.9
  per second** — saturating a clip that only holds 15.7 distinct frames per second. The two
  callbacks proved near mutually exclusive in practice (1 stale signal in 144), which is the
  diagnosis confirming itself.
- **2026-08-08 — An exact seek costs 46–100 ms, median 72 (Pixel 10 Pro XL).** Measured, closing
  the open question about seek cost. This is what sets the scrub update ceiling, and it is why
  the settle timeout is 250 ms: comfortably above the observed maximum, so it only fires on a
  genuinely missed signal, and cheap enough when it does that a miss is a hitch rather than the
  half-second stall the original 500 ms produced. A decoded-frame cache would not obviously help
  — the pump already updates faster than these clips contain distinct frames.
- **2026-08-08 — The seek pump carries permanent, opt-in tracing.** `Log.isLoggable` gated behind
  `adb shell setprop log.tag.MotionPhotoSeek VERBOSE`, silent otherwise. It is the only place
  that knows both when a seek was issued and when it settled, and it earned its keep immediately
  by distinguishing three candidate causes of the freeze that were indistinguishable by
  inspection.
- **2026-07-26 — The preview surface is sized from `VideoSize`, not stretched to fill.** A bare
  `SurfaceView` scales content to its own bounds; the first M4 build handed it `fillMaxSize()`
  and distorted every preview (measured: 1080x1440 video in a 1080x1622 box, correlation 0.9999
  against a stretched candidate versus 0.7096 against an aspect-preserved one). Fixed with
  `onVideoSizeChanged` plus a letterbox, **not** by adding `media3-ui` for `PlayerView`.
  `VideoSize.unappliedRotationDegrees` is deliberately *not* consulted: it is deprecated in
  1.10.1, and `onVideoSizeChanged` already reports the size of frames as rendered, so correcting
  for rotation here would transpose the one case it was meant to fix.
- **2026-07-26 — The scrub preview is a paused player, not repeated frame extraction.**
  `FrameExtractor` documents nothing about concurrent `getFrame` calls, and cancelling one
  only calls `cancel(false)` on the future, so a superseded decode runs to completion
  regardless — a debounce plus a mutex would be mitigating that rather than solving it.
  ExoPlayer preempts a pending seek natively and renders to a hardware surface, which is
  also how stock galleries scrub these. `MotionPhotoFrameExtractor` keeps the save path,
  where one exact full-resolution `Bitmap` is exactly what is wanted.
- **2026-07-26 — Both paths seek exactly**, closing the "approximate for preview, exact
  for save?" question. The sync-sample counts above are the reason; `CLOSEST_SYNC` is a
  trap that looks like an optimization.
- **2026-07-26 — "Nearest the default frame" is a snap detent**, not a tolerance window.
  The slider magnetizes to `defaultFrameTimestampUs` within 2% of the clip and lands on it
  exactly, so "should this byte-copy?" stays an equality test and the quality cliff sits
  at a position the user can feel. An unlabeled tick marks it; there is deliberately no
  text about the resolution or HDR difference, per the 2026-07-20 decision.
- **2026-07-26 — `ColorSpace` is written as `1` (sRGB), closing the deferral above.**
  `:core-exif` still cannot observe the encoder, which is why it remains omitted there —
  but `:app` *is* the encoder, so it supplies the value through `copyMetadata`'s
  `overrides`. That parameter existing for exactly this case is why no signature changed.
- **2026-07-26 — `MediaStore.DATE_TAKEN` is derived in `:core-exif`, not `:app`.**
  `ExifInterface.getDateTimeOriginal` is `@RestrictTo(LIBRARY)` — it compiles from outside
  the library and only `lint` objects — so `ExifMetadataCopier.readCaptureTimeMs` resolves
  the wall-clock time and `OffsetTimeOriginal` instead, keeping Exif date handling in one
  module.
- **2026-08-08 — `ACCESS_NETWORK_STATE` is stripped; `WAKE_LOCK` is left in place.** Media3
  declares the network permission for adaptive-streaming bandwidth estimation, which this app
  cannot use — it decodes a byte range of a local file and never opens a socket — so it was
  removed with `tools:node="remove"`. Safe because Media3 treats it as optional, traced through
  the 1.10.1 sources rather than assumed: `ExoPlayerImpl`'s constructor eagerly builds
  `DefaultBandwidthMeter`, which constructs `NetworkTypeObserver`, whose `ConnectivityManager`
  query is wrapped in `catch (SecurityException e) { // Expected if permission was revoked }`
  and degrades to `NETWORK_TYPE_UNKNOWN`. **The path is reached on every player construction,
  not rarely** — both `MotionPhotoPreviewPlayer` and `FrameExtractor`'s internal player hit it —
  which is what made a device test mandatory rather than cautious. Verified on device
  2026-08-08: no `SecurityException` in logcat across both paths, and `aapt2 dump permissions`
  on the APK shows the permission gone. `WAKE_LOCK` was **not** stripped: it has no privacy
  dimension, ExoPlayer only acquires it behind `setWakeMode()` which this app never calls, and
  the sole benefit would be a tidier F-Droid listing — not worth a manifest override that a
  future Media3 upgrade could silently invalidate.
- **2026-08-08 — `tools:selector` cannot scope the removal of a permission two libraries
  declare.** Scoping was tried first, being the better record of intent. It does not work here:
  `ACCESS_NETWORK_STATE` comes from both `media3-common` and `media3-exoplayer`, a selector names
  exactly one library, and covering both needs two same-named `uses-permission` nodes — which the
  merger treats as a duplicate-key collision. It warns `duplicated with element declared at`,
  **ignores `tools:node="remove"` entirely, and emits the permission twice.** The failure is
  silent-by-construction: the build stays green and only a warning separates it from success, so
  the scoped form keeps the permission while looking like it removes it. Caught by reading the
  merged manifest and the merger report, which is the only reliable check on a manifest override.
- **2026-07-26 — Identifying tags are copied, not scrubbed.** `BodySerialNumber`,
  `LensSerialNumber`, `CameraOwnerName` and `Artist` all come across. This app's purpose
  is a frame as close to the original as the format allows; mainstream editors preserve
  these, and a frame that silently lost fields the original had would be the surprising
  result. Metadata stripping is a different app.

- **2026-09-05 — Robolectric is added, for `:app` only.** The three `core-` modules take bytes
  and `File`s and are JVM-testable as they stand; `:app` is the one module whose logic is
  reachable only through a `Context`, a `ContentResolver` and real resources, and it had **no
  test source set at all** — so `SourcePhoto`'s failure paths were verifiable only by hand on a
  device. MIT licensed rather than Apache 2.0 like the rest of the stack: it is
  `testImplementation` only, never reaches the APK, and so raises no F-Droid question. Recorded
  rather than glossed because a license should be a decision. Version 4.16.1, confirmed current
  by lint's own repository check rather than by a release page.
- **2026-09-05 — `junit-vintage-engine`, not a JUnit downgrade.** Robolectric's runner is JUnit 4
  and this project is JUnit 6 Jupiter throughout. Vintage runs JUnit 4 classes on the JUnit
  Platform beside Jupiter, so one `./gradlew test` drives both engines and **not one existing
  test changed**. Downgrading was the alternative and would have meant rewriting 50 tests across
  9 classes — including the three `@TestFactory`/`DynamicTest` factories that *are* the mechanism
  letting the `samples/` tiers skip cleanly on a fresh clone. Vintage is deprecated in JUnit 6
  but explicitly not slated for removal; it logs an INFO discovery issue per JUnit 4 class.
- **2026-09-05 — Tests simulate SDK 36 while the project still compiles against 37.** Robolectric
  4.16.1 ships no android-all runtime for 37. Lowering `compileSdk` to 36 was tried first and
  **is not available**: `androidx.core:core-ktx` 1.19.0 and `lifecycle-viewmodel-compose` 2.11.0
  both require compiling against 37 or later, so matching the test runtime would cost an AndroidX
  downgrade. The gap is declared once in `app/src/test/resources/robolectric.properties` rather
  than as `@Config(sdk = [36])` per class, so a new test cannot forget it. Deleting that file is
  the whole change once Robolectric supports 37. **CLAUDE.md's "target the current stable SDK"
  constraint is untouched** — only the test runtime differs, and only for `:app`.
- **2026-09-05 — One test exists solely to prove the test setup runs.** `RobolectricSetupTest`
  reads `R.string.app_name` through a Robolectric `Context`. The failure mode it guards is
  specific: if vintage is absent or discovers nothing, zero Robolectric tests run and
  `./gradlew test` still reports success, so the setup would be silently inert and the first
  symptom would be a bug reaching a device. A green build is not evidence here; the class
  appearing in the test report is.

- **2026-09-05 — The parser asserts the container item order; it does not support other
  orderings.** This **reverses an earlier in-session decision** to handle arbitrary
  orderings, including reordering the XMP `<rdf:li>` entries on write. The reversal is the
  point: the format forbids every layout that support would have handled (see the four
  quoted sentences under "Container structure"), so the feature had no case to serve, while
  the code to handle it would have had no way to be exercised or reviewed against a real
  file. Two checks in `layOutItems` cost nothing and turn `MotionPhotoStillWriter`'s
  prefix copy from accidentally correct into provably correct. All three `samples/` files
  still parse as `Found` under the assertions, which is the evidence the spec reading is
  right; had any reported `Malformed`, the change was to stop rather than loosen the check.
- **2026-09-05 — The Primary item's bytes get no separate landing check.** With Primary
  pinned at index 0 its range starts at 0, where `hasJpegStartOfImage` has already
  validated the SOI marker on the way in. A second check there would be redundant, and a
  redundant check reads as a real one.

- **2026-09-05 — `MotionPhoto.Unreadable` is a variant of its own, not a reuse of
  `Malformed`.** `parse(File)` opens and reads, and both can fail; `Malformed` means
  "claims to be a motion photo but its structure does not hold together", which is a claim
  about content that a file that never opened has given no grounds for. The cost was
  weighed — it changes a public sealed interface in `:core-motionphoto` — and turned out to
  be one added branch, `GrabberViewModel`'s being the only exhaustive `when` over the type
  in the repo. `LoadError.Unreadable` already existed to receive it. Note that `parse(File)`
  now also answers `Unreadable` for a path with **no file at it**, which previously reported
  "file is too small to be a JPEG": `File.length()` returns 0 for a missing file, so the old
  answer was a structural claim about bytes nobody had read.
- **2026-09-05 — `SourcePhoto.copyFrom` catches `IllegalArgumentException` too.** Providers
  raise it for a Uri they do not recognize, and a Uri arriving on a share intent is
  untrusted input. Recorded because widening a catch list is the kind of change that can
  hide a genuine bug in our own Uri handling behind a user-facing "could not read"; the
  judgment is that an unrecognized Uri is far more likely to come from the sender than from
  us, and a crash is the worse failure either way. The same commit moved the display-name
  query and `setRequireOriginal` *inside* the `try` — they ran above it, so a revoked share
  grant threw `SecurityException` one line above the catch written for that exact scenario.
  `SourcePhotoTest` pins both, and both were confirmed to fail against the pre-fix code
  rather than merely to pass against the fixed one.

## Open questions to resolve with the human, not by guessing

- Should the saved still land in the same album as the source, or a dedicated folder?
- Apple Live Photos are a still plus a *separate* `.MOV`, not one container. Should the
  v1 types accommodate a two-file source now, or is a later refactor accepted? This also
  decides whether M4 can stay a single-file share target.
- **How should the location prompt be worded, given the system asks for more than we want?**
  See the 2026-07-29 decision above: the system escalates to "access photos and videos on this
  device". Options not yet weighed — reword our rationale to name what the user will actually
  see, drop the custom dialog and let the system prompt speak for itself, or accept the mismatch.
  Worth deciding deliberately because it is a consent question, not a copy question.
- **Should `READ_MEDIA_VISUAL_USER_SELECTED` be stripped?** The last of the three
  library-injected permissions; the other two were settled on 2026-08-08 (see decisions below).
  Deliberately held back from that change because it is entangled with the consent-dialog
  question above, not independent of it: that permission is plausibly what puts **"Allow limited
  access"** in the system prompt, so removing it may change what the user is asked. **Unverified
  — a hypothesis about platform behavior, not a finding.** Decide it together with the wording
  question, and test it in a change of its own so the dialog's before/after is attributable.
- **Does a gallery actually render the byte-copied original as HDR?** Structure is verified —
  gain map at the expected offset, `hdrgm:Version` intact, XMP packet length unchanged — but
  whether Google Photos or a stock gallery *displays* it as HDR is not, and a `screencap` round
  trip cannot answer it because it hands back a tonemapped SDR PNG. Needs a human looking at an
  HDR-capable screen.
- **Does the preview show exactly the frame the save writes?** M4 previews through
  ExoPlayer and saves through `FrameExtractor` — two decoders, both exact-seeking the same
  track, so they should agree, but measured `FrameExtractor` drift of 8–36 ms is just over
  one frame interval at 28.8 fps. Mitigated by handing the player's *settled* position to
  the save rather than the slider's requested one; that makes agreement likely, not
  guaranteed. Accepted as an MVP compromise on 2026-07-26 with the requirement that the
  user saves exactly what they saw. Two candidate real fixes: render the player to an
  `ImageReader`-backed surface at native resolution so one decoder serves both, or
  quantize the slider to real frame boundaries read from `stts` (which also makes M5's
  frame-step buttons fall out). Capturing the `SurfaceView` with `PixelCopy` is not a
  candidate — it yields display resolution, not native.
- Does a real gallery actually reject a `Container:Directory` whose declared items no
  longer match the file? The M4 note above says the byte-copy path must rewrite the XMP,
  and our own parser certainly would reject it — but the user-visible consequence is
  unverified. Needs a real file pushed through the path and opened in Google Photos and
  a stock gallery before the rewrite is designed.
- Should the preview path use approximate seeking and the save path exact? Measured drift
  with default `SeekParameters` was 8–36 ms against a 500 ms request — under one frame
  interval on every sample — so the default may already be good enough for saves. Not yet
  decided.
