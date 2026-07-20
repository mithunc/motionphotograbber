# CLAUDE.md

Project context for AI coding assistants. Keep this file short and factual — it is
loaded into every session. Long explanations belong in SPEC.md.

## What this app is

**Working title: MotionPhotoGrabber** (display name "Motion Photo Grabber", package
id / repo slug `motionphotograbber`). Treat this as provisional — if it changes,
update it here and in `settings.gradle.kts`, `app/build.gradle.kts` (applicationId),
and `strings.xml` in the same pass so it never drifts out of sync.

An Android app that extracts an **arbitrary frame** from a **motion photo** as a
still JPEG, **preserving the original photo's EXIF metadata** (GPS, capture
timestamp, camera model).

The gap this fills: Google Photos and Samsung Gallery can pick an arbitrary frame,
but users outside those ecosystems have no good option. Existing FOSS frame-grabbers
(VLC, Frame Extractor) produce stills with no metadata at all. Nothing on F-Droid
does both.

## Non-goals

- Not a gallery app. It receives a file, does one job, returns a file.
- Not a video editor. No trimming, filters, or effects.
- No network access. No analytics. No ads. The app must build with zero
  internet-requiring permissions.

## Hard constraints

- **Language:** Kotlin only.
- **UI:** Jetpack Compose, Material 3. No XML layouts, no Fragments.
- **Min SDK:** 26. Target the current stable SDK.
- **License:** Apache License 2.0. Every new source file gets the standard Apache
  header (see `LICENSE`/`NOTICE` at repo root for the exact boilerplate). All
  dependencies must be F-Droid-compatible (no proprietary blobs, no Google Play
  Services, no Firebase) — Apache 2.0 is F-Droid-accepted and matches the license of
  Media3/AndroidX, our main dependency, so keep it consistent rather than mixing in
  copyleft dependencies without asking first.
- **Offline:** Do not add the `INTERNET` permission. If a proposed library requires
  it, propose a different library instead.
- **Architecture:** MVVM. ViewModel holds state, Composables are stateless where
  practical. Business logic (parsing, extraction, EXIF) lives in plain Kotlin
  classes with no Android UI dependencies so it is unit-testable.

## Ask, don't assume

This overrides normal "just pick a sensible default and proceed" behavior. On this
project, prefer asking a short, specific question over silently choosing for any of:

- Ambiguous or underspecified requirements — anything SPEC.md doesn't already answer.
- A choice with more than one reasonable design (e.g. "should Save overwrite or
  create a new file?", "what happens if the user backs out mid-scrub?").
- Anything touching UX wording, icon/branding choices, or file/folder naming the
  human will see.
- A device-specific format detail you're not certain of (see rule below — this one
  is non-negotiable, not just a preference).
- Whether to add a new dependency, even a small one.

It's fine to proceed without asking for genuinely mechanical choices with only one
reasonable answer (variable names, which existing pattern in the codebase to follow,
formatting). When in doubt about which bucket something falls in, ask. A wrong
assumption costs more of the human's time to find and unwind than a short question
costs to answer.

## Verification rules

Read these before writing code.

1. **You cannot run this app.** You have no device and no emulator. Never claim a
   feature "works" or is "tested" — say what you implemented and what the human
   needs to verify on-device.
2. **The parser layer must be JVM-unit-testable.** Motion photo parsing and EXIF
   copying take `InputStream`/`ByteArray`/`File` inputs, not `Context` or `Uri`.
   Write tests against the real sample files in `samples/`. Run them with
   `./gradlew test` and confirm they pass before saying a milestone is done.
3. **Compile before claiming completion.** Run `./gradlew assembleDebug` and fix
   errors. A milestone is not done if the build is red.
4. **Never fabricate a device-specific format detail.** If you don't know whether a
   given manufacturer uses a given marker, say so and ask for a sample file. Do not
   guess and move on — this is the "ask, don't assume" rule applied to the one place
   a wrong guess is hardest to notice later.

## API drift warning — READ THIS

Several APIs central to this project changed recently. **Your training data is
probably wrong about them.** Before writing any code that touches these, check the
current docs or the version actually resolved in the Gradle build:

- **Media3 frame extraction.** This class has moved modules more than once. It began
  life as `androidx.media3.transformer.ExperimentalFrameExtractor`, then became
  `androidx.media3.inspector.FrameExtractor` in a new `media3-inspector` module, and
  a later release moved it again to `androidx.media3.inspector.frame.FrameExtractor`
  in a `media3-inspector-frame` module. **Do not guess the import.** Check
  https://developer.android.com/jetpack/androidx/releases/media3 and pin an explicit
  version in `libs.versions.toml`.
- **Media3 motion photo support.** Recent releases added HEIC motion photo parsing to
  the built-in HEIF extractor. Check whether this covers our input formats before
  hand-rolling a parser for them.
- **`androidx.exifinterface`.** Check the current stable version. Note its documented
  quirk of preferring XMP inside the Exif segment over XMP outside it, which matters
  because motion photo offsets live in XMP.

When in doubt, ask the human to paste current docs rather than guessing.

## Key domain facts

- A Google/Pixel-style motion photo is a JPEG whose XMP contains a
  `GCamera:MicroVideoOffset` (or `Container`/`Item` directory in newer files). The
  offset is measured **from the end of the file**, so video start = filesize − offset.
- A Samsung-style motion photo is a complete JPEG, then a marker containing the
  ASCII string `MotionPhoto_Data`, then a complete MP4.
- Because the still and the video are one file, you can decode the video **in place**
  using a file descriptor plus offset and length. Do not write a temporary MP4 to
  disk unless a specific API forces it.
- The default still (shutter-press frame) is the plain JPEG at the front of the file
  and already carries full EXIF. **Only mid-clip frames lose metadata.** If the user
  picks the frame nearest the default, prefer returning the original bytes.

## Style

- Explicit types on public APIs. Inference is fine locally. This is about *types*, not
  visibility keywords — see the next rule.
- No redundant visibility modifiers. Kotlin is public by default, so writing `public`
  adds nothing. It would only be required if a module enabled `explicitApi()`, which
  none currently do.
- American English throughout — identifiers, comments, and prose. `Flavor`, not
  `Flavour`.
- Constants go at the top of a class or object, not next to their first use.
- Nested type declarations go at the top as well, above the functions.
- Sealed classes/interfaces for result and error states. No exceptions across module
  boundaries for expected failures (e.g. "this file is not a motion photo").
- Comment *why*, not *what*. Byte-offset arithmetic deserves a comment; a `for` loop
  does not.
- No new dependency without asking first. State what it does and why the stdlib or an
  existing dependency can't.

## Commands

```
./gradlew assembleDebug        # build
./gradlew test                 # JVM unit tests (parser, EXIF logic)
./gradlew connectedAndroidTest # instrumented tests (needs a device)
./gradlew lint
```

## Workflow

Work one milestone at a time (see SPEC.md). For each: explore relevant files first,
propose a plan — flagging any open questions per "Ask, don't assume" above — wait for
approval, implement, run tests and build, then stop and report. Do not start the next
milestone unprompted. Commit at each green milestone.

If a question comes up mid-implementation rather than during planning, stop and ask
rather than finishing the milestone on an assumption and mentioning it afterward.
